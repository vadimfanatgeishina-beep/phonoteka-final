package com.phonoteka.security

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** JVM policy tests only. These do NOT simulate Android biometric/key authorization. */
class BiometricSessionFlowTest {
    private class State : MockAuthStateStore {
        val strings = mutableMapOf<String, String>(); val longs = mutableMapOf<String, Long>()
        override fun getString(key: String) = strings[key]
        override fun getLong(key: String, defaultValue: Long) = longs[key] ?: defaultValue
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
        override fun remove(key: String) { strings.remove(key); longs.remove(key) }
    }
    private class Store : RefreshTokenStore {
        var value: String? = null; var locked = false; var failWrite = false
        override fun prepareWrite() { check(!locked) { "Owned fixture locked" } }
        override fun read() = value
        override fun write(refreshToken: String) { value = refreshToken; check(!failWrite) { "Owned write failure" } }
        override fun clear() { value = null }
    }
    private fun cipher() = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"))
    }
    private class Vault : BiometricRefreshVault {
        var saved: String? = null; var finishes = 0; var clears = 0; var failClear = false
        override fun hasSavedRefresh() = saved != null
        override fun prepareEnrollment(refresh: String) = BiometricCryptoRequest(Cipher.getInstance("AES/GCM/NoPadding")) {
            finishes++; saved = refresh; null
        }
        override fun prepareRestore(): BiometricCryptoRequest {
            val captured = checkNotNull(saved)
            return BiometricCryptoRequest(Cipher.getInstance("AES/GCM/NoPadding")) { finishes++; clear(); captured }
        }
        override fun clear() { check(!failClear); clears++; saved = null }
    }
    private class Fixture {
        var wall = 1_000L; var elapsed = 0L
        val state = State(); val store = Store(); val vault = Vault()
        val endpoint = LocalMockAuthEndpoint(state, { wall })
        val sessions = MockSessionRepository(endpoint, store, { wall }, vault::clear)
        val flow = BiometricSessionFlow(sessions, vault) { elapsed }
        fun login() { check(sessions.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD) is MockLoginResult.Success) }
        fun enroll() { val t = flow.prepare(BiometricSessionAction.ENROLL); flow.complete(t, t.cipher) }
        fun enrolled() { login(); enroll(); sessions.forgetAccessSession() }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Operation was accepted") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
    }
    @Test fun enrollmentSealsCurrentRefreshNotConstantProof() {
        val f = Fixture(); f.login(); f.enroll(); assertTrue(f.vault.saved == f.store.value); assertEquals(1, f.vault.finishes)
    }
    @Test fun actualRestoreRotatesTokenAndReissuesMemoryOnlyAccess() {
        val f = Fixture(); f.enrolled(); val old = f.store.value
        val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        assertEquals(BiometricSessionAction.RESTORE, f.flow.complete(t, t.cipher))
        assertNotNull(f.sessions.accessToken()); assertTrue(old != f.store.value)
        assertNull(f.endpoint.refresh(checkNotNull(old))); assertFalse(f.vault.hasSavedRefresh())
    }
    @Test fun replacementRequiresSeparateEnrollmentAndUsesRotatedRefresh() {
        val f = Fixture(); f.enrolled(); val old = f.vault.saved
        val t = f.flow.prepare(BiometricSessionAction.RESTORE); f.flow.complete(t, t.cipher)
        f.enroll(); assertTrue(f.vault.saved != old && f.vault.saved == f.store.value)
    }
    @Test fun wrongCipherCannotConsumeVaultAndTicketCannotRetry() {
        val f = Fixture(); f.enrolled(); val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        rejected { f.flow.complete(t, cipher()) }; rejected { f.flow.complete(t, t.cipher) }
        assertEquals(1, f.vault.finishes); assertNotNull(f.vault.saved)
    }
    @Test fun missingCryptoObjectDoesNotGrantAccess() {
        val f = Fixture(); f.enrolled(); val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        rejected { f.flow.complete(t, null) }; assertNotNull(f.vault.saved)
    }
    @Test fun cancelRejectsLateSuccessfulCallback() {
        val f = Fixture(); f.enrolled(); val t = f.flow.prepare(BiometricSessionAction.RESTORE); f.flow.cancel()
        rejected { f.flow.complete(t, t.cipher) }; assertNotNull(f.vault.saved)
    }
    @Test fun timeoutBoundaryRejectsWithoutConsumingRefresh() {
        val f = Fixture(); f.enrolled(); val t = f.flow.prepare(BiometricSessionAction.RESTORE); f.elapsed = 60_000
        rejected { f.flow.complete(t, t.cipher) }; assertNotNull(f.vault.saved)
    }
    @Test fun elapsedClockRollbackIsRejected() {
        val f = Fixture(); f.elapsed = 100; f.enrolled(); val t = f.flow.prepare(BiometricSessionAction.RESTORE); f.elapsed = 99
        rejected { f.flow.complete(t, t.cipher) }; assertNotNull(f.vault.saved)
    }
    @Test fun duplicateCallbackCannotPerformSecondRotation() {
        val f = Fixture(); f.enrolled(); val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        f.flow.complete(t, t.cipher); val revision = f.sessions.sessionRevision()
        rejected { f.flow.complete(t, t.cipher) }; assertEquals(revision, f.sessions.sessionRevision())
    }
    @Test fun newerPromptInvalidatesOldTicket() {
        val f = Fixture(); f.enrolled(); val old = f.flow.prepare(BiometricSessionAction.RESTORE)
        val newer = f.flow.prepare(BiometricSessionAction.RESTORE)
        rejected { f.flow.complete(old, old.cipher) }; f.flow.complete(newer, newer.cipher)
    }
    @Test fun logoutDuringPromptCannotResurrectSession() {
        val f = Fixture(); f.enrolled(); val t = f.flow.prepare(BiometricSessionAction.RESTORE); f.sessions.logout()
        rejected { f.flow.complete(t, t.cipher) }; assertNull(f.store.value); assertFalse(f.vault.hasSavedRefresh())
    }
    @Test fun revokedRefreshNeverBecomesFreshLocalAuthentication() {
        val f = Fixture(); f.enrolled(); f.endpoint.revoke(checkNotNull(f.store.value))
        val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        rejected { f.flow.complete(t, t.cipher) }; assertNull(f.store.value); assertNull(f.sessions.accessToken())
    }
    @Test fun expiredRefreshIsClearedWithoutNewSession() {
        val f = Fixture(); f.enrolled(); f.wall += 8 * 24 * 60 * 60_000L
        val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        rejected { f.flow.complete(t, t.cipher) }; assertNull(f.store.value)
    }
    @Test fun mismatchedCredentialAndBiometricTokensDoNotConsumeEndpointToken() {
        val f = Fixture(); f.enrolled(); val old = checkNotNull(f.store.value); f.store.value = "f".repeat(64)
        val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        rejected { f.flow.complete(t, t.cipher) }; assertNotNull(f.endpoint.refresh(old))
    }
    @Test fun lockedFallbackWriteKeyDoesNotConsumeEndpointToken() {
        val f = Fixture(); f.enrolled(); val old = checkNotNull(f.store.value); f.store.locked = true
        val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        rejected { f.flow.complete(t, t.cipher) }; assertNotNull(f.endpoint.refresh(old))
    }
    @Test fun writeAfterRotationFailureRevokesBothAndClearsAll() {
        val f = Fixture(); f.enrolled(); val old = checkNotNull(f.store.value); f.store.failWrite = true
        val t = f.flow.prepare(BiometricSessionAction.RESTORE)
        rejected { f.flow.complete(t, t.cipher) }; assertNull(f.store.value); assertFalse(f.vault.hasSavedRefresh())
        assertNull(f.endpoint.refresh(old)); assertFalse(f.state.strings.keys.any { it.startsWith("refresh:") })
    }
    @Test fun normalRefreshInvalidatesBiometricSnapshotBeforeRotation() {
        val f = Fixture(); f.login(); f.enroll(); f.wall += 60_000
        assertNotNull(f.sessions.accessToken()); assertFalse(f.vault.hasSavedRefresh())
    }
    @Test fun clearFailureAbortsNormalRefreshBeforeEndpointConsume() {
        val f = Fixture(); f.login(); f.enroll(); val old = checkNotNull(f.store.value)
        f.wall += 60_000; f.vault.failClear = true
        rejected { f.sessions.accessToken() }; assertNotNull(f.endpoint.refresh(old))
    }
    @Test fun freshLoginInvalidatesOldBiometricSnapshot() {
        val f = Fixture(); f.login(); f.enroll(); val old = f.store.value; f.login()
        assertFalse(f.vault.hasSavedRefresh()); assertTrue(old != f.store.value)
    }
    @Test fun noSessionCannotBeEnrolled() {
        val f = Fixture(); rejected { f.flow.prepare(BiometricSessionAction.ENROLL) }; assertFalse(f.vault.hasSavedRefresh())
    }
    @Test fun logoutClearFailureStillRevokesRefreshAndClearsCredentialStore() {
        val f = Fixture(); f.login(); f.enroll(); val old = checkNotNull(f.store.value)
        f.vault.failClear = true
        rejected { f.sessions.logout() }
        assertNull(f.store.value); assertNull(f.endpoint.refresh(old))
        assertNull(f.sessions.accessToken())
    }
    @Test fun lateEnrollmentAfterLockCannotWriteBiometricSnapshot() {
        val f = Fixture(); f.login(); val t = f.flow.prepare(BiometricSessionAction.ENROLL)
        f.sessions.forgetAccessSession()
        rejected { f.flow.complete(t, t.cipher) }; assertFalse(f.vault.hasSavedRefresh())
    }

}
