import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Запуск из корня проекта: java tools/backend/IdentityServerTest.java  (JDK 22+, multi-file source launcher) */
public class IdentityServerTest {
    static int checks = 0;
    static final AtomicLong now = new AtomicLong(1_000_000);
    static final HttpClient http = HttpClient.newHttpClient();
    static String base;

    static void expect(boolean c, String what) { checks++; if (!c) throw new AssertionError(what); }
    static HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
    static String login(String user, String pw) throws Exception {
        return post("/auth/login", "{\"login\":\"" + user + "\",\"password\":\"" + pw + "\"}").body();
    }

    public static void main(String[] args) throws Exception {
        IdentityServer s = new IdentityServer(now::get, "pw-123456");
        base = "http://127.0.0.1:" + s.start(0);
        try {
            // login + server-assigned role/scope (клиентский role игнорируется)
            String r = post("/auth/login", "{\"login\":\"student\",\"password\":\"pw-123456\",\"role\":\"ADMIN\"}").body();
            expect("EMPLOYEE".equals(field(r, "role")), "role assigned by server");
            String at = field(r, "access_token"), rt = field(r, "refresh_token");
            expect(at != null && at.matches("[A-Za-z0-9_-]{43}"), "opaque access");
            expect(get("/me", at).statusCode() == 200, "me ok");
            expect(get("/me", null).statusCode() == 401, "me without token");
            expect(get("/me", "garbage").statusCode() == 401, "me bad token");
            // scopes
            expect(get("/pass", at).statusCode() == 200, "student pass:view");
            expect(get("/audit", at).statusCode() == 403, "student no audit:read");
            String admin = field(login("admin", "pw-123456"), "access_token");
            expect(get("/audit", admin).statusCode() == 200, "admin audit:read");
            // access expiry
            now.addAndGet(IdentityServer.ACCESS_TTL_MS);
            expect(get("/me", at).statusCode() == 401, "access expired");
            // rotation
            String r2 = post("/auth/refresh", "{\"refresh_token\":\"" + rt + "\"}").body();
            String at2 = field(r2, "access_token"), rt2 = field(r2, "refresh_token");
            expect(at2 != null && !rt2.equals(rt), "rotated");
            expect(get("/me", at2).statusCode() == 200, "new access ok");
            // reuse of old refresh -> whole family revoked
            expect(post("/auth/refresh", "{\"refresh_token\":\"" + rt + "\"}").statusCode() == 401, "reuse rejected");
            expect(get("/me", at2).statusCode() == 401, "family access revoked");
            expect(post("/auth/refresh", "{\"refresh_token\":\"" + rt2 + "\"}").statusCode() == 401, "family refresh revoked");
            expect(s.auditLog().contains("refresh_reuse_detected:family_revoked"), "reuse audited");
            // logout
            String r3 = login("guard", "pw-123456");
            String at3 = field(r3, "access_token"), rt3 = field(r3, "refresh_token");
            expect(post("/auth/logout", "{\"refresh_token\":\"" + rt3 + "\"}").statusCode() == 204, "logout");
            expect(get("/me", at3).statusCode() == 401, "access after logout");
            expect(post("/auth/refresh", "{\"refresh_token\":\"" + rt3 + "\"}").statusCode() == 401, "refresh after logout");
            expect(post("/auth/logout", "{\"refresh_token\":\"unknown\"}").statusCode() == 204, "logout idempotent");
            // refresh expiry
            String rt4 = field(login("guard", "pw-123456"), "refresh_token");
            now.addAndGet(IdentityServer.REFRESH_TTL_MS);
            expect(post("/auth/refresh", "{\"refresh_token\":\"" + rt4 + "\"}").statusCode() == 401, "refresh expired");
            // brute force lock
            for (int i = 0; i < IdentityServer.MAX_FAILURES; i++)
                expect(post("/auth/login", "{\"login\":\"student\",\"password\":\"wrong\"}").statusCode() == 401, "wrong pw " + i);
            HttpResponse<String> locked = post("/auth/login", "{\"login\":\"student\",\"password\":\"pw-123456\"}");
            expect(locked.statusCode() == 429 && locked.headers().firstValue("Retry-After").isPresent(), "locked");
            now.addAndGet(IdentityServer.LOCK_MS);
            expect(post("/auth/login", "{\"login\":\"student\",\"password\":\"pw-123456\"}").statusCode() == 200, "unlocked");
            expect(post("/auth/login", "{\"login\":\"nobody\",\"password\":\"x\"}").statusCode() == 401, "unknown user 401");
            // input validation
            expect(http.send(HttpRequest.newBuilder(URI.create(base + "/auth/login")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 400, "content-type");
            expect(post("/auth/login", "{\"login\":\"a\",\"login\":\"b\",\"password\":\"x\"}").statusCode() == 400, "duplicate keys");
            expect(post("/auth/login", "{\"pad\":\"" + "a".repeat(5000) + "\"}").statusCode() == 400, "body limit");
            expect(get("/auth/login", null).statusCode() == 404, "method");
            expect(get("/me", at2).headers().firstValue("Cache-Control").orElse("").equals("no-store"), "no-store");
            // секреты не пишутся в журнал
            expect(s.auditLog().stream().noneMatch(e -> e.contains("pw-123456") || e.contains(rt) || e.contains(at)), "no secrets in audit");
            System.out.println("Identity backend: OK (" + checks + " checks)");
        } finally { s.stop(); }
    }
}
