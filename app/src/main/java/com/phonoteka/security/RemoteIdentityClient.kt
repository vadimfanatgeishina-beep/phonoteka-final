package com.phonoteka.security

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * ЛР13 (доп.): клиент отдельного identity-backend (tools/backend/IdentityServer.java).
 * Access хранится только в памяти; refresh отдаётся вызывающему для сохранения в KeyStore-хранилище.
 * Роль/scopes берутся ТОЛЬКО из ответа сервера.
 */
class RemoteIdentityClient(private val baseUrl: String, private val http: OkHttpClient) {
    data class Tokens(val access: String, val refresh: String, val expiresInSec: Long, val role: String, val scopes: Set<DemoScope>)
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data object InvalidCredentials : Result<Nothing>
        data class Locked(val retryAfterSec: Long) : Result<Nothing>
        data object SessionRevoked : Result<Nothing>
        data object Forbidden : Result<Nothing>
        data class Failure(val code: Int) : Result<Nothing>
        data object Network : Result<Nothing>
    }

    init { require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://127.0.0.1:")) { "identity backend must be https or loopback" } }

    @Volatile private var access: String? = null

    fun login(login: String, password: String): Result<Tokens> =
        postTokens("auth/login", JSONObject().put("login", login).put("password", password))

    fun refresh(refreshToken: String): Result<Tokens> {
        val r = postTokens("auth/refresh", JSONObject().put("refresh_token", refreshToken))
        if (r is Result.InvalidCredentials) { access = null; return Result.SessionRevoked }
        return r
    }

    fun logout(refreshToken: String): Result<Unit> {
        access = null
        return call(post("auth/logout", JSONObject().put("refresh_token", refreshToken))) { Result.Ok(Unit) }
    }

    /** GET /me, /pass, /audit с текущим access. */
    fun get(path: String): Result<JSONObject> {
        val token = access ?: return Result.SessionRevoked
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/" + path).header("Authorization", "Bearer $token").get().build()
        return call(req) { body -> Result.Ok(JSONObject(body)) }
    }

    private fun postTokens(path: String, json: JSONObject): Result<Tokens> = call(post(path, json)) { body ->
        val o = JSONObject(body)
        val t = Tokens(o.getString("access_token"), o.getString("refresh_token"), o.getLong("expires_in"),
            o.getString("role"), DemoScope.parseClaim(o.getString("scope")))
        access = t.access
        Result.Ok(t)
    }

    private fun post(path: String, json: JSONObject) = Request.Builder().url(baseUrl.trimEnd('/') + "/" + path)
        .post(json.toString().toRequestBody("application/json".toMediaType())).build()

    private fun <T> call(req: Request, ok: (String) -> Result<T>): Result<T> = try {
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> ok(resp.body?.string().orEmpty())
                204 -> ok("{}")
                401 -> Result.InvalidCredentials
                403 -> Result.Forbidden
                429 -> Result.Locked(resp.header("Retry-After")?.toLongOrNull() ?: 60)
                else -> Result.Failure(resp.code)
            }
        }
    } catch (_: IOException) { Result.Network }
    catch (_: org.json.JSONException) { Result.Failure(-1) }
}
