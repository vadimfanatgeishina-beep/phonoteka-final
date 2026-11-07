package com.phonoteka.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * ЛР13 на устройстве: повреждённый блоб в настоящих SharedPreferences + AndroidKeyStore.
 * Изолированное пространство fixture, журнал в памяти — данные приложения не затрагиваются.
 */
@RunWith(AndroidJUnit4::class)
class RefreshStoreKeyLossDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "keyloss-" + UUID.randomUUID().toString().take(8)
    private val journal = InMemoryKeyLossJournal()
    private val store = AndroidKeystoreRefreshTokenStore(context, requireSystemAuthentication = false,
        fixtureNamespace = namespace, keyLoss = journal)
    private val prefs = context.getSharedPreferences("phonoteka_lab13_refresh_store_v2.fixture.$namespace", 0)

    @After fun cleanup() { store.clear(); context.deleteSharedPreferences("phonoteka_lab13_refresh_store_v2.fixture.$namespace") }

    @Test fun roundTripDoesNotRecordKeyLoss() {
        val token = "a".repeat(64)
        store.write(token)
        assertEquals(token, store.read())
        assertNull(journal.peek())
    }

    @Test fun corruptBlobIsDeletedAndRecordedForLoginScreen() {
        store.write("b".repeat(64))
        assertTrue(prefs.edit().putString("encrypted_refresh_token", "!!!not-base64!!!").commit())
        assertNull(store.read())
        assertEquals(KeyStoreFailure.CORRUPT_BLOB, journal.peek()?.failure)
        assertNull(prefs.getString("encrypted_refresh_token", null))
    }

    @Test fun tamperedCiphertextIsDetectedByGcm() {
        store.write("c".repeat(64))
        val encoded = prefs.getString("encrypted_refresh_token", null)!!
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertTrue(prefs.edit().putString("encrypted_refresh_token", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit())
        assertNull(store.read())
        assertEquals(KeyStoreFailure.CORRUPT_BLOB, journal.peek()?.failure)
    }
}
