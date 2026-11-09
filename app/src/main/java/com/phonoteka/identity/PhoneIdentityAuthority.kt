package com.phonoteka.identity

import java.io.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The adapter MUST atomically replace the encrypted snapshot; if save throws, prior bytes remain intact. */
interface IdentitySnapshotStore {
    fun load(): ByteArray?
    fun save(snapshot: ByteArray)
}

fun interface IdentityClock { fun nowMillis(): Long }
fun interface IdentityEntropy { fun fill(bytes: ByteArray) }

enum class IdentityRole { EMPLOYEE, SECURITY, ADMIN }
enum class IdentityScope(val wire: String) {
    LIBRARY_READ("library:read"), LIBRARY_WRITE("library:write"), PASS_VIEW("pass:view"),
    SCAN_TRACK("scan:execute"), AUDIT_READ("audit:read"), INVITE_CREATE("invite:create")
}

object IdentityPolicy {
    fun scopes(role: IdentityRole): Set<IdentityScope> = when (role) {
        IdentityRole.EMPLOYEE -> setOf(IdentityScope.LIBRARY_READ, IdentityScope.LIBRARY_WRITE, IdentityScope.PASS_VIEW)
        IdentityRole.SECURITY -> setOf(IdentityScope.LIBRARY_READ, IdentityScope.PASS_VIEW, IdentityScope.SCAN_TRACK)
        IdentityRole.ADMIN -> IdentityScope.entries.toSet()
    }
}

/** All exceptions are generic; neither credentials nor token bytes are included. */
class IdentityException(val reason: Reason) : RuntimeException(reason.name) {
    enum class Reason { INVALID_CREDENTIALS, LOCKED, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, LIMIT, INVALID_INPUT, INVALID_SNAPSHOT }
}

data class IdentityUser(val id: String, val login: String, val role: IdentityRole, val scopes: Set<IdentityScope>)
data class IdentitySessionInfo(val id: String, val deviceLabel: String, val createdAt: Long, val refreshExpiresAt: Long, val current: Boolean)

/** Secret strings are never printed by toString. */
class IdentityTokens(access: String, refresh: String, val accessExpiresAt: Long, val refreshExpiresAt: Long) {
    val access: String = access
    val refresh: String = refresh
    override fun toString(): String = "IdentityTokens(redacted)"
}

enum class IdentityEventType { BOOTSTRAP, LOGIN_SUCCESS, LOGIN_DENIED, LOGOUT, TOKEN_REFRESH, REFRESH_REPLAY, SESSION_REVOKED, USER_CREATED, ROLE_CHANGED, PASSWORD_CHANGED }
data class IdentityEvent(val type: IdentityEventType, val actorId: String?, val subjectId: String?, val timestamp: Long)

/** Serializable, synchronized, pure-JVM identity authority for use behind an external TLS server. */
class PhoneIdentityAuthority(
    private val store: IdentitySnapshotStore,
    private val clock: IdentityClock = IdentityClock(System::currentTimeMillis),
    private val entropy: IdentityEntropy = IdentityEntropy { SecureRandom().nextBytes(it) },
    private val audit: (IdentityEvent) -> Unit = {}
) {
    companion object {
        const val ACCESS_TTL_MS = 5 * 60_000L
        const val REFRESH_TTL_MS = 7 * 24 * 60 * 60_000L
        const val LOCK_MS = 15 * 60_000L
        const val MAX_USERS = 128
        const val MAX_SESSIONS = 512
        const val MAX_REPLAY_RECORDS = 4096
        const val MAX_LOGIN_GUARDS = 512
        private const val ITERATIONS = 210_000
        private const val MAGIC = 0x50484944
        private const val VERSION = 1
        private const val MAX_SNAPSHOT_BYTES = 2_000_000
        private const val MAX_LOGIN_LENGTH = 64
        private const val MAX_PASSWORD_LENGTH = 256
        private const val MAX_DEVICE_LENGTH = 100
    }

    private data class User(val id: String, val login: String, val role: IdentityRole, val salt: ByteArray, val hash: ByteArray)
    private data class Session(val id: String, val userId: String, val family: String, val device: String,
                               val created: Long, val accessHash: String, val accessExpiry: Long,
                               val refreshHash: String, val refreshExpiry: Long)
    private data class Replay(val family: String, val expiry: Long)
    private data class Guard(val failures: Int, val lockedUntil: Long, val lastAttempt: Long)
    private class State(
        val users: MutableList<User> = mutableListOf(),
        val sessions: MutableList<Session> = mutableListOf(),
        val replay: MutableMap<String, Replay> = linkedMapOf(),
        val guards: MutableMap<String, Guard> = linkedMapOf()
    )

    private var state: State
    init {
        val bytes = try { store.load() } catch (e: Exception) { throw IllegalStateException("Identity storage read failed", e) }
        state = if (bytes == null) State() else decode(bytes.copyOf())
    }

    @Synchronized fun bootstrapOwner(login: String, password: String): IdentityUser {
        val key = validLogin(login); val pass = validPassword(password)
        return change(IdentityEventType.BOOTSTRAP, null, null) { s ->
            if (s.users.isNotEmpty() || s.sessions.isNotEmpty() || s.replay.isNotEmpty() || s.guards.isNotEmpty()) fail(IdentityException.Reason.CONFLICT)
            val u = createUser(key, pass, IdentityRole.ADMIN)
            s.users.add(u)
            u.toPublic()
        }
    }

    /** No client-provided role or scope parameter exists on the login boundary. */
    @Synchronized fun login(login: String, password: String, deviceLabel: String = ""): IdentityTokens {
        val key = validLogin(login); val pass = validPassword(password); val device = validDevice(deviceLabel)
        val now = clock.nowMillis()
        val guard = state.guards[key]
        if (guard != null && now < guard.lockedUntil) fail(IdentityException.Reason.LOCKED)
        val u = state.users.firstOrNull { it.login == key }
        val salt = u?.salt ?: ByteArray(16) // constant-work unknown-user path
        val target = u?.hash ?: ByteArray(32)
        val candidate = pbkdf2(pass, salt)
        val ok = MessageDigest.isEqual(candidate, target) && u != null
        candidate.fill(0)
        if (!ok) {
            change(IdentityEventType.LOGIN_DENIED, null, null) { s ->
                val old = s.guards[key] ?: Guard(0, 0, now)
                if (s.guards.size >= MAX_LOGIN_GUARDS && key !in s.guards) fail(IdentityException.Reason.LIMIT)
                val count = (old.failures + 1).coerceAtMost(5)
                s.guards[key] = Guard(count, if (count >= 5) plus(now, LOCK_MS) else 0, now)
            }
            fail(IdentityException.Reason.INVALID_CREDENTIALS)
        }
        val authenticated = u ?: fail(IdentityException.Reason.INVALID_CREDENTIALS)
        return change(IdentityEventType.LOGIN_SUCCESS, authenticated.id, authenticated.id) { s ->
            s.guards.remove(key)
            makeSession(s, authenticated, device, now)
        }
    }

    @Synchronized fun currentUser(access: String): IdentityUser {
        val session = requireAccess(access)
        return state.users.firstOrNull { it.id == session.userId }?.toPublic() ?: fail(IdentityException.Reason.UNAUTHORIZED)
    }

    @Synchronized fun authorize(access: String, scope: IdentityScope): IdentityUser {
        val user = currentUser(access)
        if (scope !in user.scopes) fail(IdentityException.Reason.FORBIDDEN)
        return user
    }

    @Synchronized fun refresh(refresh: String): IdentityTokens {
        val hash = tokenHash(refresh)
        val now = clock.nowMillis()
        val tombstone = state.replay[hash]
        if (tombstone != null && now < tombstone.expiry) {
            change(IdentityEventType.REFRESH_REPLAY, null, null) { s -> revokeFamily(s, tombstone.family) }
            fail(IdentityException.Reason.UNAUTHORIZED)
        }
        val old = state.sessions.firstOrNull { constantHashEqual(it.refreshHash, hash) && now < it.refreshExpiry }
            ?: fail(IdentityException.Reason.UNAUTHORIZED)
        return change(IdentityEventType.TOKEN_REFRESH, old.userId, old.userId) { s ->
            if (s.replay.size >= MAX_REPLAY_RECORDS) fail(IdentityException.Reason.LIMIT)
            s.replay[old.refreshHash] = Replay(old.family, old.refreshExpiry)
            s.sessions.removeAll { it.id == old.id }
            val user = s.users.first { it.id == old.userId }
            makeSession(s, user, old.device, now, old.family, old.created, old.refreshExpiry)
        }
    }

    @Synchronized fun logout(access: String) {
        val s = requireAccess(access)
        change(IdentityEventType.LOGOUT, s.userId, s.userId) { st -> revokeFamily(st, s.family) }
    }

    @Synchronized fun listSessions(access: String): List<IdentitySessionInfo> {
        val session = requireAccess(access)
        return state.sessions.filter { it.userId == session.userId && clock.nowMillis() < it.refreshExpiry }
            .map { IdentitySessionInfo(it.id, it.device, it.created, it.refreshExpiry, it.id == session.id) }
    }

    @Synchronized fun revokeOwnSession(access: String, sessionId: String) {
        val actor = requireAccess(access)
        if (!validIdentifier(sessionId)) fail(IdentityException.Reason.INVALID_INPUT)
        val selected = state.sessions.firstOrNull { it.id == sessionId && it.userId == actor.userId }
            ?: fail(IdentityException.Reason.NOT_FOUND)
        change(IdentityEventType.SESSION_REVOKED, actor.userId, actor.userId) { revokeFamily(it, selected.family) }
    }

    @Synchronized fun createUser(adminAccess: String, login: String, password: String, role: IdentityRole): IdentityUser {
        val admin = requireAdmin(adminAccess); val key = validLogin(login); val pass = validPassword(password)
        return change(IdentityEventType.USER_CREATED, admin.userId, null) { s ->
            if (s.users.size >= MAX_USERS) fail(IdentityException.Reason.LIMIT)
            if (s.users.any { it.login == key }) fail(IdentityException.Reason.CONFLICT)
            val u = createUser(key, pass, role)
            s.users.add(u); u.toPublic()
        }
    }

    @Synchronized fun changeRole(adminAccess: String, userId: String, role: IdentityRole) {
        val admin = requireAdmin(adminAccess)
        if (!validIdentifier(userId)) fail(IdentityException.Reason.INVALID_INPUT)
        change(IdentityEventType.ROLE_CHANGED, admin.userId, userId) { s ->
            val i = s.users.indexOfFirst { it.id == userId }
            if (i < 0) fail(IdentityException.Reason.NOT_FOUND)
            if (s.users[i].role == role) return@change
            if (s.users[i].role == IdentityRole.ADMIN && role != IdentityRole.ADMIN && s.users.count { it.role == IdentityRole.ADMIN } == 1)
                fail(IdentityException.Reason.CONFLICT)
            s.users[i] = s.users[i].copy(role = role)
            revokeUser(s, userId)
        }
    }

    @Synchronized fun changePassword(access: String, oldPassword: String, newPassword: String) {
        val actor = requireAccess(access); val old = validPassword(oldPassword); val next = validPassword(newPassword)
        val user = state.users.first { it.id == actor.userId }
        val computed = pbkdf2(old, user.salt)
        val match = MessageDigest.isEqual(computed, user.hash)
        computed.fill(0)
        if (!match) fail(IdentityException.Reason.INVALID_CREDENTIALS)
        change(IdentityEventType.PASSWORD_CHANGED, actor.userId, actor.userId) { s ->
            val index = s.users.indexOfFirst { it.id == actor.userId }
            val salt = random(16); val hash = pbkdf2(next, salt)
            s.users[index] = s.users[index].copy(salt = salt, hash = hash)
            revokeUser(s, actor.userId)
        }
    }

    private fun requireAdmin(access: String): Session {
        val session = requireAccess(access)
        if (state.users.firstOrNull { it.id == session.userId }?.role != IdentityRole.ADMIN) fail(IdentityException.Reason.FORBIDDEN)
        return session
    }
    private fun requireAccess(token: String): Session {
        val hash = tokenHash(token)
        val now = clock.nowMillis()
        return state.sessions.firstOrNull { now < it.accessExpiry && now < it.refreshExpiry && constantHashEqual(it.accessHash, hash) }
            ?: fail(IdentityException.Reason.UNAUTHORIZED)
    }

    private fun createUser(login: String, pass: String, role: IdentityRole): User {
        val salt = random(16)
        return User(randomId(), login, role, salt, pbkdf2(pass, salt))
    }
    private fun User.toPublic() = IdentityUser(id, login, role, IdentityPolicy.scopes(role))
    private fun makeSession(s: State, user: User, device: String, now: Long,
                            family: String = randomId(), created: Long = now,
                            refreshExpiry: Long = plus(now, REFRESH_TTL_MS)): IdentityTokens {
        if (s.sessions.size >= MAX_SESSIONS) fail(IdentityException.Reason.LIMIT)
        val a = randomToken(); val r = randomToken()
        val accessExpiry = minOf(plus(now, ACCESS_TTL_MS), refreshExpiry)
        if (now >= accessExpiry) fail(IdentityException.Reason.UNAUTHORIZED)
        s.sessions.add(Session(randomId(), user.id, family, device, created, tokenHash(a), accessExpiry, tokenHash(r), refreshExpiry))
        return IdentityTokens(a, r, accessExpiry, refreshExpiry)
    }
    private fun revokeUser(s: State, userId: String) {
        val families = s.sessions.filter { it.userId == userId }.map { it.family }.toSet()
        s.sessions.removeAll { it.userId == userId }
        s.replay.entries.removeAll { it.value.family in families }
    }
    private fun revokeFamily(s: State, family: String) {
        s.sessions.removeAll { it.family == family }
        s.replay.entries.removeAll { it.value.family == family }
    }
    private fun prune(s: State, now: Long) {
        s.sessions.removeAll { now >= it.refreshExpiry }
        s.replay.entries.removeAll { now >= it.value.expiry }
        s.guards.entries.removeAll { now >= it.value.lockedUntil && now - it.value.lastAttempt >= LOCK_MS }
    }

    /** All edits happen in an isolated decoded copy; publish only after atomic save succeeds. */
    private inline fun <T> change(type: IdentityEventType, actorId: String?, subjectId: String?, edit: (State) -> T): T {
        val next = decode(encode(state))
        prune(next, clock.nowMillis())
        val result = edit(next)
        val bytes = encode(next)
        store.save(bytes.copyOf()) // any exception: original in-memory state is untouched
        state = next
        try { audit(IdentityEvent(type, actorId, subjectId, clock.nowMillis())) } catch (_: Exception) { /* committed */ }
        return result
    }

    private fun encode(s: State): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeInt(s.users.size)
            for (u in s.users) { out.writeUTF(u.id); out.writeUTF(u.login); out.writeInt(u.role.ordinal); writeBytes(out, u.salt); writeBytes(out, u.hash) }
            out.writeInt(s.sessions.size)
            for (x in s.sessions) {
                out.writeUTF(x.id); out.writeUTF(x.userId); out.writeUTF(x.family); out.writeUTF(x.device)
                out.writeLong(x.created); out.writeUTF(x.accessHash); out.writeLong(x.accessExpiry)
                out.writeUTF(x.refreshHash); out.writeLong(x.refreshExpiry)
            }
            out.writeInt(s.replay.size)
            for ((key, value) in s.replay) { out.writeUTF(key); out.writeUTF(value.family); out.writeLong(value.expiry) }
            out.writeInt(s.guards.size)
            for ((key, value) in s.guards) { out.writeUTF(key); out.writeInt(value.failures); out.writeLong(value.lockedUntil); out.writeLong(value.lastAttempt) }
        }
        return bos.toByteArray().also { if (it.size > MAX_SNAPSHOT_BYTES) fail(IdentityException.Reason.LIMIT) }
    }
    private fun decode(bytes: ByteArray): State {
        if (bytes.size > MAX_SNAPSHOT_BYTES) fail(IdentityException.Reason.INVALID_SNAPSHOT)
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) fail(IdentityException.Reason.INVALID_SNAPSHOT)
                val s = State()
                repeat(readCount(input, MAX_USERS)) {
                    val id = input.readUTF(); val login = input.readUTF(); val ordinal = input.readInt()
                    val salt = readBytes(input, 16); val hash = readBytes(input, 32)
                    if (!validIdentifier(id) || !isValidLogin(login) || ordinal !in IdentityRole.entries.indices ||
                        s.users.any { it.id == id || it.login == login }) fail(IdentityException.Reason.INVALID_SNAPSHOT)
                    s.users.add(User(id, login, IdentityRole.entries[ordinal], salt, hash))
                }
                repeat(readCount(input, MAX_SESSIONS)) {
                    val id = input.readUTF(); val uid = input.readUTF(); val family = input.readUTF(); val device = input.readUTF()
                    val created = input.readLong(); val ah = input.readUTF(); val ae = input.readLong(); val rh = input.readUTF(); val re = input.readLong()
                    if (!validIdentifier(id) || !validIdentifier(uid) || !validIdentifier(family) || device.length > MAX_DEVICE_LENGTH ||
                        !validHash(ah) || !validHash(rh) || created < 0 || ae < created || re < ae ||
                        s.users.none { it.id == uid } || s.sessions.any { it.id == id || it.accessHash == ah || it.refreshHash == rh || it.family == family })
                        fail(IdentityException.Reason.INVALID_SNAPSHOT)
                    s.sessions.add(Session(id, uid, family, device, created, ah, ae, rh, re))
                }
                repeat(readCount(input, MAX_REPLAY_RECORDS)) {
                    val h = input.readUTF(); val f = input.readUTF(); val e = input.readLong()
                    if (!validHash(h) || !validIdentifier(f) || e < 0 || s.replay.putIfAbsent(h, Replay(f, e)) != null ||
                        s.sessions.any { it.refreshHash == h }) fail(IdentityException.Reason.INVALID_SNAPSHOT)
                }
                repeat(readCount(input, MAX_LOGIN_GUARDS)) {
                    val login = input.readUTF(); val n = input.readInt(); val lock = input.readLong(); val last = input.readLong()
                    if (!isValidLogin(login) || n !in 1..5 || lock < 0 || last < 0 || s.guards.putIfAbsent(login, Guard(n, lock, last)) != null)
                        fail(IdentityException.Reason.INVALID_SNAPSHOT)
                }
                if (input.available() != 0) fail(IdentityException.Reason.INVALID_SNAPSHOT)
                return s
            }
        } catch (e: IdentityException) { throw e }
        catch (_: Exception) { fail(IdentityException.Reason.INVALID_SNAPSHOT) }
    }
    private fun readCount(input: DataInputStream, max: Int): Int = input.readInt().also {
        if (it !in 0..max) fail(IdentityException.Reason.INVALID_SNAPSHOT)
    }
    private fun writeBytes(o: DataOutputStream, bytes: ByteArray) { o.writeInt(bytes.size); o.write(bytes) }
    private fun readBytes(i: DataInputStream, size: Int): ByteArray {
        if (i.readInt() != size) fail(IdentityException.Reason.INVALID_SNAPSHOT)
        return ByteArray(size).also { i.readFully(it) }
    }
    private fun validLogin(value: String): String {
        if (!isValidLogin(value)) fail(IdentityException.Reason.INVALID_INPUT)
        return value
    }
    private fun isValidLogin(v: String): Boolean = v.length in 1..MAX_LOGIN_LENGTH && v.matches(Regex("[a-zA-Z0-9._-]+"))
    private fun validPassword(v: String): String {
        if (v.length !in 8..MAX_PASSWORD_LENGTH || v.toByteArray(StandardCharsets.UTF_8).size > 1024) fail(IdentityException.Reason.INVALID_INPUT)
        return v
    }
    private fun validDevice(v: String): String {
        if (v.length > MAX_DEVICE_LENGTH || v.any { it.isISOControl() }) fail(IdentityException.Reason.INVALID_INPUT)
        return v
    }
    private fun validIdentifier(v: String): Boolean = v.length == 22 && v.matches(Regex("[A-Za-z0-9_-]{22}"))
    private fun validHash(v: String): Boolean = v.length == 64 && v.matches(Regex("[0-9a-f]{64}"))
    private fun random(n: Int) = ByteArray(n).also { entropy.fill(it) }
    private fun randomId() = Base64.getUrlEncoder().withoutPadding().encodeToString(random(16))
    private fun randomToken() = Base64.getUrlEncoder().withoutPadding().encodeToString(random(32))
    private fun tokenHash(v: String): String {
        if (v.length != 43 || !v.matches(Regex("[A-Za-z0-9_-]{43}"))) fail(IdentityException.Reason.UNAUTHORIZED)
        val decoded = try { Base64.getUrlDecoder().decode(v) } catch (_: IllegalArgumentException) { fail(IdentityException.Reason.UNAUTHORIZED) }
        if (decoded.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != v) fail(IdentityException.Reason.UNAUTHORIZED)
        return sha(decoded)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun constantHashEqual(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(StandardCharsets.US_ASCII), b.toByteArray(StandardCharsets.US_ASCII))
    private fun pbkdf2(pass: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pass.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.copyOf() }
        finally { spec.clearPassword() }
    }
    private fun plus(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
    private fun fail(reason: IdentityException.Reason): Nothing = throw IdentityException(reason)
}
