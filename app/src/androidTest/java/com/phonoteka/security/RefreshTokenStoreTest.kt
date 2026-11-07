package com.phonoteka.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshTokenStoreTest {
    @Test fun refreshTokenIsEncryptedAtRestAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidKeystoreRefreshTokenStore(context, requireSystemAuthentication = false)
        val token = "test-only-refresh-token-never-a-real-credential"
        store.clear()
        store.write(token)
        assertEquals(token, store.read())
        val encoded = context.getSharedPreferences("phonoteka_lab13_refresh_store_v2.instrumentation-fixture", 0)
            .getString("encrypted_refresh_token", "").orEmpty()
        assertFalse(encoded.contains(token))
        store.clear()
        assertEquals(null, store.read())
    }
}
