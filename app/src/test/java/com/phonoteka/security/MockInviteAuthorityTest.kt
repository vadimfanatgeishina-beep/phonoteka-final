package com.phonoteka.security
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
class MockInviteAuthorityTest {
    private val mac = InviteMac { bytes -> Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")); doFinal(bytes)
    } }
    @Test fun adminIssuesSecurityConsumesOnceAndEmployeeCannotScan() {
        val roles = MockRoleAuthority { 1000L }
        val invites = MockInviteAuthority(roles, mac, { 1000L })
        val wire = invites.issue(roles.issue(DemoRole.ADMIN), 42)
        denied(InviteFailure.DENIED) { invites.scan(roles.issue(DemoRole.EMPLOYEE), wire) }
        val security = roles.issue(DemoRole.SECURITY)
        assertEquals(42L, invites.scan(security, wire).trackId)
        denied(InviteFailure.REPLAY) { invites.scan(security, wire) }
    }
    @Test fun forgedRoleTamperedTrackAndUnknownIssuanceNeverConsumeValidInvite() {
        val roles = MockRoleAuthority { 1000L }
        val invites = MockInviteAuthority(roles, mac, { 1000L })
        val employee = roles.issue(DemoRole.EMPLOYEE)
        denied(InviteFailure.DENIED) { invites.issue(employee.copy(role = DemoRole.ADMIN, scopes = DemoRolePolicy.scopes(DemoRole.ADMIN)), 1) }
        val wire = invites.issue(roles.issue(DemoRole.ADMIN), 42)
        val security = roles.issue(DemoRole.SECURITY)
        val fields = wire.split('\n').toMutableList().apply { this[2] = "43" }
        denied(InviteFailure.INVALID) { invites.scan(security, fields.joinToString("\n")) }
        assertEquals(42L, invites.scan(security, wire).trackId)
    }
    @Test fun exactTtlRestartRevocationAndCapacityFailClosed() {
        var now = 1000L
        val roles = MockRoleAuthority { now }
        val invites = MockInviteAuthority(roles, mac, { now }, capacity = 1)
        val admin = roles.issue(DemoRole.ADMIN)
        val wire = invites.issue(admin, 1)
        denied(InviteFailure.CAPACITY) { invites.issue(admin, 2) }
        val security = roles.issue(DemoRole.SECURITY)
        denied(InviteFailure.REPLAY) { MockInviteAuthority(roles, mac, { now }).scan(security, wire) }
        invites.revokeAll()
        denied(InviteFailure.REPLAY) { invites.scan(security, wire) }
        val timed = invites.issue(roles.issue(DemoRole.ADMIN), 1)
        now += 300_000
        denied(InviteFailure.EXPIRED) { invites.scan(roles.issue(DemoRole.SECURITY), timed) }
    }
    private fun denied(reason: InviteFailure, block: () -> Any?) {
        var actual: InviteFailure? = null
        try { block() } catch (e: InviteException) { actual = e.failure }
        assertEquals(reason, actual)
    }
}
