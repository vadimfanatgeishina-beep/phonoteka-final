package com.phonoteka.identity

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class PhoneIdentityAuthorityTest {
    private class Store : IdentitySnapshotStore {
        var bytes: ByteArray? = null
        var failSave = false
        override fun load(): ByteArray? = bytes?.copyOf()
        override fun save(snapshot: ByteArray) {
            if (failSave) throw IllegalStateException("I/O error")
            bytes = snapshot.copyOf()
        }
    }
    private class Fixture {
        val store = Store()
        var time = 1_700_000_000_000L
        val clock = IdentityClock { time }
        val entropy = IdentityEntropy { SecureRandom().nextBytes(it) }
        fun authority(log: (IdentityEvent) -> Unit = {}) = PhoneIdentityAuthority(store, clock, entropy, log)
        fun owner(a: PhoneIdentityAuthority): IdentityTokens {
            a.bootstrapOwner("owner", "valid-owner-pass")
            return a.login("owner", "valid-owner-pass", "owner phone")
        }
    }
    private fun denied(reason: IdentityException.Reason, block: () -> Any?) {
        try { block(); fail("Expected $reason") }
        catch (e: IdentityException) { assertEquals(reason, e.reason) }
    }

    @Test fun restartKeepsAccountsAndSessions() {
        val f = Fixture(); val a = f.authority(); val token = f.owner(a)
        val original = a.currentUser(token.access)
        val restarted = f.authority()
        assertEquals(original, restarted.currentUser(token.access))
        denied(IdentityException.Reason.CONFLICT) { restarted.bootstrapOwner("another", "another-long-password") }
    }

    @Test fun invalidPasswordsUnknownLoginAndPersistentLock() {
        val f = Fixture(); val a = f.authority(); f.owner(a)
        denied(IdentityException.Reason.INVALID_CREDENTIALS) { a.login("ghost", "wrong-password") }
        for (i in 1..5) denied(IdentityException.Reason.INVALID_CREDENTIALS) { a.login("owner", "wrong-password") }
        denied(IdentityException.Reason.LOCKED) { f.authority().login("owner", "valid-owner-pass") }
        f.time += PhoneIdentityAuthority.LOCK_MS + 1
        val fresh = f.authority()
        val token = fresh.login("owner", "valid-owner-pass")
        assertEquals(IdentityRole.ADMIN, f.authority().currentUser(token.access).role)
    }

    @Test fun serverControlsRoleAndScopes() {
        val f = Fixture(); val a = f.authority(); val admin = f.owner(a)
        a.createUser(admin.access, "worker", "worker-secret-password", IdentityRole.EMPLOYEE)
        val worker = a.login("worker", "worker-secret-password")
        assertEquals(IdentityRole.EMPLOYEE, a.currentUser(worker.access).role)
        assertFalse(IdentityScope.AUDIT_READ in a.currentUser(worker.access).scopes)
        denied(IdentityException.Reason.FORBIDDEN) { a.createUser(worker.access, "intruder", "intruder-password", IdentityRole.ADMIN) }
        denied(IdentityException.Reason.FORBIDDEN) { a.authorize(worker.access, IdentityScope.INVITE_CREATE) }
        assertEquals(IdentityRole.ADMIN, a.authorize(admin.access, IdentityScope.INVITE_CREATE).role)
    }

    @Test fun accessExpiryAndRefreshExpiry() {
        val f = Fixture(); val a = f.authority(); val token = f.owner(a)
        f.time += PhoneIdentityAuthority.ACCESS_TTL_MS
        denied(IdentityException.Reason.UNAUTHORIZED) { a.currentUser(token.access) }
        val newTokens = a.refresh(token.refresh)
        assertEquals(IdentityRole.ADMIN, a.currentUser(newTokens.access).role)
        f.time = newTokens.refreshExpiresAt
        denied(IdentityException.Reason.UNAUTHORIZED) { a.refresh(newTokens.refresh) }
    }

    @Test fun replayRevokesRotatedFamily() {
        val f = Fixture(); val a = f.authority(); val first = f.owner(a)
        val second = a.refresh(first.refresh)
        denied(IdentityException.Reason.UNAUTHORIZED) { a.currentUser(first.access) }
        denied(IdentityException.Reason.UNAUTHORIZED) { a.refresh(first.refresh) }
        denied(IdentityException.Reason.UNAUTHORIZED) { a.currentUser(second.access) }
        denied(IdentityException.Reason.UNAUTHORIZED) { f.authority().refresh(second.refresh) }
    }

    @Test fun logoutRevokesBothTokens() {
        val f = Fixture(); val a = f.authority(); val t = f.owner(a)
        a.logout(t.access)
        denied(IdentityException.Reason.UNAUTHORIZED) { a.currentUser(t.access) }
        denied(IdentityException.Reason.UNAUTHORIZED) { a.refresh(t.refresh) }
    }

    @Test fun changingRoleRevokesSessionsAndPersistsRole() {
        val f = Fixture(); val a = f.authority(); val admin = f.owner(a)
        val user = a.createUser(admin.access, "guard", "secure-guard-password", IdentityRole.SECURITY)
        val previous = a.login("guard", "secure-guard-password")
        a.changeRole(admin.access, user.id, IdentityRole.EMPLOYEE)
        denied(IdentityException.Reason.UNAUTHORIZED) { a.currentUser(previous.access) }
        denied(IdentityException.Reason.UNAUTHORIZED) { a.refresh(previous.refresh) }
        val newToken = a.login("guard", "secure-guard-password")
        assertEquals(IdentityRole.EMPLOYEE, f.authority().currentUser(newToken.access).role)
    }

    @Test fun foreignSessionCannotBeRevoked() {
        val f = Fixture(); val a = f.authority(); val admin = f.owner(a)
        a.createUser(admin.access, "other", "another-secret-password", IdentityRole.EMPLOYEE)
        val other = a.login("other", "another-secret-password")
        val victimId = a.listSessions(other.access).single().id
        denied(IdentityException.Reason.NOT_FOUND) { a.revokeOwnSession(admin.access, victimId) }
        assertEquals(IdentityRole.EMPLOYEE, a.currentUser(other.access).role)
        a.revokeOwnSession(other.access, victimId)
        denied(IdentityException.Reason.UNAUTHORIZED) { a.currentUser(other.access) }
    }

    @Test fun failingSavePreservesStateAndNeverReturnsNewTokens() {
        val f = Fixture(); val a = f.authority(); val admin = f.owner(a)
        val before = f.store.bytes!!.copyOf()
        f.store.failSave = true
        try { a.refresh(admin.refresh); fail("expected save failure") } catch (_: IllegalStateException) { }
        assertArrayEquals(before, f.store.bytes!!)
        assertEquals(IdentityRole.ADMIN, a.currentUser(admin.access).role)
        try { a.createUser(admin.access, "new", "another-new-password", IdentityRole.ADMIN); fail("expected save failure") }
        catch (_: IllegalStateException) { }
        f.store.failSave = false
        denied(IdentityException.Reason.INVALID_CREDENTIALS) { a.login("new", "another-new-password") }
        val refreshed = a.refresh(admin.refresh)
        assertEquals(IdentityRole.ADMIN, f.authority().currentUser(refreshed.access).role)
    }

    @Test fun corruptedSnapshotFailsClosed() {
        val f = Fixture(); val a = f.authority(); val original = f.owner(a)
        val valid = f.store.bytes!!.copyOf()
        f.store.bytes = valid.copyOf().also { it[0] = 0 }
        denied(IdentityException.Reason.INVALID_SNAPSHOT) { f.authority() }
        f.store.bytes = valid
        assertEquals(IdentityRole.ADMIN, f.authority().currentUser(original.access).role)
    }

    @Test fun passwordChangeRevokesAllSessionsAndAuditFailuresAreNonFatal() {
        val f = Fixture(); val a = f.authority { throw IllegalStateException("audit offline") }; val first = f.owner(a)
        val second = a.login("owner", "valid-owner-pass")
        a.changePassword(first.access, "valid-owner-pass", "replacement-password")
        denied(IdentityException.Reason.UNAUTHORIZED) { a.currentUser(second.access) }
        denied(IdentityException.Reason.UNAUTHORIZED) { a.refresh(first.refresh) }
        denied(IdentityException.Reason.INVALID_CREDENTIALS) { a.login("owner", "valid-owner-pass") }
        assertEquals(IdentityRole.ADMIN, a.currentUser(a.login("owner", "replacement-password").access).role)
    }

    @Test fun snapshotDoesNotContainPlaintextSecrets() {
        val f = Fixture(); val a = f.authority(); val t = f.owner(a)
        val raw = f.store.bytes!!.toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(t.access)); assertFalse(raw.contains(t.refresh))
        assertFalse(raw.contains("valid-owner-pass")); assertFalse(t.toString().contains(t.access))
    }
}
