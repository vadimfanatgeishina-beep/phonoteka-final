package com.phonoteka.security

import java.security.MessageDigest
import java.security.SecureRandom

enum class DemoRole { EMPLOYEE, SECURITY, ADMIN }
/** ЛР14: wire — имя scope в учебном JWT-подобном claim `scope` (pass:view, scan:execute, audit:read). */
enum class DemoScope(val wire: String) {
    LIBRARY_READ("library:read"), LIBRARY_WRITE("library:write"), PASS_VIEW("pass:view"),
    SCAN_TRACK("scan:execute"), AUDIT_READ("audit:read"), INVITE_CREATE("invite:create");
    companion object {
        fun fromWire(value: String): DemoScope? = entries.firstOrNull { it.wire == value }
        /** Разбор claim «scope»: неизвестные значения игнорируются (deny by default). */
        fun parseClaim(claim: String): Set<DemoScope> = claim.split(' ').mapNotNull(::fromWire).toSet()
    }
}
/** Строка как в JWT: "audit:read library:read pass:view". */
val DemoSession.scopeClaim: String get() = scopes.map { it.wire }.sorted().joinToString(" ")
data class DemoSession(val role: DemoRole, val scopes: Set<DemoScope>, val mockToken: String,
    val actorId: String = "mock:student") {
    override fun toString() = "DemoSession(role=$role, token-redacted)"
}
object DemoRolePolicy {
    fun scopes(role: DemoRole): Set<DemoScope> = when (role) {
        DemoRole.EMPLOYEE -> setOf(DemoScope.LIBRARY_READ, DemoScope.LIBRARY_WRITE, DemoScope.PASS_VIEW)
        DemoRole.SECURITY -> setOf(DemoScope.LIBRARY_READ, DemoScope.PASS_VIEW, DemoScope.SCAN_TRACK)
        DemoRole.ADMIN -> DemoScope.entries.toSet()
    }
}
/** Owned in-process mock, not a production server boundary or JWT verifier. */
class MockRoleAuthority(private val now: () -> Long = System::currentTimeMillis) {
    private data class Grant(val role: DemoRole, val expires: Long, val actorId: String = "mock:student")
    private val grants = mutableMapOf<String, Grant>()
    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun issue(role: DemoRole): DemoSession {
        // Role fixture selection invalidates previous grants, including a previous ADMIN grant.
        grants.clear()
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        grants[digest(token)] = Grant(role, now() + 5 * 60_000)
        return DemoSession(role, DemoRolePolicy.scopes(role), token)
    }
    @Synchronized fun authorize(session: DemoSession, scope: DemoScope): Boolean {
        val grant = grants[digest(session.mockToken)] ?: return false
        if (now() >= grant.expires) { grants.remove(digest(session.mockToken)); return false }
        return session.actorId == grant.actorId && session.role == grant.role && session.scopes == DemoRolePolicy.scopes(grant.role) && scope in DemoRolePolicy.scopes(grant.role)
    }
    @Synchronized fun valid(session: DemoSession): Boolean = authorize(session, DemoScope.LIBRARY_READ)
    @Synchronized fun <T> withVerifiedGrant(session: DemoSession, scope: DemoScope, action: () -> T): T {
        if (!authorize(session, scope)) throw SecurityException("Grant no longer valid")
        return action() // Prevent role revocation between final decision and protected snapshot read.
    }
    @Synchronized fun auditActor(session: DemoSession): AuditActor {
        if (!valid(session)) return AuditActor("unverified", "UNVERIFIED")
        val grant = grants[digest(session.mockToken)] ?: error("Grant disappeared")
        return AuditActor(grant.actorId, grant.role.name)
    }
    @Synchronized fun revokeAll() { grants.clear() }
}
