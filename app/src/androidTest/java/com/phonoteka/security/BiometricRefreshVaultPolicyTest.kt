package com.phonoteka.security

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Policy/negative test, NOT a successful BiometricPrompt roundtrip. Use a secure owned device. */
class BiometricRefreshVaultPolicyTest {
    @Test fun freshKeyIs256BitPerUseAuthBoundAndUnauthorizedCipherCannotWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Secure own Android required", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure)
        assumeTrue("Enrolled strong biometric required", androidx.biometric.BiometricManager.from(context)
            .canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS)
        val namespace = java.util.UUID.randomUUID().toString()
        val alias = "phonoteka.lab13.biometric-refresh.per-use.v1.fixture.$namespace"
        val vault = AndroidBiometricRefreshVault(context, namespace)
        val request = vault.prepareEnrollment("a".repeat(64))
        try {
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
            val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertEquals(256, info.keySize); assertTrue(info.isUserAuthenticationRequired)
            assertTrue(info.userAuthenticationValidityDurationSeconds <= 0)
            assertTrue(info.isInvalidatedByBiometricEnrollment)
            try { request.finish(request.cipher); fail("Unauthenticated per-use cipher wrote blob") }
            catch (_: android.security.keystore.UserNotAuthenticatedException) {}
            assertFalse(vault.hasSavedRefresh())
        } finally { vault.clear(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}
