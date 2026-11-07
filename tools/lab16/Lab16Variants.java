import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ЛР16: ИЗОЛИРОВАННЫЕ учебные пары vulnerable / hardened для находок F-01…F-06.
 * Это РЕКОНСТРУКЦИЯ поведения из аудита, а не код исходного коммита eadf4b0 (его нет в архиве).
 * Код лежит в tools/, не подключён к Gradle-модулю app и не попадает ни в один APK.
 * Уязвимые варианты работают только с локальными данными в памяти: без сети, файлов и чужих систем.
 */
public final class Lab16Variants {
    private Lab16Variants() {}

    // ---------- F-01 / F-06: конфигурация manifest и release ----------
    public record ConfigFinding(String id, String message) {}
    public static List<ConfigFinding> auditManifest(String manifest) {
        List<ConfigFinding> out = new ArrayList<>();
        if (!attr(manifest, "android:allowBackup", "false")) out.add(new ConfigFinding("F-01", "allowBackup не отключён"));
        if (!attr(manifest, "android:fullBackupContent", "false") && !manifest.contains("android:dataExtractionRules="))
            out.add(new ConfigFinding("F-01", "нет правил исключения из backup/transfer"));
        if (!attr(manifest, "android:usesCleartextTraffic", "false")) out.add(new ConfigFinding("NET", "cleartext не запрещён явно"));
        if (attr(manifest, "android:debuggable", "true")) out.add(new ConfigFinding("RES", "debuggable=true в manifest"));
        return out;
    }
    public static List<ConfigFinding> auditReleaseBuild(String gradle) {
        List<ConfigFinding> out = new ArrayList<>();
        String release = block(gradle, "release {");
        if (release == null || !release.matches("(?s).*isMinifyEnabled\\s*=\\s*true.*")) out.add(new ConfigFinding("F-06", "R8 minify выключен в release"));
        if (release == null || !release.matches("(?s).*isShrinkResources\\s*=\\s*true.*")) out.add(new ConfigFinding("F-06", "shrinkResources выключен в release"));
        if (release != null && release.matches("(?s).*isDebuggable\\s*=\\s*true.*")) out.add(new ConfigFinding("RES", "release debuggable"));
        return out;
    }
    private static boolean attr(String xml, String name, String value) { return xml.matches("(?s).*" + java.util.regex.Pattern.quote(name) + "\\s*=\\s*\"" + value + "\".*"); }
    private static String block(String text, String header) {
        int start = text.indexOf(header); if (start < 0) return null;
        int depth = 0;
        for (int i = start + header.length() - 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++; else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
        }
        return null;
    }

    // ---------- F-02: доступ к каталогу без локальной аутентификации ----------
    public record Session(long authenticatedAtMillis) {}
    public interface Catalog { List<String> list(Session session, long nowMillis); }
    public static final class VulnerableCatalog implements Catalog {
        private final List<String> items; public VulnerableCatalog(List<String> items) { this.items = List.copyOf(items); }
        public List<String> list(Session session, long nowMillis) { return items; } // сессия игнорируется
    }
    public static final class HardenedCatalog implements Catalog {
        public static final long SESSION_TTL_MILLIS = 5 * 60_000L;
        private final List<String> items; public HardenedCatalog(List<String> items) { this.items = List.copyOf(items); }
        public List<String> list(Session session, long nowMillis) {
            if (session == null) throw new SecurityException("auth required");
            long age = nowMillis - session.authenticatedAtMillis();
            if (age < 0 || age > SESSION_TTL_MILLIS) throw new SecurityException("session expired");
            return items;
        }
    }

    // ---------- F-03: загрузка без предела размера ----------
    public interface Downloader { long copy(InputStream in, long declaredLength, OutputStream sink) throws IOException; }
    public static final class VulnerableDownloader implements Downloader {
        public long copy(InputStream in, long declaredLength, OutputStream sink) throws IOException { return in.transferTo(sink); }
    }
    public static final class HardenedDownloader implements Downloader {
        private final long max; public HardenedDownloader(long max) { this.max = max; }
        public long copy(InputStream in, long declaredLength, OutputStream sink) throws IOException {
            if (declaredLength > max) throw new IOException("declared length exceeds limit");
            byte[] buf = new byte[8192]; long total = 0; int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) throw new IOException("stream exceeds limit"); // chunked/лживый Content-Length
                sink.write(buf, 0, n);
            }
            return total;
        }
    }
    /** Бесконечный локальный поток нулей с самоограничением для демонстрации (защита самого теста). */
    public static InputStream zeros(long length) {
        return new InputStream() {
            long left = length;
            public int read() { if (left <= 0) return -1; left--; return 0; }
            public int read(byte[] b, int off, int len) { if (left <= 0) return -1; int n = (int) Math.min(len, left); Arrays.fill(b, off, off + n, (byte) 0); left -= n; return n; }
        };
    }
    public static final class CountingSink extends OutputStream {
        public long count; public void write(int b) { count++; } public void write(byte[] b, int off, int len) { count += len; }
    }

    // ---------- F-04: деструктивная миграция ----------
    public static final class Db { public int version; public final List<String> rows = new ArrayList<>(); public Db(int v, String... r) { version = v; rows.addAll(List.of(r)); } }
    public static final class MigrationRequired extends IllegalStateException { public MigrationRequired(String m) { super(m); } }
    public interface Migrator { void open(Db db, int target); }
    public static final class VulnerableMigrator implements Migrator {
        public void open(Db db, int target) { if (db.version != target) { if (!(db.version == 2 && target == 3)) db.rows.clear(); db.version = target; } }
    }
    public static final class HardenedMigrator implements Migrator {
        public void open(Db db, int target) {
            while (db.version < target) {
                if (db.version == 2) { db.rows.replaceAll(r -> r + "|added_v3="); db.version = 3; }
                else throw new MigrationRequired("no migration path from v" + db.version + ", data preserved");
            }
            if (db.version > target) throw new MigrationRequired("downgrade refused, data preserved");
        }
    }

    // ---------- F-05: URL с временными параметрами в диагностике ----------
    public interface Diagnostics { String failure(String url, String reason); }
    public static final class VulnerableDiagnostics implements Diagnostics {
        public String failure(String url, String reason) { return "Download failed for " + url + ": " + reason; }
    }
    public static final class HardenedDiagnostics implements Diagnostics {
        public String failure(String url, String reason) {
            String host;
            try { URI u = new URI(url); host = u.getHost() == null ? "unknown" : u.getHost().toLowerCase(Locale.ROOT); }
            catch (Exception e) { host = "invalid-url"; }
            String safeReason = reason.replaceAll("https?://\\S+", "<url>");
            return "Download failed for host " + host + ": " + safeReason;
        }
    }

    public static String read(String path) throws IOException { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)), StandardCharsets.UTF_8); }

    // ---- F-07 WebView: уязвимая версия грузит HTTP и отдаёт JS-мост с токеном; hardened — только https allowlist, без моста.
    public record WebViewConfig(boolean javaScriptEnabled, boolean jsInterface, boolean mixedContent, boolean fileAccess) {}
    public interface HelpWebView { WebViewConfig config(); String load(String url); String bridgeToken(String pageOrigin); }
    public static final class VulnerableHelpWebView implements HelpWebView {
        public WebViewConfig config() { return new WebViewConfig(true, true, true, true); }
        public String load(String url) { return url; }
        /** addJavascriptInterface("Android"): любая страница (в т.ч. подменённая MITM по HTTP) читает токен. */
        public String bridgeToken(String pageOrigin) { return "FAKE_SESSION_TOKEN"; }
    }
    public static final class HardenedHelpWebView implements HelpWebView {
        static final Set<String> HOSTS = Set.of("help.phonoteka.example");
        public WebViewConfig config() { return new WebViewConfig(false, false, false, false); }
        public String load(String url) {
            java.net.URI u;
            try { u = new java.net.URI(url); } catch (Exception e) { throw new SecurityException("bad url"); }
            if (!"https".equals(u.getScheme()) || u.getRawUserInfo() != null || u.getPort() != -1 || !HOSTS.contains(u.getHost()))
                throw new SecurityException("blocked: " + u.getScheme() + "://" + u.getHost());
            return url;
        }
        public String bridgeToken(String pageOrigin) { throw new SecurityException("no javascript interface"); }
    }

    // ---- F-08 Clipboard: уязвимая версия оставляет текст навсегда; hardened — sensitive-флаг и очистка через 30 с.
    public static final class FakeClipboard {
        public String text; public boolean sensitive;
        public void set(String t, boolean s) { text = t; sensitive = s; }
    }
    public interface NoteCopier { void copy(FakeClipboard c, String text, long now); void tick(FakeClipboard c, long now); }
    public static final class VulnerableCopier implements NoteCopier {
        public void copy(FakeClipboard c, String text, long now) { c.set(text, false); }
        public void tick(FakeClipboard c, long now) {}
    }
    public static final class HardenedCopier implements NoteCopier {
        public static final long CLEAR_AFTER_MS = 30_000;
        private String ours; private long copiedAt = -1;
        public void copy(FakeClipboard c, String text, long now) { c.set(text, true); ours = text; copiedAt = now; }
        public void tick(FakeClipboard c, long now) {
            if (copiedAt < 0 || now - copiedAt < CLEAR_AFTER_MS) return;
            if (c.text == null || c.text.equals(ours)) c.set(null, false); // чужой текст не трогаем
            copiedAt = -1; ours = null;
        }
    }
}
