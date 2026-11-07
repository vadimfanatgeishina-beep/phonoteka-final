package com.phonoteka.security
import org.junit.Assert.*
import org.junit.Test
class MockRoleAuthorityTest {
    @Test fun forgedRoleScopesOrTokenCannotReadAudit() {
        val authority = MockRoleAuthority()
        val employee = authority.issue(DemoRole.EMPLOYEE)
        assertFalse(authority.authorize(employee.copy(role = DemoRole.ADMIN, scopes = DemoRolePolicy.scopes(DemoRole.ADMIN)), DemoScope.AUDIT_READ))
        assertFalse(authority.authorize(employee.copy(scopes = employee.scopes + DemoScope.AUDIT_READ), DemoScope.AUDIT_READ))
        assertFalse(authority.authorize(employee.copy(mockToken = "invalid"), DemoScope.LIBRARY_READ))
    }
    @Test fun readonlySecurityCannotWriteAndChangingRoleRevokesOldAdmin() {
        val authority = MockRoleAuthority()
        val admin = authority.issue(DemoRole.ADMIN)
        assertTrue(authority.authorize(admin, DemoScope.AUDIT_READ))
        val security = authority.issue(DemoRole.SECURITY)
        assertFalse(authority.authorize(admin, DemoScope.AUDIT_READ))
        assertTrue(authority.authorize(security, DemoScope.SCAN_TRACK))
        assertFalse(authority.authorize(security, DemoScope.LIBRARY_WRITE))
    }
    @Test fun grantExpiresAtFiveMinutes() {
        var now = 1000L
        val authority = MockRoleAuthority { now }
        val employee = authority.issue(DemoRole.EMPLOYEE)
        now += 300_000
        assertFalse(authority.authorize(employee, DemoScope.LIBRARY_READ))
    }
}
