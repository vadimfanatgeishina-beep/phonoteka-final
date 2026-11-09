package com.phonoteka.identity

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

class IdentityClientException(val status: Int) : IOException("Identity request failed ($status)")

/** IO-thread client for one pinned origin. Credentials/tokens stay in memory; every operation reaches the server. */
class PhoneIdentityClient(private val endpoint: IdentityTlsEndpoint) : AutoCloseable {
    private val http = endpoint.client()
    private var access: String? = null
    private var refresh: String? = null
    private var closed = false

    @Synchronized fun login(login: String, password: String, deviceLabel: String): IdentityUser {
        check(!closed)
        access = null; refresh = null
        try {
            takeTokens(call("POST", "/auth/login", JSONObject().put("login", login).put("password", password).put("deviceLabel", deviceLabel)))
            return currentUser()
        } catch (failure: Exception) { access = null; refresh = null; throw failure }
    }

    @Synchronized fun currentUser(): IdentityUser = user(authenticated("GET", "/me"))
    @Synchronized fun sessions(): List<IdentitySessionInfo> {
        val rows = authenticated("GET", "/sessions").getJSONArray("sessions")
        if (rows.length() > PhoneIdentityAuthority.MAX_SESSIONS) throw IdentityClientException(502)
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            IdentitySessionInfo(it.getString("id"), it.getString("device_label"), it.getLong("created_at"),
                it.getLong("refresh_expires_at"), it.getBoolean("current"))
        } }
    }
    @Synchronized fun createUser(login: String, password: String, role: IdentityRole): IdentityUser =
        user(authenticated("POST", "/admin/users", JSONObject().put("login", login).put("password", password).put("role", role.name)))
    @Synchronized fun changeRole(userId: String, role: IdentityRole) {
        id(userId); authenticated("PATCH", "/admin/users/$userId/role", JSONObject().put("role", role.name))
    }
    @Synchronized fun revokeSession(sessionId: String) {
        id(sessionId)
        val isCurrent = sessions().any { it.id == sessionId && it.current }
        authenticated("DELETE", "/sessions/$sessionId")
        if (isCurrent) { access = null; refresh = null }
    }
    @Synchronized fun changePassword(previous: String, next: String) {
        authenticated("POST", "/me/password", JSONObject().put("old_password", previous).put("new_password", next))
        access = null; refresh = null
    }
    @Synchronized fun logout() {
        try { authenticated("POST", "/auth/logout") }
        finally { access = null; refresh = null }
    }

    private fun id(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{22}"))) }
    private fun user(json: JSONObject): IdentityUser {
        val role = IdentityRole.valueOf(json.getString("role"))
        val scopes = json.getString("scope").split(' ').filter { it.isNotEmpty() }.map { wire ->
            IdentityScope.entries.firstOrNull { it.wire == wire } ?: throw IdentityClientException(502)
        }.toSet()
        if (scopes != IdentityPolicy.scopes(role)) throw IdentityClientException(502)
        return IdentityUser(json.getString("id"), json.getString("login"), role, scopes)
    }
    private fun takeTokens(json: JSONObject) {
        val nextAccess = json.getString("access_token")
        val nextRefresh = json.getString("refresh_token")
        if (json.getString("token_type") != "Bearer" || !nextAccess.matches(Regex("[A-Za-z0-9_-]{43}")) ||
            !nextRefresh.matches(Regex("[A-Za-z0-9_-]{43}"))) throw IdentityClientException(502)
        access = nextAccess; refresh = nextRefresh
    }
    private fun authenticated(method: String, path: String, body: JSONObject? = null): JSONObject {
        val token = access ?: throw IdentityClientException(401)
        try { return call(method, path, body, token) }
        catch (failure: IdentityClientException) {
            if (failure.status != 401) throw failure
            val previous = refresh ?: throw failure
            try { takeTokens(call("POST", "/auth/refresh", JSONObject().put("refresh_token", previous))) }
            catch (refreshFailure: Exception) {
                // A response may have been lost after rotation. Never replay the previous refresh automatically.
                access = null; refresh = null; throw refreshFailure
            }
            return call(method, path, body, access!!)
        }
    }
    private fun call(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        check(!closed)
        val payload = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
            ?: if (method == "POST" || method == "PATCH") byteArrayOf().toRequestBody() else null
        val builder = Request.Builder().url(endpoint.baseUrl + path).method(method, payload)
        token?.let { builder.header("Authorization", "Bearer $it") }
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IdentityClientException(response.code)
            if (response.code == 204) return JSONObject()
            val output = ByteArrayOutputStream()
            response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    if (output.size() + count > 512 * 1024) throw IdentityClientException(502)
                    output.write(buffer, 0, count)
                }
            } ?: throw IdentityClientException(502)
            return try { JSONObject(output.toString(Charsets.UTF_8.name())) }
            catch (_: org.json.JSONException) { throw IdentityClientException(502) }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; access = null; refresh = null
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
