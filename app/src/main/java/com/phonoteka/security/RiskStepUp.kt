package com.phonoteka.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Monotonic timestamps, never persisted as authority across process death/reboot. */
data class AuthenticationSnapshot(val version: Long, val authenticatedAt: Long?)
data class StepUpRisk(val debuggerAttached: Boolean = false, val resumedAfterLongBackground: Boolean = false)
enum class ProtectedOperation {
    ORDINARY, LIBRARY_EXPORT, LIBRARY_REPLACE, LIBRARY_CLEAR, NOTES_EXPORT, NOTES_REPLACE,
    NOTES_RESET, NOTES_TRASH_CLEAR, NOTES_REMOVE_CORRUPTED, AUDIT_EXPORT, INVITE_CREATE, INVITE_SCAN;
    companion object {
        fun fromResource(resource: String): ProtectedOperation = when (resource) {
            "library.export" -> LIBRARY_EXPORT
            "library.importBackup" -> LIBRARY_REPLACE
            "library.clearAllTracks" -> LIBRARY_CLEAR
            "notes.export" -> NOTES_EXPORT
            "notes.import" -> NOTES_REPLACE
            "notes.reset" -> NOTES_RESET
            "notes.trash.clear" -> NOTES_TRASH_CLEAR
            "notes.corrupt.remove" -> NOTES_REMOVE_CORRUPTED
            "audit.export" -> AUDIT_EXPORT
            "invite.create", "invite.export" -> INVITE_CREATE
            "invite.scan" -> INVITE_SCAN
            else -> ORDINARY
        }
    }
}
object RiskStepUpPolicy {
    const val SESSION_WINDOW = 300_000L
    const val APPROVAL_WINDOW = 60_000L
    const val CHALLENGE_WINDOW = 120_000L
    fun fresh(snapshot: AuthenticationSnapshot, now: Long): Boolean {
        val at = snapshot.authenticatedAt ?: return false
        return snapshot.version > 0 && at >= 0 && now >= at && now - at < SESSION_WINDOW
    }
    fun needsExplicitApproval(operation: ProtectedOperation, risk: StepUpRisk): Boolean = when (operation) {
        ProtectedOperation.LIBRARY_REPLACE, ProtectedOperation.LIBRARY_CLEAR, ProtectedOperation.NOTES_REPLACE,
        ProtectedOperation.NOTES_RESET, ProtectedOperation.NOTES_TRASH_CLEAR, ProtectedOperation.NOTES_REMOVE_CORRUPTED,
        ProtectedOperation.AUDIT_EXPORT, ProtectedOperation.INVITE_CREATE -> true
        ProtectedOperation.LIBRARY_EXPORT, ProtectedOperation.NOTES_EXPORT, ProtectedOperation.INVITE_SCAN ->
            risk.debuggerAttached || risk.resumedAfterLongBackground
        ProtectedOperation.ORDINARY -> false
    }
}
data class StepUpChallenge(val id: String, val operation: ProtectedOperation, val issuedAt: Long, val baselineVersion: Long)
class StepUpRequiredException : SecurityException("Confirm authentication, then repeat the operation")
/** Repository guard, not a UI boolean. One operation-specific 60s approval, no queued side effects. */
class RiskStepUpController(private val now: () -> Long) {
    private data class Approval(val version: Long, val grantedAt: Long)
    private val approvals = mutableMapOf<ProtectedOperation, Approval>()
    private val mutable = MutableStateFlow<StepUpChallenge?>(null)
    val pending: StateFlow<StepUpChallenge?> = mutable.asStateFlow()
    @Synchronized fun require(operation: ProtectedOperation, snapshot: AuthenticationSnapshot, risk: StepUpRisk) {
        val time = now()
        if (!RiskStepUpPolicy.fresh(snapshot, time)) throw SecurityException("Fresh authentication required")
        if (!RiskStepUpPolicy.needsExplicitApproval(operation, risk)) return
        val approval = approvals[operation]
        if (approval != null && approval.version == snapshot.version && time >= approval.grantedAt &&
            time - approval.grantedAt < RiskStepUpPolicy.APPROVAL_WINDOW) return
        val challenge = mutable.value
        if (challenge == null || challenge.operation != operation || time < challenge.issuedAt ||
            time - challenge.issuedAt >= RiskStepUpPolicy.CHALLENGE_WINDOW) {
            mutable.value = StepUpChallenge(UUID.randomUUID().toString(), operation, time, snapshot.version)
        }
        throw StepUpRequiredException()
    }
    @Synchronized fun confirm(id: String, snapshot: AuthenticationSnapshot): Boolean {
        val challenge = mutable.value ?: return false
        val time = now()
        if (challenge.id != id || snapshot.version <= challenge.baselineVersion || !RiskStepUpPolicy.fresh(snapshot, time) ||
            time < challenge.issuedAt || time - challenge.issuedAt >= RiskStepUpPolicy.CHALLENGE_WINDOW ||
            snapshot.authenticatedAt == null || snapshot.authenticatedAt < challenge.issuedAt) return false
        approvals[challenge.operation] = Approval(snapshot.version, time)
        mutable.value = null
        return true
    }
    @Synchronized fun cancel(id: String) { if (mutable.value?.id == id) mutable.value = null }
    @Synchronized fun invalidate() { approvals.clear(); mutable.value = null }
}
