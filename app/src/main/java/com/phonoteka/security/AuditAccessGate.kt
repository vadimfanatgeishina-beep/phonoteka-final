package com.phonoteka.security

/** Role is checked independently of visibility; final ALLOWED is emitted only AFTER fresh/step-up gate. */
class AuditAccessGate(
    private val authority: MockRoleAuthority,
    private val record: (DemoSession, String, Boolean, String) -> Unit,
    private val readGate: () -> Unit,
    private val exportGate: () -> Unit
) {
    fun authorize(session: DemoSession, exporting: Boolean = false) { withAccess(session, exporting) {} }
    fun <T> withAccess(session: DemoSession, exporting: Boolean = false, protectedRead: () -> T): T {
        val action = if (exporting) "audit.export" else "audit.read"
        if (!authority.authorize(session, DemoScope.AUDIT_READ)) {
            record(session, action, false, "journal:audit")
            throw SecurityException("Verified ADMIN grant required")
        }
        try { if (exporting) exportGate() else readGate() }
        catch (failure: Exception) {
            try { record(session, action, false, "journal:audit") } catch (auditFailure: Exception) { failure.addSuppressed(auditFailure) }
            throw failure
        }
        return try {
            authority.withVerifiedGrant(session, DemoScope.AUDIT_READ) {
                record(session, action, true, "journal:audit") // Failure to persist => no read/export.
                protectedRead()
            }
        } catch (revoked: SecurityException) {
            try { record(session, action, false, "journal:audit") } catch (failure: Exception) { revoked.addSuppressed(failure) }
            throw revoked
        }
    }
}
object AuditSubjects {
    fun collectionFor(action: String): String = when (action.substringBefore('.')) {
        "library" -> "collection:library"
        "notes" -> "collection:notes"
        "audit" -> "journal:audit"
        "invite" -> "feature:invites"
        "permission" -> "permission:" + action.substringAfter('.', "unknown")
        "role" -> "role:fixture"
        else -> "resource:$action"
    }
}
