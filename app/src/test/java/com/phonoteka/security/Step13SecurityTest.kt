package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployeeInviteLinkTest {
    private fun err(raw: String?) = (EmployeeInviteLink.parse(raw) as InviteLinkResult.Invalid).error

    @Test fun validTokens() {
        assertEquals(InviteLinkResult.Valid("abcDEF12"), EmployeeInviteLink.parse("phonoteka://invite?token=abcDEF12"))
        assertEquals(InviteLinkResult.Valid("a".repeat(64)), EmployeeInviteLink.parse("phonoteka://invite?token=" + "a".repeat(64)))
        assertEquals(InviteLinkResult.Valid("A_b-9_zz"), EmployeeInviteLink.parse("phonoteka://invite/?token=A_b-9_zz"))
    }

    @Test fun tokenRegexBoundaries() {
        assertEquals(InviteLinkError.BAD_TOKEN, err("phonoteka://invite?token=short7x"))
        assertEquals(InviteLinkError.BAD_TOKEN, err("phonoteka://invite?token=" + "a".repeat(65)))
        assertEquals(InviteLinkError.BAD_TOKEN, err("phonoteka://invite?token=abc%2Fdefgh"))
        assertEquals(InviteLinkError.BAD_TOKEN, err("phonoteka://invite?token=abcdefgh."))
        assertEquals(InviteLinkError.MISSING_TOKEN, err("phonoteka://invite?token="))
        assertEquals(InviteLinkError.MISSING_TOKEN, err("phonoteka://invite"))
    }

    @Test fun hostileShapesRejected() {
        assertEquals(InviteLinkError.MALFORMED, err(null))
        assertEquals(InviteLinkError.MALFORMED, err("phonoteka://invite?token=abc defgh"))
        assertEquals(InviteLinkError.WRONG_TARGET, err("https://invite?token=abcdefgh"))
        assertEquals(InviteLinkError.WRONG_TARGET, err("phonoteka://details/1"))
        assertEquals(InviteLinkError.WRONG_TARGET, err("phonoteka://evil@invite?token=abcdefgh"))
        assertEquals(InviteLinkError.WRONG_TARGET, err("phonoteka://invite:8080?token=abcdefgh"))
        assertEquals(InviteLinkError.WRONG_TARGET, err("phonoteka://invite/admin?token=abcdefgh"))
        assertEquals(InviteLinkError.WRONG_TARGET, err("phonoteka://invite?token=abcdefgh#x"))
        assertEquals(InviteLinkError.UNEXPECTED_PARAMETERS, err("phonoteka://invite?token=abcdefgh&role=ADMIN"))
        assertEquals(InviteLinkError.UNEXPECTED_PARAMETERS, err("phonoteka://invite?token=abcdefgh&token=zzzzzzzz"))
        assertEquals(InviteLinkError.UNEXPECTED_PARAMETERS, err("phonoteka://invite?role=ADMIN"))
        assertEquals(InviteLinkError.MALFORMED, err("phonoteka://invite?token=" + "a".repeat(300)))
        assertFalse(EmployeeInviteLink.parse("phonoteka://invite?token=abcDEF12").toString().contains("abcDEF12"))
    }

    @Test fun registrationIsSingleUseAndValidated() {
        val r = MockInviteRegistration()
        assertEquals(InviteRegistrationResult.INVALID_NAME, r.register("abcdefgh", "A", "a@b.cd"))
        assertEquals(InviteRegistrationResult.INVALID_EMAIL, r.register("abcdefgh", "Иван", "not-mail"))
        assertEquals(InviteRegistrationResult.INVALID_TOKEN, r.register("bad", "Иван", "a@b.cd"))
        assertEquals(InviteRegistrationResult.CREATED, r.register("abcdefgh", "Иван", "a@b.cd"))
        assertEquals(InviteRegistrationResult.ALREADY_USED, r.register("abcdefgh", "Пётр", "p@b.cd"))
    }
}

class DemoScopeClaimTest {
    @Test fun wireNamesMatchLab14() {
        assertEquals("pass:view", DemoScope.PASS_VIEW.wire)
        assertEquals("scan:execute", DemoScope.SCAN_TRACK.wire)
        assertEquals("audit:read", DemoScope.AUDIT_READ.wire)
        assertEquals(DemoScope.entries.size, DemoScope.entries.map { it.wire }.toSet().size)
    }

    @Test fun rolesGetExpectedScopes() {
        assertTrue(DemoScope.PASS_VIEW in DemoRolePolicy.scopes(DemoRole.EMPLOYEE))
        assertFalse(DemoScope.SCAN_TRACK in DemoRolePolicy.scopes(DemoRole.EMPLOYEE))
        assertFalse(DemoScope.AUDIT_READ in DemoRolePolicy.scopes(DemoRole.SECURITY))
        assertTrue(DemoScope.SCAN_TRACK in DemoRolePolicy.scopes(DemoRole.SECURITY))
        assertEquals(DemoScope.entries.toSet(), DemoRolePolicy.scopes(DemoRole.ADMIN))
    }

    @Test fun claimRoundTripIgnoresUnknown() {
        val claim = "pass:view scan:execute admin:all audit:read"
        assertEquals(setOf(DemoScope.PASS_VIEW, DemoScope.SCAN_TRACK, DemoScope.AUDIT_READ), DemoScope.parseClaim(claim))
        assertEquals(emptySet<DemoScope>(), DemoScope.parseClaim(""))
    }
}

class RecoveryNotifierTest {
    @Test fun noticeIsMaskedAndHasNoSecrets() {
        val outbox = MockRecoveryOutbox("student@example.test", "+79991234567", capacity = 3)
        val n = outbox.recoveryCodeUsed(1000)
        assertEquals(listOf("email", "sms"), n.map { it.channel })
        assertEquals("s***@example.test", n[0].maskedDestination)
        assertEquals("********4567", n[1].maskedDestination)
        repeat(3) { outbox.recoveryCodeUsed(2000L + it) }
        assertEquals(3, outbox.messages().size)
    }
}

class SensitiveResultsGateTest {
    @Test fun loaderIsNotCalledWithoutFreshAuthentication() {
        var fresh = false; var loads = 0; val audit = mutableListOf<String>()
        val gate = SensitiveResultsGate({ fresh }, { loads++; listOf("r") }, { audit += it })
        assertEquals(SensitiveResultsState.StepUpRequired, gate.open())
        assertEquals(0, loads)
        fresh = true
        assertEquals(SensitiveResultsState.Loaded(listOf("r")), gate.open())
        assertEquals(1, loads)
        assertEquals(listOf("results_stepup_required", "results_opened"), audit)
    }
}

class ClipboardClearPolicyTest {
    @Test fun clearsOnlyOurClipAfter30s() {
        val ours = ClipboardClearPolicy.fingerprint("secret")
        assertFalse(ClipboardClearPolicy.shouldClear(ours, ours, 29_999))
        assertTrue(ClipboardClearPolicy.shouldClear(ours, ours, 30_000))
        assertTrue(ClipboardClearPolicy.shouldClear(null, ours, 30_000))
        assertFalse(ClipboardClearPolicy.shouldClear(ClipboardClearPolicy.fingerprint("other"), ours, 60_000))
    }
}
