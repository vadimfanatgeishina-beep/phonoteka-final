import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ЛР13 (доп.): отдельный УЧЕБНЫЙ identity-backend для Phonoteka (только JDK, без зависимостей).
 * Запуск:  java tools/backend/IdentityServer.java 8085      (слушает ТОЛЬКО 127.0.0.1)
 * Эмулятор:  adb reverse tcp:8085 tcp:8085  → клиент ходит на http://127.0.0.1:8085 (debug cleartext только для loopback).
 *
 * POST /auth/login   {"login","password"}  → 200 {access_token, refresh_token, expires_in, scope, role} | 401 | 429
 * POST /auth/refresh {"refresh_token"}     → 200 новая пара (ротация) | 401; повтор старого refresh → отзыв всего семейства
 * POST /auth/logout  {"refresh_token"}     → 204, семейство отозвано (access тоже)
 * GET  /me  Authorization: Bearer <access> → 200 {sub, role, scope} | 401
 * GET  /pass Authorization: Bearer <access> → 200 если есть pass:view, иначе 403
 * GET  /audit Authorization: Bearer <access> → 200 если есть audit:read, иначе 403
 *
 * Токены непрозрачные (32 случайных байта), сервер хранит только SHA-256; пароли — PBKDF2-SHA256 + соль.
 * Роль и scopes назначает только сервер; клиентские поля role/scope в запросе игнорируются.
 * Учётные записи — тестовые (пароль из PHONOTEKA_DEMO_PASSWORD или "demo-pass-123").
 */
public final class IdentityServer {
    public static final long ACCESS_TTL_MS = 5 * 60_000L;
    public static final long REFRESH_TTL_MS = 7 * 24 * 3_600_000L;
    public static final int MAX_FAILURES = 5;
    public static final long LOCK_MS = 60_000L;
    private static final int MAX_BODY = 4096;
    private static final SecureRandom RNG = new SecureRandom();

    record User(String login, String role, String scope, byte[] salt, byte[] hash) {}
    record Access(String sub, String role, String scope, String family, long expiresAt) {}
    record Refresh(String sub, String family, long expiresAt) {}

    private final Map<String, User> users = new HashMap<>();
    private final Map<String, Access> access = new ConcurrentHashMap<>();
    private final Map<String, Refresh> refresh = new ConcurrentHashMap<>();
    private final Set<String> usedRefresh = ConcurrentHashMap.newKeySet();
    private final Map<String, String> usedRefreshFamily = new ConcurrentHashMap<>();
    private final Set<String> revokedFamilies = ConcurrentHashMap.newKeySet();
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    private final LongSupplier clock;
    private HttpServer server;

    public IdentityServer(LongSupplier clock, String demoPassword) {
        this.clock = clock;
        addUser("student", "EMPLOYEE", "library:read library:write pass:view", demoPassword);
        addUser("guard", "SECURITY", "library:read pass:view scan:execute", demoPassword);
        addUser("admin", "ADMIN", "library:read library:write pass:view scan:execute audit:read invite:create", demoPassword);
    }

    private void addUser(String login, String role, String scope, String password) {
        byte[] salt = new byte[16]; RNG.nextBytes(salt);
        users.put(login, new User(login, role, scope, salt, pbkdf2(password, salt)));
    }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "identity-http"); t.setDaemon(true); return t; }));
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() { if (server != null) server.stop(0); }
    public List<String> auditLog() { synchronized (audit) { return List.copyOf(audit); } }

    private void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            switch (method + " " + path) {
                case "POST /auth/login" -> login(ex);
                case "POST /auth/refresh" -> refresh(ex);
                case "POST /auth/logout" -> logout(ex);
                case "GET /me" -> protectedGet(ex, null);
                case "GET /pass" -> protectedGet(ex, "pass:view");
                case "GET /audit" -> protectedGet(ex, "audit:read");
                default -> send(ex, 404, "{\"error\":\"not_found\"}");
            }
        } catch (BadRequest e) {
            send(ex, 400, "{\"error\":\"bad_request\"}");
        } catch (RuntimeException e) {
            send(ex, 500, "{\"error\":\"server_error\"}");
        } finally {
            // дочитываем остаток тела (ограниченно), чтобы клиент получил ответ, а не RST
            try (InputStream in = ex.getRequestBody()) { in.skipNBytes(Math.min(in.available(), 64 * 1024)); } catch (IOException ignored) { }
            ex.close();
        }
    }

    private void login(HttpExchange ex) throws IOException {
        Map<String, String> body = readJson(ex);
        String login = body.get("login"), password = body.get("password");
        if (login == null || password == null || login.length() > 64 || password.length() > 128) throw new BadRequest();
        long now = clock.getAsLong();
        Long until = lockedUntil.get(login);
        if (until != null && now < until) {
            ex.getResponseHeaders().set("Retry-After", String.valueOf((until - now + 999) / 1000));
            audit.add("login_locked:" + login);
            send(ex, 429, "{\"error\":\"too_many_attempts\"}");
            return;
        }
        User u = users.get(login);
        // Для несуществующего пользователя тоже считаем PBKDF2 — одинаковое время ответа.
        byte[] salt = u != null ? u.salt() : new byte[16];
        boolean ok = MessageDigest.isEqual(pbkdf2(password, salt), u != null ? u.hash() : new byte[32]) && u != null;
        if (!ok) {
            int n = failures.computeIfAbsent(login, k -> new int[1])[0] += 1;
            if (n >= MAX_FAILURES) { lockedUntil.put(login, now + LOCK_MS); failures.remove(login); }
            audit.add("login_failed:" + login);
            send(ex, 401, "{\"error\":\"invalid_credentials\"}");
            return;
        }
        failures.remove(login); lockedUntil.remove(login);
        audit.add("login_ok:" + login);
        send(ex, 200, issue(u, newToken()));
    }

    private void refresh(HttpExchange ex) throws IOException {
        String token = readJson(ex).get("refresh_token");
        if (token == null) throw new BadRequest();
        String h = sha(token);
        long now = clock.getAsLong();
        if (usedRefresh.contains(h)) {
            String family = usedRefreshFamily.get(h);
            revokeFamily(family);
            audit.add("refresh_reuse_detected:family_revoked");
            send(ex, 401, "{\"error\":\"token_reused\"}");
            return;
        }
        Refresh r = refresh.remove(h);
        if (r == null || revokedFamilies.contains(r.family()) || now >= r.expiresAt()) {
            send(ex, 401, "{\"error\":\"invalid_token\"}");
            return;
        }
        usedRefresh.add(h); usedRefreshFamily.put(h, r.family());
        // старые access этого семейства больше не нужны
        access.values().removeIf(a -> a.family().equals(r.family()));
        audit.add("refresh_rotated:" + r.sub());
        send(ex, 200, issue(users.get(r.sub()), r.family()));
    }

    private void logout(HttpExchange ex) throws IOException {
        String token = readJson(ex).get("refresh_token");
        if (token == null) throw new BadRequest();
        Refresh r = refresh.get(sha(token));
        if (r != null) { revokeFamily(r.family()); audit.add("logout:" + r.sub()); }
        send(ex, 204, null); // идемпотентно, без утечки «токен существует»
    }

    private void protectedGet(HttpExchange ex, String requiredScope) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        Access a = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            a = access.get(sha(auth.substring(7).trim()));
            if (a != null && (clock.getAsLong() >= a.expiresAt() || revokedFamilies.contains(a.family()))) a = null;
        }
        if (a == null) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            send(ex, 401, "{\"error\":\"invalid_token\"}");
            return;
        }
        if (requiredScope != null && !Arrays.asList(a.scope().split(" ")).contains(requiredScope)) {
            audit.add("forbidden:" + a.sub() + ":" + requiredScope);
            send(ex, 403, "{\"error\":\"insufficient_scope\",\"scope\":\"" + requiredScope + "\"}");
            return;
        }
        send(ex, 200, "{\"sub\":\"" + a.sub() + "\",\"role\":\"" + a.role() + "\",\"scope\":\"" + a.scope() + "\"}");
    }

    private String issue(User u, String family) {
        long now = clock.getAsLong();
        String at = newToken(), rt = newToken();
        access.put(sha(at), new Access(u.login(), u.role(), u.scope(), family, now + ACCESS_TTL_MS));
        refresh.put(sha(rt), new Refresh(u.login(), family, now + REFRESH_TTL_MS));
        return "{\"access_token\":\"" + at + "\",\"refresh_token\":\"" + rt + "\",\"token_type\":\"Bearer\",\"expires_in\":"
            + ACCESS_TTL_MS / 1000 + ",\"role\":\"" + u.role() + "\",\"scope\":\"" + u.scope() + "\"}";
    }

    private void revokeFamily(String family) {
        if (family == null) return;
        revokedFamilies.add(family);
        refresh.values().removeIf(r -> r.family().equals(family));
        access.values().removeIf(a -> a.family().equals(family));
    }

    // ---------- helpers ----------
    static final class BadRequest extends RuntimeException {}
    private static final Pattern FIELD = Pattern.compile("\"([a-z_]{1,32})\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,256})\"");

    private static Map<String, String> readJson(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.startsWith("application/json")) throw new BadRequest();
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) { raw = in.readNBytes(MAX_BODY + 1); }
        if (raw.length > MAX_BODY) throw new BadRequest();
        String s = new String(raw, StandardCharsets.UTF_8).trim();
        if (!s.startsWith("{") || !s.endsWith("}")) throw new BadRequest();
        Map<String, String> out = new HashMap<>();
        Matcher m = FIELD.matcher(s);
        while (m.find()) if (out.put(m.group(1), m.group(2)) != null) throw new BadRequest(); // дубликаты ключей
        return out;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        if (body == null) { ex.sendResponseHeaders(code, -1); return; }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String newToken() {
        byte[] b = new byte[32]; RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 120_000, 256)).getEncoded();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8085;
        String pw = Optional.ofNullable(System.getenv("PHONOTEKA_DEMO_PASSWORD")).orElse("demo-pass-123");
        int bound = new IdentityServer(System::currentTimeMillis, pw).start(port);
        System.out.println("Phonoteka identity backend on http://127.0.0.1:" + bound + " (users: student, guard, admin)");
    }
}
