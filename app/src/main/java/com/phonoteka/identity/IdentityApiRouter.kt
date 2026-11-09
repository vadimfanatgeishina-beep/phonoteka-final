package com.phonoteka.identity

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Transport-neutral HTTP result. No socket, TLS, Android, or servlet dependency. */
data class IdentityApiResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/**
 * Strict, pure-JVM HTTP-to-PhoneIdentityAuthority adapter. The TLS layer must supply a
 * single-valued header map and raw request bytes, enforce framing, and send this result.
 *
 * The clock must represent the same time source as the authority's injected clock, so
 * expires_in remains accurate, especially near the end of a refresh family lifetime.
 */
class IdentityApiRouter(
    private val authority: PhoneIdentityAuthority,
    private val clock: IdentityClock = IdentityClock(System::currentTimeMillis)
) {
    companion object {
        const val MAX_BODY_BYTES = 8 * 1024
        const val MAX_PATH_CHARS = 256
        const val MAX_HEADERS = 32
        const val MAX_HEADER_VALUE_CHARS = 2048
        const val MAX_HEADER_TOTAL_CHARS = 8192
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val CHALLENGE = "Bearer realm=\"phonoteka\""
    }

    /** `path` is the literal raw HTTP path (not URL-decoded); no query/fragment. */
    fun handle(method: String, path: String, headers: Map<String, String>, body: ByteArray): IdentityApiResponse {
        return try {
            if (body.size > MAX_BODY_BYTES) return error(413, "payload_too_large")
            if (!method.matches(Regex("[A-Z]{1,16}"))) return error(400, "invalid_request")
            if (path.length !in 1..MAX_PATH_CHARS || !path.matches(Regex("/[A-Za-z0-9_/-]*")) ||
                path.contains("//") || path.contains("/./") || path.contains("/../") || path.endsWith("/.") || path.endsWith("/.."))
                return error(400, "invalid_request")
            val h = parseHeaders(headers) ?: return error(400, "invalid_request")
            val match = matchRoute(path) ?: return error(404, "not_found")
            if (method != match.method) return error(405, "method_not_allowed", mapOf("Allow" to match.method))
            // Authenticate first for every protected endpoint, before processing caller input.
            val authenticatedAccess = when (match.kind) {
                Route.LOGIN, Route.REFRESH -> null
                else -> bearer(h).also { authority.currentUser(it) }
            }
            if (match.kind == Route.LOGOUT) {
                // Logout accepts no JSON/body and does not require Content-Type.
                if (body.isNotEmpty()) return error(400, "invalid_request")
            } else if (match.method == "POST" || match.method == "PATCH") {
                if (!validContentType(h["content-type"])) return error(415, "unsupported_media_type")
            } else if (body.isNotEmpty()) return error(400, "invalid_request")

            when (match.kind) {
                Route.LOGIN -> {
                    val fields = parseBody(body, setOf("login", "password"), setOf("deviceLabel"))
                    val tokens = authority.login(fields.getValue("login"), fields.getValue("password"), fields["deviceLabel"] ?: "")
                    tokenResponse(tokens)
                }
                Route.REFRESH -> {
                    val fields = parseBody(body, setOf("refresh_token"))
                    tokenResponse(authority.refresh(fields.getValue("refresh_token")))
                }
                Route.LOGOUT -> {
                    authority.logout(authenticatedAccess!!)
                    empty(204)
                }
                Route.ME -> json(200, userJson(authority.currentUser(authenticatedAccess!!)))
                Route.SESSIONS -> {
                    val sessions = authority.listSessions(authenticatedAccess!!)
                    json(200, "{\"sessions\":[" + sessions.joinToString(",") { sessionJson(it) } + "]}")
                }
                Route.REVOKE_SESSION -> {
                    authority.revokeOwnSession(authenticatedAccess!!, match.id)
                    empty(204)
                }
                Route.CREATE_USER -> {
                    val access = authenticatedAccess!!
                    // Check authentication and server-side authorization even for invalid input.
                    authority.authorize(access, IdentityScope.INVITE_CREATE)
                    val fields = parseBody(body, setOf("login", "password", "role"))
                    val role = parseRole(fields.getValue("role"))
                    val user = authority.createUser(access, fields.getValue("login"), fields.getValue("password"), role)
                    json(201, userJson(user))
                }
                Route.CHANGE_ROLE -> {
                    val access = authenticatedAccess!!
                    authority.authorize(access, IdentityScope.INVITE_CREATE)
                    val fields = parseBody(body, setOf("role"))
                    authority.changeRole(access, match.id, parseRole(fields.getValue("role")))
                    empty(204)
                }
                Route.CHANGE_PASSWORD -> {
                    val access = authenticatedAccess!!
                    authority.currentUser(access)
                    val fields = parseBody(body, setOf("old_password", "new_password"))
                    authority.changePassword(access, fields.getValue("old_password"), fields.getValue("new_password"))
                    empty(204)
                }
            }
        } catch (_: MalformedJson) {
            error(400, "invalid_json")
        } catch (e: IdentityException) {
            when (e.reason) {
                IdentityException.Reason.INVALID_CREDENTIALS -> error(401, "invalid_credentials", challenge())
                IdentityException.Reason.LOCKED -> error(429, "login_locked", mapOf("Retry-After" to "900"))
                IdentityException.Reason.UNAUTHORIZED -> error(401, "unauthorized", challenge())
                IdentityException.Reason.FORBIDDEN -> error(403, "forbidden")
                IdentityException.Reason.NOT_FOUND -> error(404, "not_found")
                IdentityException.Reason.CONFLICT -> error(409, "conflict")
                IdentityException.Reason.LIMIT -> error(503, "capacity_exceeded", mapOf("Retry-After" to "60"))
                IdentityException.Reason.INVALID_INPUT -> error(400, "invalid_input")
                IdentityException.Reason.INVALID_SNAPSHOT -> error(503, "storage_unavailable")
            }
        } catch (_: Exception) {
            // Includes persistence failures. Never convert failed commits to success or echo exception messages.
            error(503, "storage_unavailable")
        }
    }

    private enum class Route { LOGIN, REFRESH, LOGOUT, ME, SESSIONS, REVOKE_SESSION, CREATE_USER, CHANGE_ROLE, CHANGE_PASSWORD }
    private data class Match(val kind: Route, val method: String, val id: String = "")

    private fun matchRoute(path: String): Match? = when (path) {
        "/auth/login" -> Match(Route.LOGIN, "POST")
        "/auth/refresh" -> Match(Route.REFRESH, "POST")
        "/auth/logout" -> Match(Route.LOGOUT, "POST")
        "/me" -> Match(Route.ME, "GET")
        "/sessions" -> Match(Route.SESSIONS, "GET")
        "/admin/users" -> Match(Route.CREATE_USER, "POST")
        "/me/password" -> Match(Route.CHANGE_PASSWORD, "POST")
        else -> when {
            path.startsWith("/sessions/") && path.removePrefix("/sessions/").isNotEmpty() &&
                !path.removePrefix("/sessions/").contains('/') ->
                Match(Route.REVOKE_SESSION, "DELETE", path.removePrefix("/sessions/"))
            path.startsWith("/admin/users/") && path.endsWith("/role") -> {
                val id = path.removePrefix("/admin/users/").removeSuffix("/role")
                if (id.isNotEmpty() && !id.contains('/')) Match(Route.CHANGE_ROLE, "PATCH", id) else null
            }
            else -> null
        }
    }

    private fun parseHeaders(headers: Map<String, String>): Map<String, String>? {
        if (headers.size > MAX_HEADERS) return null
        val normalized = HashMap<String, String>(headers.size)
        var total = 0
        for ((name, value) in headers) {
            if (name.length !in 1..64 || !name.matches(Regex("[!#\\$%&'*+.^_`|~0-9A-Za-z-]+")) ||
                value.length > MAX_HEADER_VALUE_CHARS || value.any { (it.code < 0x20 && it != '\t') || it.code > 0x7e }) return null
            total += name.length + value.length
            if (total > MAX_HEADER_TOTAL_CHARS) return null
            if (normalized.putIfAbsent(name.lowercase(Locale.ROOT), value) != null) return null
        }
        return normalized
    }

    private fun validContentType(value: String?): Boolean {
        if (value == null) return false
        return value.matches(Regex("(?i)application/json(?:[ \\t]*;[ \\t]*charset[ \\t]*=[ \\t]*utf-8)?"))
    }

    private fun bearer(headers: Map<String, String>): String {
        val value = headers["authorization"] ?: throw IdentityException(IdentityException.Reason.UNAUTHORIZED)
        if (!value.matches(Regex("(?i)Bearer [A-Za-z0-9_-]{43}")))
            throw IdentityException(IdentityException.Reason.UNAUTHORIZED)
        return value.substring(7)
    }

    private fun parseBody(bytes: ByteArray, required: Set<String>, optional: Set<String> = emptySet()): Map<String, String> =
        StrictStringJson.parse(bytes, required, optional)

    private fun parseRole(raw: String): IdentityRole = IdentityRole.entries.firstOrNull { it.name == raw }
        ?: throw IdentityException(IdentityException.Reason.INVALID_INPUT)

    private fun scope(user: IdentityUser): String = IdentityScope.entries.filter { it in user.scopes }.joinToString(" ") { it.wire }

    private fun userJson(user: IdentityUser): String = "{\"id\":" + q(user.id) +
        ",\"login\":" + q(user.login) + ",\"role\":" + q(user.role.name) +
        ",\"scope\":" + q(scope(user)) + "}"

    private fun sessionJson(s: IdentitySessionInfo): String = "{\"id\":" + q(s.id) +
        ",\"device_label\":" + q(s.deviceLabel) +
        ",\"created_at\":" + s.createdAt + ",\"refresh_expires_at\":" + s.refreshExpiresAt +
        ",\"current\":" + s.current + "}"

    private fun tokenResponse(tokens: IdentityTokens): IdentityApiResponse {
        // Must be looked up from the authoritative session, never from request-supplied role/scope.
        val user = authority.currentUser(tokens.access)
        val delta = (tokens.accessExpiresAt - clock.nowMillis()).coerceAtLeast(0L)
        val seconds = (delta / 1000L + if (delta % 1000L == 0L) 0L else 1L)
            .coerceAtMost(PhoneIdentityAuthority.ACCESS_TTL_MS / 1000L)
        return json(200, "{\"access_token\":" + q(tokens.access) +
            ",\"refresh_token\":" + q(tokens.refresh) +
            ",\"token_type\":\"Bearer\",\"expires_in\":" + seconds +
            ",\"role\":" + q(user.role.name) + ",\"scope\":" + q(scope(user)) + "}")
    }

    private fun json(status: Int, payload: String): IdentityApiResponse = IdentityApiResponse(
        status, mapOf("Cache-Control" to "no-store", "Content-Type" to JSON_CONTENT_TYPE),
        payload.toByteArray(StandardCharsets.UTF_8)
    )

    private fun empty(status: Int): IdentityApiResponse = IdentityApiResponse(
        status, mapOf("Cache-Control" to "no-store"), ByteArray(0)
    )

    private fun error(status: Int, code: String, extra: Map<String, String> = emptyMap()): IdentityApiResponse {
        val result = json(status, "{\"error\":" + q(code) + "}")
        return result.copy(headers = result.headers + extra)
    }

    private fun challenge(): Map<String, String> = mapOf("WWW-Authenticate" to CHALLENGE)
}

/** Strict UTF-8 JSON string-object decoder for endpoint schemas (every accepted field is a string). */
private object StrictStringJson {
    fun parse(body: ByteArray, required: Set<String>, optional: Set<String>): Map<String, String> {
        val source = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) { throw MalformedJson() }
        val parser = Parser(source, required + optional)
        val result = parser.readObject()
        if (!result.keys.containsAll(required)) throw MalformedJson()
        return result
    }

    private class Parser(private val src: String, private val allowed: Set<String>) {
        private var pos = 0
        private fun white() { while (pos < src.length && (src[pos] == ' ' || src[pos] == '\n' || src[pos] == '\r' || src[pos] == '\t')) pos++ }
        private fun take(ch: Char): Boolean { white(); if (pos < src.length && src[pos] == ch) { pos++; return true }; return false }
        fun readObject(): Map<String, String> {
            if (!take('{')) throw MalformedJson()
            val fields = LinkedHashMap<String, String>()
            if (!take('}')) {
                do {
                    white()
                    val key = string()
                    if (key !in allowed || fields.containsKey(key) || !take(':')) throw MalformedJson()
                    white()
                    val value = string() // strict string type, including forbidding null/number/boolean/array/object
                    fields[key] = value
                    if (take('}')) break
                    if (!take(',')) throw MalformedJson()
                } while (true)
            }
            white()
            if (pos != src.length) throw MalformedJson()
            return fields
        }

        private fun string(): String {
            if (pos >= src.length || src[pos++] != '"') throw MalformedJson()
            val b = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') return b.toString()
                if (c.code < 0x20) throw MalformedJson()
                if (c == '\\') {
                    if (pos >= src.length) throw MalformedJson()
                    when (src[pos++]) {
                        '"' -> b.append('"')
                        '\\' -> b.append('\\')
                        '/' -> b.append('/')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000c')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> appendCodeUnit(b, hex4())
                        else -> throw MalformedJson()
                    }
                } else if (Character.isHighSurrogate(c)) {
                    if (pos == src.length || !Character.isLowSurrogate(src[pos])) throw MalformedJson()
                    b.append(c).append(src[pos++])
                } else if (Character.isLowSurrogate(c)) throw MalformedJson()
                else b.append(c)
            }
            throw MalformedJson()
        }
        private fun appendCodeUnit(b: StringBuilder, unit: Char) {
            if (Character.isLowSurrogate(unit)) throw MalformedJson()
            if (Character.isHighSurrogate(unit)) {
                if (pos + 2 > src.length || src[pos++] != '\\' || src[pos++] != 'u') throw MalformedJson()
                val second = hex4()
                if (!Character.isLowSurrogate(second)) throw MalformedJson()
                b.append(unit).append(second)
            } else b.append(unit)
        }
        private fun hex4(): Char {
            if (pos + 4 > src.length) throw MalformedJson()
            var n = 0
            repeat(4) {
                val c = src[pos++]
                n = (n shl 4) or when (c) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> throw MalformedJson()
                }
            }
            return n.toChar()
        }
    }
}

private class MalformedJson : RuntimeException()

/** Escapes JSON response strings, including control characters and unpaired surrogates. */
private fun q(value: String): String = buildString(value.length + 2) {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20 || Character.isSurrogate(c)) {
                append("\\u")
                append(c.code.toString(16).padStart(4, '0'))
            } else append(c)
        }
    }
    append('"')
}
