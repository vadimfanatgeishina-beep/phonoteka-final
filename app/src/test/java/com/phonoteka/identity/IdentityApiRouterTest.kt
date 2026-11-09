package com.phonoteka.identity

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/** Contract tests use the production authority (PBKDF2, tokens, persistence), not fake auth. */
class IdentityApiRouterTest {
    private class MemoryStore : IdentitySnapshotStore {
        var snapshot: ByteArray? = null
        var failSave = false
        override fun load(): ByteArray? = snapshot?.copyOf()
        override fun save(snapshot: ByteArray) {
            if (failSave) throw IllegalStateException("Sensitive storage failure details")
            this.snapshot = snapshot.copyOf()
        }
    }

    private inner class Fixture {
        val store = MemoryStore()
        var now = 1_700_000_000_000L
        val clock = IdentityClock { now }
        val entropy = IdentityEntropy { SecureRandom().nextBytes(it) }
        val authority = PhoneIdentityAuthority(store, clock, entropy)
        val router = IdentityApiRouter(authority, clock)
        init { authority.bootstrapOwner("owner", "valid-owner-password") }
        fun request(method: String, path: String, json: String? = null, token: String? = null,
                    headers: Map<String, String> = emptyMap()): IdentityApiResponse {
            val h = linkedMapOf<String, String>()
            if (json != null) h["Content-Type"] = "application/json"
            if (token != null) h["Authorization"] = "Bearer $token"
            h.putAll(headers)
            return router.handle(method, path, h, json?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0))
        }
        fun login(login: String = "owner", password: String = "valid-owner-password", label: String = "host phone"): IdentityApiResponse =
            request("POST", "/auth/login", """{"login":"$login","password":"$password","deviceLabel":"$label"}""")
        fun adminAccess(): String = field(login(), "access_token")
        fun newEmployee(admin: String, login: String = "worker"): String {
            val r = request("POST", "/admin/users", """{"login":"$login","password":"password-for-$login","role":"EMPLOYEE"}""", admin)
            assertEquals(201, r.status)
            return field(r, "id")
        }
    }

    private fun body(r: IdentityApiResponse) = r.body.toString(StandardCharsets.UTF_8)
    private fun field(r: IdentityApiResponse, key: String): String =
        Regex("\\\"${Regex.escape(key)}\\\":\\\"([^\\\"]+)\\\"").find(body(r))?.groupValues?.get(1)
            ?: throw AssertionError("Missing field '$key' in response status ${r.status}")
    private fun assertStatus(expected: Int, actual: IdentityApiResponse) {
        assertEquals(expected, actual.status)
        assertEquals("no-store", actual.headers["Cache-Control"])
        assertFalse(actual.headers.containsKey("Access-Control-Allow-Origin"))
        assertFalse(actual.headers.containsKey("Set-Cookie"))
    }

    @Test fun loginReturnsServerRoleScopeAndTokenContract() {
        val f = Fixture()
        val r = f.login()
        assertStatus(200, r)
        assertEquals("Bearer", field(r, "token_type"))
        assertEquals("ADMIN", field(r, "role"))
        assertTrue(field(r, "scope").contains("invite:create"))
        assertTrue(field(r, "access_token").length == 43)
        assertTrue(field(r, "refresh_token").length == 43)
        assertTrue(body(r).contains("\"expires_in\":300"))
        assertEquals("application/json; charset=utf-8", r.headers["Content-Type"])
        val me = f.request("GET", "/me", token = field(r, "access_token"))
        assertStatus(200, me)
        assertEquals("owner", field(me, "login"))
        assertFalse(body(me).contains(field(r, "refresh_token")))
        assertFalse(body(me).contains(field(r, "access_token")))
    }

    @Test fun wrongPasswordAndUnknownLoginHaveIdenticalFailureAndNoEcho() {
        val f = Fixture()
        val bad = f.login(password = "secret-not-the-password")
        val unknown = f.login(login = "missing", password = "secret-not-the-password")
        assertStatus(401, bad); assertStatus(401, unknown)
        assertEquals("invalid_credentials", field(bad, "error"))
        assertEquals(body(bad), body(unknown))
        assertEquals("Bearer realm=\"phonoteka\"", bad.headers["WWW-Authenticate"])
        assertFalse(body(bad).contains("secret-not-the-password"))
    }

    @Test fun missingMalformedAndExpiredBearerAreDenied() {
        val f = Fixture()
        assertStatus(401, f.request("GET", "/me"))
        assertStatus(401, f.request("GET", "/me", headers = mapOf("Authorization" to "Bearer invalid")))
        val access = f.adminAccess()
        f.now += PhoneIdentityAuthority.ACCESS_TTL_MS
        val r = f.request("GET", "/me", token = access)
        assertStatus(401, r)
        assertEquals("unauthorized", field(r, "error"))
        assertTrue(r.headers.containsKey("WWW-Authenticate"))
    }

    @Test fun employeeCannotForgeAdminOrCreateUsers() {
        val f = Fixture(); val admin = f.adminAccess()
        f.newEmployee(admin)
        val worker = f.login("worker", "password-for-worker")
        assertStatus(200, worker)
        assertEquals("EMPLOYEE", field(worker, "role"))
        assertFalse(field(worker, "scope").contains("invite:create"))
        val forbidden = f.request("POST", "/admin/users", """{"login":"newadmin","password":"valid-password-here","role":"ADMIN"}""", field(worker, "access_token"))
        assertStatus(403, forbidden)
        val forged = f.request("POST", "/auth/login", """{"login":"worker","password":"password-for-worker","deviceLabel":"x","role":"ADMIN"}""")
        assertStatus(400, forged)
        assertEquals("invalid_json", field(forged, "error"))
    }

    @Test fun sessionsAreScopedToCallerOnly() {
        val f = Fixture(); val admin = f.adminAccess()
        f.newEmployee(admin)
        val worker = field(f.login("worker", "password-for-worker", "employee phone"), "access_token")
        val own = f.request("GET", "/sessions", token = worker)
        assertStatus(200, own)
        assertTrue(body(own).contains("employee phone"))
        assertFalse(body(own).contains("host phone"))
        assertFalse(body(own).contains(admin))
        assertEquals(1, Regex("\"device_label\"").findAll(body(own)).count())
        val adminSessions = f.request("GET", "/sessions", token = admin)
        assertTrue(body(adminSessions).contains("host phone"))
        assertFalse(body(adminSessions).contains("employee phone"))
    }

    @Test fun foreignSessionCannotBeRevoked() {
        val f = Fixture(); val admin = f.adminAccess(); f.newEmployee(admin)
        val worker = field(f.login("worker", "password-for-worker"), "access_token")
        val workerSession = field(f.request("GET", "/sessions", token = worker), "id")
        val attempted = f.request("DELETE", "/sessions/$workerSession", token = admin)
        assertStatus(404, attempted)
        assertStatus(200, f.request("GET", "/me", token = worker))
        assertStatus(204, f.request("DELETE", "/sessions/$workerSession", token = worker))
        assertStatus(401, f.request("GET", "/me", token = worker))
    }

    @Test fun refreshRotationReplayRevokesNewAccess() {
        val f = Fixture()
        val initial = f.login()
        val newTokens = f.request("POST", "/auth/refresh", """{"refresh_token":"${field(initial, "refresh_token")}"}""")
        assertStatus(200, newTokens)
        assertStatus(401, f.request("GET", "/me", token = field(initial, "access_token")))
        val replay = f.request("POST", "/auth/refresh", """{"refresh_token":"${field(initial, "refresh_token")}"}""")
        assertStatus(401, replay)
        assertStatus(401, f.request("GET", "/me", token = field(newTokens, "access_token")))
        assertStatus(401, f.request("POST", "/auth/refresh", """{"refresh_token":"${field(newTokens, "refresh_token")}"}"""))
    }

    @Test fun expiresInRespectsEndOfRefreshLifetime() {
        val f = Fixture(); val initial = f.login()
        f.now += PhoneIdentityAuthority.REFRESH_TTL_MS - 90_000L
        val refreshed = f.request("POST", "/auth/refresh", """{"refresh_token":"${field(initial, "refresh_token")}"}""")
        assertStatus(200, refreshed)
        assertTrue(body(refreshed).contains("\"expires_in\":90"))
    }

    @Test fun wrongMethodHasAllowAndBootstrapRouteIsAbsent() {
        val f = Fixture()
        val wrong = f.request("GET", "/auth/login")
        assertStatus(405, wrong)
        assertEquals("POST", wrong.headers["Allow"])
        val patchWrong = f.request("GET", "/admin/users/abc/role")
        assertStatus(405, patchWrong)
        assertEquals("PATCH", patchWrong.headers["Allow"])
        assertStatus(404, f.request("POST", "/auth/bootstrapOwner", "{}"))
    }

    @Test fun duplicateJsonKeysIncludingEscapedEquivalentsAreRejected() {
        val f = Fixture()
        val duplicate = f.request("POST", "/auth/login", """{"login":"owner","login":"owner","password":"valid-owner-password"}""")
        assertStatus(400, duplicate)
        val escapedDuplicate = f.request("POST", "/auth/login", """{"login":"owner","\u006cogin":"owner","password":"valid-owner-password"}""")
        assertStatus(400, escapedDuplicate)
    }

    @Test fun strictJsonTypesStructureAndTrailingData() {
        val f = Fixture()
        val bad = listOf(
            """["owner","password"]""",
            """{"login":123,"password":"valid-owner-password"}""",
            """{"login":null,"password":"valid-owner-password"}""",
            """{"login":"owner","password":"valid-owner-password"}true""",
            """{"login":"owner","password":"valid-owner-password",}""",
            """{"login":"owner","password":"valid-owner-password","deviceLabel":{}}""",
            """{"login":"owner","password":"valid-owner-password","unknown":"x"}""",
            """{"login":"owner","password":"valid-owner-password","deviceLabel":"\uD800"}""",
            """{"login":"owner","password":"valid-owner-password","deviceLabel":"\x41"}"""
        )
        for (input in bad) assertStatus(400, f.request("POST", "/auth/login", input))
        val invalidUtf8 = f.router.handle("POST", "/auth/login", mapOf("Content-Type" to "application/json"), byteArrayOf(0xc3.toByte(), 0x28))
        assertStatus(400, invalidUtf8)
    }

    @Test fun payloadLimitAndHeaderAndContentTypeLimits() {
        val f = Fixture()
        val large = ByteArray(IdentityApiRouter.MAX_BODY_BYTES + 1) { 'a'.code.toByte() }
        val r = f.router.handle("POST", "/auth/login", mapOf("Content-Type" to "application/json"), large)
        assertStatus(413, r)
        assertStatus(415, f.request("POST", "/auth/login", "{}", headers = mapOf("Content-Type" to "text/plain")))
        assertStatus(415, f.request("POST", "/auth/login", "{}", headers = mapOf("Content-Type" to "application/json; charset=iso-8859-1")))
        assertStatus(400, f.request("POST", "/auth/login", "{}", headers = mapOf("content-type" to "application/json")))
        assertStatus(400, f.request("GET", "/me", headers = mapOf("X-Test" to "a".repeat(2049))))
        assertStatus(400, f.request("GET", "/" + "x".repeat(256)))
        assertStatus(400, f.request("GET", "/me?query=x"))
        assertStatus(400, f.request("GET", "/me", json = "{}", token = f.adminAccess()))
    }

    @Test fun storageFailureReturns503AndNoActiveNewTokens() {
        val f = Fixture(); val admin = f.adminAccess()
        val before = f.store.snapshot!!.copyOf()
        f.store.failSave = true
        val login = f.login()
        assertStatus(503, login)
        assertEquals("storage_unavailable", field(login, "error"))
        assertFalse(body(login).contains("Sensitive storage failure details"))
        assertFalse(body(login).contains("access_token"))
        assertTrue(before.contentEquals(f.store.snapshot))
        val failedAdminWrite = f.request("POST", "/admin/users", """{"login":"new","password":"password-for-new","role":"ADMIN"}""", admin)
        assertStatus(503, failedAdminWrite)
        assertTrue(before.contentEquals(f.store.snapshot))
        assertStatus(200, f.request("GET", "/me", token = admin))
        f.store.failSave = false
        assertStatus(401, f.login("new", "password-for-new"))
    }

    @Test fun failedRefreshSaveKeepsOriginalRefreshUsable() {
        val f = Fixture(); val initial = f.login()
        val oldRefresh = field(initial, "refresh_token")
        val oldAccess = field(initial, "access_token")
        val snapshot = f.store.snapshot!!.copyOf()
        f.store.failSave = true
        val failed = f.request("POST", "/auth/refresh", """{"refresh_token":"$oldRefresh"}""")
        assertStatus(503, failed)
        assertFalse(body(failed).contains("access_token"))
        assertTrue(snapshot.contentEquals(f.store.snapshot))
        assertStatus(200, f.request("GET", "/me", token = oldAccess))
        f.store.failSave = false
        assertStatus(200, f.request("POST", "/auth/refresh", """{"refresh_token":"$oldRefresh"}"""))
    }

    @Test fun adminCanCreateAndChangeRolesWithImmediateRevocation() {
        val f = Fixture(); val admin = f.adminAccess()
        val id = f.newEmployee(admin)
        val oldWorker = field(f.login("worker", "password-for-worker"), "access_token")
        val change = f.request("PATCH", "/admin/users/$id/role", """{"role":"SECURITY"}""", admin)
        assertStatus(204, change)
        assertEquals(0, change.body.size)
        assertStatus(401, f.request("GET", "/me", token = oldWorker))
        val current = f.login("worker", "password-for-worker")
        assertEquals("SECURITY", field(current, "role"))
        assertTrue(field(current, "scope").contains("scan:execute"))
        assertFalse(field(current, "scope").contains("library:write"))
    }

    @Test fun logoutRejectsAnyBodyWithoutRevokingSession() {
        val f = Fixture(); val first = f.login()
        val access = field(first, "access_token")
        assertStatus(400, f.request("POST", "/auth/logout", token = access, json = "{}"))
        assertStatus(200, f.request("GET", "/me", token = access))
    }

    @Test fun logoutWithEmptyBodyAndPasswordChange() {
        val f = Fixture(); val first = f.login()
        val access = field(first, "access_token")
        val bad = f.request("POST", "/me/password", """{"old_password":"wrong-old-pass","new_password":"replacement-password"}""", access)
        assertStatus(401, bad)
        assertStatus(204, f.request("POST", "/me/password", """{"old_password":"valid-owner-password","new_password":"replacement-password"}""", access))
        assertStatus(401, f.request("GET", "/me", token = access))
        assertStatus(401, f.login())
        val replacement = f.login(password = "replacement-password")
        assertStatus(200, replacement)
        val refreshed = field(replacement, "refresh_token")
        assertStatus(204, f.request("POST", "/auth/logout", token = field(replacement, "access_token")))
        assertStatus(401, f.request("POST", "/auth/refresh", """{"refresh_token":"$refreshed"}"""))
    }

    @Test fun loginLockReports429WithRetryAfter() {
        val f = Fixture()
        repeat(5) { assertStatus(401, f.login(password = "wrong-password-here")) }
        val locked = f.login()
        assertStatus(429, locked)
        assertEquals("900", locked.headers["Retry-After"])
        assertEquals("login_locked", field(locked, "error"))
    }
}
