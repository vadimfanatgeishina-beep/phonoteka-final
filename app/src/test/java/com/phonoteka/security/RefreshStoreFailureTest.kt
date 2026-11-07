package com.phonoteka.security

import org.junit.Assert.*
import org.junit.Test

/** ЛР13: поведение MockSessionRepository, когда хранилище refresh временно недоступно. */
class RefreshStoreFailureTest {
    // Имя класса совпадает с Android-исключением: KeyLossPolicy классифицирует по simpleName.
    private class UserNotAuthenticatedException : java.security.InvalidKeyException("fixture locked")

    private class MemoryState : MockAuthStateStore {
        val strings = mutableMapOf<String, String>(); val longs = mutableMapOf<String, Long>()
        override fun getString(key: String) = strings[key]
        override fun getLong(key: String, defaultValue: Long) = longs[key] ?: defaultValue
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
        override fun remove(key: String) { strings.remove(key); longs.remove(key) }
    }
    private class FlakyStore : RefreshTokenStore {
        var value: String? = null
        var failure: Exception? = null
        override fun read(): String? { failure?.let { throw it }; return value }
        override fun write(refreshToken: String) { value = refreshToken }
        override fun clear() { value = null }
    }

    private var now = 100_000L
    private val state = MemoryState()
    private fun endpoint() = LocalMockAuthEndpoint(state, { now }, accessTtlMillis = 60_000)
    private fun loggedIn(store: FlakyStore): String {
        MockSessionRepository(endpoint(), store, { now }).login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD)
        return store.value!!
    }

    @Test fun lockedStoreOnNewProcessKeepsTokenAndExplainsUnlock() {
        val store = FlakyStore(); val saved = loggedIn(store)
        store.failure = UserNotAuthenticatedException()
        val fresh = MockSessionRepository(endpoint(), store, { now }) // новый процесс: в памяти ничего нет
        val error = assertThrows(KeyLostException::class.java) { fresh.accessToken() }
        assertEquals(KeyStoreFailure.LOCKED, error.failure)
        assertEquals(saved, store.value) // токен не стёрт
        store.failure = null
        assertNotNull(fresh.accessToken()) // после разблокировки сессия восстанавливается: refresh не был израсходован
    }

    @Test fun transientStoreFailureIsRetryableAndNotDestructive() {
        val store = FlakyStore(); val saved = loggedIn(store)
        store.failure = java.io.IOException("fixture transient")
        val fresh = MockSessionRepository(endpoint(), store, { now })
        assertEquals(KeyStoreFailure.TRANSIENT, assertThrows(KeyLostException::class.java) { fresh.accessToken() }.failure)
        assertEquals(saved, store.value)
        store.failure = null
        assertNotNull(fresh.accessToken())
    }

    @Test fun loginWithLockedStoreFailsBeforeContactingEndpoint() {
        val store = FlakyStore(); loggedIn(store)
        store.failure = UserNotAuthenticatedException()
        var logins = 0
        val server = endpoint()
        val counting = object : MockAuthEndpoint by server {
            override fun login(username: String, password: String): MockLoginResult { logins++; return server.login(username, password) }
        }
        val fresh = MockSessionRepository(counting, store, { now })
        assertThrows(KeyLostException::class.java) { fresh.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD) }
        assertEquals(0, logins)
    }

    @Test fun logoutWithLockedStoreStillClearsLocalSessionAndReportsFailure() {
        val store = FlakyStore(); loggedIn(store)
        store.failure = UserNotAuthenticatedException()
        val fresh = MockSessionRepository(endpoint(), store, { now })
        val error = assertThrows(MockSessionPersistenceException::class.java) { fresh.logout() }
        assertTrue(error.cause is KeyLostException)
        assertNull(store.value) // локальные данные всё равно удалены
    }

    @Test fun corruptBlobIsNotWrappedAsRetryable() {
        val store = FlakyStore(); loggedIn(store)
        store.failure = IllegalStateException("other failure", javax.crypto.AEADBadTagException())
        val fresh = MockSessionRepository(endpoint(), store, { now })
        val error: Throwable = assertThrows(IllegalStateException::class.java) { fresh.accessToken() }
        assertFalse(error is KeyLostException)
    }
}
