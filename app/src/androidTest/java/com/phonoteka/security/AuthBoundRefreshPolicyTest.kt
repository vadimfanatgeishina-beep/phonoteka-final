package com.phonoteka.security
import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyStore
import javax.crypto.SecretKeyFactory

class AuthBoundRefreshPolicyTest {
    @Test fun productionRefreshKeyRequiresSystemAuthentication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Secure Android screen lock is required", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure)
        val namespace = java.util.UUID.randomUUID().toString()
        val alias = "phonoteka.lab13.refresh.aes.auth-bound.v2.fixture.$namespace"
        val store = AndroidKeystoreRefreshTokenStore(context, requireSystemAuthentication = true, fixtureNamespace = namespace)
        // Requires recent system authentication; UserNotAuthenticatedException is a failed precondition,
        // never silently accepted as a round-trip success. Use the app's system-confirmation flow first.
        val value = java.util.UUID.randomUUID().toString()
        try {
            store.write(value)
            assertEquals(value, store.read())
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
            val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key as javax.crypto.SecretKey, KeyInfo::class.java) as KeyInfo
            assertTrue(info.isUserAuthenticationRequired)
            assertEquals(300, info.userAuthenticationValidityDurationSeconds)
        } finally {
            store.clear()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
