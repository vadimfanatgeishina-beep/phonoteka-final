package com.phonoteka.security
import org.junit.Assert.*
import org.junit.Test

class AuditAccessGateTest {
    private fun denied(action: () -> Unit) { try { action(); fail("Access granted") } catch (_: SecurityException) {} }
    @Test fun forgedAdminIdentityDoesNotReachFreshGateOrRead() {
        val authority = MockRoleAuthority(); val employee = authority.issue(DemoRole.EMPLOYEE)
        val events = mutableListOf<Boolean>(); var gates = 0
        val gate = AuditAccessGate(authority, { _, _, ok, _ -> events += ok }, { gates++ }, { gates++ })
        denied { gate.authorize(employee.copy(role = DemoRole.ADMIN, scopes = DemoRolePolicy.scopes(DemoRole.ADMIN))) }
        assertEquals(listOf(false), events); assertEquals(0, gates)
    }
    @Test fun changedActorIdIsRejectedAndAuditDoesNotTrustClaimedIdentity() {
        val a = MockRoleAuthority(); val admin = a.issue(DemoRole.ADMIN).copy(actorId = "forged")
        assertFalse(a.authorize(admin, DemoScope.AUDIT_READ)); assertEquals(AuditActor("unverified", "UNVERIFIED"), a.auditActor(admin))
    }
    @Test fun freshGateFailureEmitsDeniedNotAllowed() {
        val a = MockRoleAuthority(); val events = mutableListOf<Boolean>()
        val gate = AuditAccessGate(a, { _, _, ok, _ -> events += ok }, { throw SecurityException("Fresh auth required") }, {})
        denied { gate.authorize(a.issue(DemoRole.ADMIN)) }; assertEquals(listOf(false), events)
    }
    @Test fun exportUsesItsOwnGateAndPersistsDecisionBeforeReturning() {
        val a = MockRoleAuthority(); val order = mutableListOf<String>()
        val gate = AuditAccessGate(a, { _, act, ok, subject -> assertTrue(ok); assertEquals("journal:audit", subject); order += act }, { order += "read-gate" }, { order += "export-gate" })
        gate.authorize(a.issue(DemoRole.ADMIN), true); assertEquals(listOf("export-gate", "audit.export"), order)
    }
    @Test fun persistFailureCannotGrantRead() {
        val a = MockRoleAuthority(); val gate = AuditAccessGate(a, { _, _, _, _ -> throw java.io.IOException("Own failed write") }, {}, {})
        try { gate.authorize(a.issue(DemoRole.ADMIN)); fail() } catch (_: java.io.IOException) {}
    }
    @Test fun validActorHasStableMockIdentityWithoutBearerInToString() {
        val a = MockRoleAuthority(); val session = a.issue(DemoRole.SECURITY)
        assertEquals(AuditActor("mock:student", "SECURITY"), a.auditActor(session)); assertFalse(session.toString().contains(session.mockToken))
    }
    @Test fun revokedGrantIsUnverifiedEvenWhenRoleStillSaysAdmin() {
        val a = MockRoleAuthority(); val old = a.issue(DemoRole.ADMIN); a.issue(DemoRole.EMPLOYEE)
        assertEquals(AuditActor("unverified", "UNVERIFIED"), a.auditActor(old))
    }
    @Test fun scopedCollectionSubjectsAreNotDuplicatedActionStrings() {
        assertEquals("collection:notes", AuditSubjects.collectionFor("notes.export"))
        assertEquals("journal:audit", AuditSubjects.collectionFor("audit.read"))
        assertEquals("permission:POST_NOTIFICATIONS", AuditSubjects.collectionFor("permission.POST_NOTIFICATIONS"))
    }
    @Test fun revokeDuringFreshGateNeverReachesProtectedRead() {
        val a = MockRoleAuthority(); val admin = a.issue(DemoRole.ADMIN)
        val decisions = mutableListOf<Boolean>(); var reads = 0
        val gate = AuditAccessGate(a, { _, _, ok, _ -> decisions += ok }, { a.issue(DemoRole.EMPLOYEE) }, {})
        denied { gate.withAccess(admin) { reads++ } }
        assertEquals(0, reads); assertEquals(listOf(false), decisions)
    }
    @Test fun successfulProtectedSnapshotIsInsideVerifiedGrant() {
        val a = MockRoleAuthority(); val admin = a.issue(DemoRole.ADMIN)
        val gate = AuditAccessGate(a, { _, _, _, _ -> }, {}, {})
        assertEquals("owned-snapshot", gate.withAccess(admin) { "owned-snapshot" })
    }

}
