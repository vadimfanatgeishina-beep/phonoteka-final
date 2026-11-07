import java.io.*;
import java.util.*;

/**
 * Запуск из корня репозитория (JDK 22+):  java tools/lab16/Lab16VariantsTest.java
 * Для каждой находки: vulnerable воспроизводит проблему (REPRODUCED), hardened её блокирует (BLOCKED).
 * В конце реальные AndroidManifest.xml и app/build.gradle.kts проверяются теми же правилами.
 */
public final class Lab16VariantsTest {
    static int checks = 0;
    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        f01f06Config(root); f02(); f03(); f04(); f05(); f07(); f08(root);
        System.out.println("Lab16 vulnerable/hardened variants: OK (" + checks + " checks)");
    }

    static void f01f06Config(String root) throws Exception {
        String vulnManifest = "<application android:allowBackup=\"true\" android:debuggable=\"true\">";
        var v = Lab16Variants.auditManifest(vulnManifest);
        expect(v.stream().anyMatch(f -> f.id().equals("F-01")), "F-01 vulnerable manifest reproduced");
        expect(v.stream().anyMatch(f -> f.id().equals("RES")), "debuggable reproduced");
        String vulnGradle = "buildTypes {\n release {\n isMinifyEnabled = false\n }\n}";
        expect(Lab16Variants.auditReleaseBuild(vulnGradle).size() == 2, "F-06 vulnerable build reproduced");
        // Реальный hardened-снимок проекта
        var m = Lab16Variants.auditManifest(Lab16Variants.read(root + "/app/src/main/AndroidManifest.xml"));
        expect(m.isEmpty(), "real manifest hardened: " + m);
        var g = Lab16Variants.auditReleaseBuild(Lab16Variants.read(root + "/app/build.gradle.kts"));
        expect(g.isEmpty(), "real release build hardened: " + g);
        System.out.println("F-01/F-06 config: REPRODUCED in vulnerable, BLOCKED in project");
    }

    static void f02() {
        long now = 10_000_000;
        List<String> items = List.of("track-1", "track-2");
        expect(new Lab16Variants.VulnerableCatalog(items).list(null, now).size() == 2, "F-02 reproduced");
        var h = new Lab16Variants.HardenedCatalog(items);
        throwsSecurity(() -> h.list(null, now), "F-02 no session");
        throwsSecurity(() -> h.list(new Lab16Variants.Session(now - Lab16Variants.HardenedCatalog.SESSION_TTL_MILLIS - 1), now), "F-02 expired");
        throwsSecurity(() -> h.list(new Lab16Variants.Session(now + 1), now), "F-02 future session");
        expect(h.list(new Lab16Variants.Session(now - 1_000), now).size() == 2, "F-02 valid session works");
        System.out.println("F-02 auth gate: REPRODUCED / BLOCKED");
    }

    static void f03() throws Exception {
        long limit = 1 << 20, hostile = 8L << 20; // 1 MiB лимит, «сервер» отдаёт 8 MiB
        var sink = new Lab16Variants.CountingSink();
        long copied = new Lab16Variants.VulnerableDownloader().copy(Lab16Variants.zeros(hostile), -1, sink);
        expect(copied == hostile && sink.count == hostile, "F-03 unbounded write reproduced");
        var hard = new Lab16Variants.HardenedDownloader(limit);
        var s2 = new Lab16Variants.CountingSink();
        expectIo(() -> hard.copy(Lab16Variants.zeros(hostile), -1, s2), "F-03 chunked over limit");
        expect(s2.count <= limit, "F-03 written bytes bounded: " + s2.count);
        expectIo(() -> hard.copy(Lab16Variants.zeros(10), hostile, new Lab16Variants.CountingSink()), "F-03 declared length");
        var s3 = new Lab16Variants.CountingSink();
        expectIo(() -> hard.copy(Lab16Variants.zeros(hostile), 100, s3), "F-03 lying Content-Length");
        expect(hard.copy(Lab16Variants.zeros(limit), limit, new Lab16Variants.CountingSink()) == limit, "F-03 exact limit allowed");
        System.out.println("F-03 download limit: REPRODUCED / BLOCKED");
    }

    static void f04() {
        var vdb = new Lab16Variants.Db(1, "a", "b");
        new Lab16Variants.VulnerableMigrator().open(vdb, 3);
        expect(vdb.rows.isEmpty() && vdb.version == 3, "F-04 data loss reproduced");
        var hdb = new Lab16Variants.Db(1, "a", "b");
        try { new Lab16Variants.HardenedMigrator().open(hdb, 3); expect(false, "F-04 should refuse"); }
        catch (Lab16Variants.MigrationRequired expected) { checks++; }
        expect(hdb.rows.equals(List.of("a", "b")) && hdb.version == 1, "F-04 data preserved");
        var down = new Lab16Variants.Db(4, "x");
        try { new Lab16Variants.HardenedMigrator().open(down, 3); expect(false, "F-04 downgrade"); }
        catch (Lab16Variants.MigrationRequired expected) { checks++; }
        expect(down.rows.equals(List.of("x")), "F-04 downgrade preserved");
        var ok = new Lab16Variants.Db(2, "a");
        new Lab16Variants.HardenedMigrator().open(ok, 3);
        expect(ok.version == 3 && ok.rows.equals(List.of("a|added_v3=")), "F-04 known 2->3 migrates");
        System.out.println("F-04 migration: REPRODUCED / BLOCKED");
    }

    static void f05() {
        String url = "https://cdn.example.test/a.mp3?token=FAKE_TEMP_TOKEN&sig=abc";
        String v = new Lab16Variants.VulnerableDiagnostics().failure(url, "timeout");
        expect(v.contains("FAKE_TEMP_TOKEN"), "F-05 token leak reproduced");
        String h = new Lab16Variants.HardenedDiagnostics().failure(url, "redirect to " + url);
        expect(!h.contains("FAKE_TEMP_TOKEN") && !h.contains("sig=") && h.contains("cdn.example.test"), "F-05 redacted: " + h);
        String u = new Lab16Variants.HardenedDiagnostics().failure("https://user:pass@host.test/x", "e");
        expect(!u.contains("pass"), "F-05 userinfo redacted");
        expect(new Lab16Variants.HardenedDiagnostics().failure("::bad", "e").contains("host"), "F-05 invalid url safe");
        System.out.println("F-05 diagnostics: REPRODUCED / BLOCKED");
    }

    static void f07() {
        var v = new Lab16Variants.VulnerableHelpWebView();
        expect(v.config().jsInterface() && v.load("http://help.phonoteka.example/faq").startsWith("http:"), "F-07 http + JS bridge reproduced");
        expect(v.bridgeToken("http://evil.test").contains("TOKEN"), "F-07 token via bridge reproduced");
        var h = new Lab16Variants.HardenedHelpWebView();
        expect(!h.config().jsInterface() && !h.config().javaScriptEnabled() && !h.config().fileAccess() && !h.config().mixedContent(), "F-07 hardened config");
        throwsSecurity(() -> h.load("http://help.phonoteka.example/faq"), "F-07 http blocked");
        throwsSecurity(() -> h.load("https://evil.test/"), "F-07 foreign host blocked");
        throwsSecurity(() -> h.load("https://user@help.phonoteka.example/"), "F-07 userinfo blocked");
        throwsSecurity(() -> h.load("file:///data/data/com.phonoteka/"), "F-07 file blocked");
        throwsSecurity(() -> h.bridgeToken("https://help.phonoteka.example"), "F-07 no bridge");
        expect(h.load("https://help.phonoteka.example/faq").startsWith("https:"), "F-07 https allowlist works");
        System.out.println("F-07 webview: REPRODUCED / BLOCKED");
    }

    static void f08(String root) throws Exception {
        var c = new Lab16Variants.FakeClipboard();
        var v = new Lab16Variants.VulnerableCopier();
        v.copy(c, "secret note", 0); v.tick(c, 3_600_000);
        expect("secret note".equals(c.text) && !c.sensitive, "F-08 clipboard kept for 1h reproduced");
        var h = new Lab16Variants.HardenedCopier();
        h.copy(c, "secret note", 0);
        expect(c.sensitive, "F-08 sensitive flag");
        h.tick(c, 29_999); expect("secret note".equals(c.text), "F-08 kept before 30s");
        h.tick(c, 30_000); expect(c.text == null, "F-08 cleared at 30s");
        h.copy(c, "secret", 0); c.set("user copied other", false); h.tick(c, 31_000);
        expect("user copied other".equals(c.text), "F-08 foreign clip untouched");
        // Реальный снимок: в app нет WebView/addJavascriptInterface, буфер очищается.
        var src = new StringBuilder();
        try (var walk = java.nio.file.Files.walk(java.nio.file.Path.of(root, "app/src/main/java"))) {
            for (var p : (Iterable<java.nio.file.Path>) walk.filter(x -> x.toString().endsWith(".kt"))::iterator) src.append(java.nio.file.Files.readString(p));
        }
        expect(!src.toString().contains("addJavascriptInterface") && !src.toString().contains("android.webkit.WebView"), "F-07 project has no WebView bridge");
        expect(src.toString().contains("clearPrimaryClip") && src.toString().contains("EXTRA_IS_SENSITIVE"), "F-08 project clears clipboard");
        System.out.println("F-08 clipboard: REPRODUCED / BLOCKED");
    }

    interface Io { Object run() throws IOException; }
    static void expectIo(Io r, String name) { try { r.run(); expect(false, name + " should fail"); } catch (IOException expected) { checks++; } }
    static void throwsSecurity(Runnable r, String name) { try { r.run(); expect(false, name + " should fail"); } catch (SecurityException expected) { checks++; } }
    static void expect(boolean ok, String name) { if (!ok) throw new AssertionError(name); checks++; }
}
