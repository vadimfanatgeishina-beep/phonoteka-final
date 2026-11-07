package com.phonoteka.security
import org.junit.Assert.*
import org.junit.Test

class RiskStepUpTest {
    @Test fun sensitiveOperationRequiresNewAuthenticationAndIsBoundToOperationAndVersion() {
        var now = 1000L
        val controller = RiskStepUpController { now }
        val initial = AuthenticationSnapshot(1, now)
        denied { controller.require(ProtectedOperation.LIBRARY_CLEAR, initial, StepUpRisk()) }
        val challenge = controller.pending.value!!
        assertFalse(controller.confirm(challenge.id, initial))
        val authenticated = AuthenticationSnapshot(2, now)
        assertTrue(controller.confirm(challenge.id, authenticated))
        controller.require(ProtectedOperation.LIBRARY_CLEAR, authenticated, StepUpRisk())
        denied { controller.require(ProtectedOperation.AUDIT_EXPORT, authenticated, StepUpRisk()) }
        controller.cancel(controller.pending.value!!.id)
        now += 60_000
        denied { controller.require(ProtectedOperation.LIBRARY_CLEAR, authenticated, StepUpRisk()) }
    }
    @Test fun cancellationProcessRecreationAndRoleChangeDoNotRetainApproval() {
        val now = 1000L
        val controller = RiskStepUpController { now }
        val initial = AuthenticationSnapshot(1, now)
        denied { controller.require(ProtectedOperation.INVITE_CREATE, initial, StepUpRisk()) }
        val id = controller.pending.value!!.id
        controller.cancel(id)
        assertFalse(controller.confirm(id, AuthenticationSnapshot(2, now)))
        denied { controller.require(ProtectedOperation.INVITE_CREATE, initial, StepUpRisk()) }
        assertTrue(controller.confirm(controller.pending.value!!.id, AuthenticationSnapshot(2, now)))
        controller.invalidate()
        denied { controller.require(ProtectedOperation.INVITE_CREATE, AuthenticationSnapshot(2, now), StepUpRisk()) }
        denied { RiskStepUpController { now }.require(ProtectedOperation.INVITE_CREATE, AuthenticationSnapshot(2, now), StepUpRisk()) }
    }
    @Test fun debuggerOrLongResumeElevatesExportButDoesNotBanNormalReadsOrEmulators() {
        val controller = RiskStepUpController { 1000L }
        val snapshot = AuthenticationSnapshot(1, 1000)
        controller.require(ProtectedOperation.LIBRARY_EXPORT, snapshot, StepUpRisk())
        denied { controller.require(ProtectedOperation.LIBRARY_EXPORT, snapshot, StepUpRisk(debuggerAttached = true)) }
        controller.require(ProtectedOperation.ORDINARY, snapshot, StepUpRisk(debuggerAttached = true))
        denied { controller.require(ProtectedOperation.NOTES_EXPORT, snapshot, StepUpRisk(resumedAfterLongBackground = true)) }
    }
    @Test fun exactFiveMinutesFutureAuthAndExpiredChallengeFailClosed() {
        var now = 1000L
        val controller = RiskStepUpController { now }
        denied { controller.require(ProtectedOperation.ORDINARY, AuthenticationSnapshot(1, 1001), StepUpRisk()) }
        denied { controller.require(ProtectedOperation.LIBRARY_REPLACE, AuthenticationSnapshot(1, now), StepUpRisk()) }
        val id = controller.pending.value!!.id
        now += 120_000
        assertFalse(controller.confirm(id, AuthenticationSnapshot(2, now)))
        now = 301_000
        denied { controller.require(ProtectedOperation.ORDINARY, AuthenticationSnapshot(1, 1000), StepUpRisk()) }
    }
    private fun denied(block: () -> Unit) { var denied = false; try { block() } catch (_: SecurityException) { denied = true }; assertTrue(denied) }
}
