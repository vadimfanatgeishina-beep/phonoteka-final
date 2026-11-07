package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockAuthSessionTest {
    private class MemoryState : MockAuthStateStore {
        val strings = mutableMapOf<String, String>()
        val longs = mutableMapOf<String, Long>()
        override fun getString(key: String) = strings[key]
        override fun getLong(key: String, defaultValue: Long) = longs[key] ?: defaultValue
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
        override fun remove(key: String) { strings.remove(key); longs.remove(key) }
    }
    private open class MemoryRefreshStore : RefreshTokenStore {
        var value: String? = null
        override fun read() = value
        override fun write(refreshToken: String) { value = refreshToken }
        override fun clear() { value = null }
    }

    @Test fun lockedWritePreflightDoesNotConsumeRefreshOrLoseCiphertext() {
        var now = 100_000L
        val server = LocalMockAuthEndpoint(MemoryState(), { now }, accessTtlMillis = 60_000)
        var rotations = 0
        val endpoint = object : MockAuthEndpoint by server {
            override fun refresh(refreshToken: String): MockTokenPair? { rotations++; return server.refresh(refreshToken) }
        }
        val store = object : MemoryRefreshStore() {
            var locked = false
            override fun prepareWrite() { if (locked) throw SecurityException("owned locked-key fixture") }
        }
        val client = MockSessionRepository(endpoint, store, { now })
        client.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD)
        val before = store.read()
        now += 60_001
        store.locked = true
        var rejected = false
        try { client.accessToken() } catch (_: SecurityException) { rejected = true }
        assertTrue(rejected)
        assertEquals(0, rotations)
        assertEquals(before, store.read())
        store.locked = false
        assertNotNull(client.accessToken())
        assertEquals(1, rotations)
        assertNotEquals(before, store.read())
    }

    @Test fun defaultLockoutLastsFiveMinutesAndSurvivesEndpointRecreation() {
        var now = 100_000L
        val state = MemoryState()
        val server = LocalMockAuthEndpoint(state, { now })
        repeat(3) { assertEquals(MockLoginResult.InvalidCredentials, server.login(LocalMockAuthEndpoint.DEMO_USER, "wrong")) }
        assertEquals(MockLoginResult.Locked(300_000), server.login(LocalMockAuthEndpoint.DEMO_USER, "wrong"))
        val recreated = LocalMockAuthEndpoint(state, { now })
        now += 299_999
        assertEquals(MockLoginResult.Locked(1), recreated.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD))
        now++
        assertTrue(recreated.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD) is MockLoginResult.Success)
    }

    @Test fun ledgerContainsOnlyDigestsAndRotatedTokenCannotBeReused() {
        val state = MemoryState()
        val server = LocalMockAuthEndpoint(state)
        val initial = (server.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD) as MockLoginResult.Success).tokens
        val replacement = server.refresh(initial.refreshToken)!!
        val persisted = (state.strings.keys + state.strings.values + state.longs.keys).joinToString(";")
        listOf(initial.accessToken, initial.refreshToken, replacement.accessToken, replacement.refreshToken).forEach {
            assertTrue("No bearer token may appear in mock ledger", !persisted.contains(it))
        }
        assertNull(server.refresh(initial.refreshToken))
        server.revoke(replacement.refreshToken)
        assertNull(server.refresh(replacement.refreshToken))
    }

    @Test fun rotationWriteFailureRevokesReplacementAndRequiresFreshLogin() {
        for (writeBeforeThrow in listOf(false, true)) {
            var now = 100_000L
            val server = LocalMockAuthEndpoint(MemoryState(), { now }, accessTtlMillis = 60_000)
            var issuedRefresh: String? = null
            val endpoint = object : MockAuthEndpoint by server {
                override fun refresh(refreshToken: String): MockTokenPair? = server.refresh(refreshToken).also {
                    issuedRefresh = it?.refreshToken
                }
            }
            val store = object : MemoryRefreshStore() {
                var fail = false
                override fun write(refreshToken: String) {
                    if (!fail || writeBeforeThrow) super.write(refreshToken)
                    if (fail) throw java.io.IOException("simulated persistence failure")
                }
            }
            val client = MockSessionRepository(endpoint, store, { now })
            client.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD)
            val old = store.read()!!
            store.fail = true
            now += 60_001
            var failed = false
            try { client.accessToken() } catch (_: MockSessionPersistenceException) { failed = true }
            assertTrue("Persistence failure must reach the caller", failed)
            assertNull(store.read())
            assertNull(client.accessToken())
            assertNull(server.refresh(old))
            assertNull(server.refresh(issuedRefresh!!))
            assertNull(MockSessionRepository(endpoint, store, { now }).accessToken())
            store.fail = false
            assertTrue(client.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD) is MockLoginResult.Success)
        }
    }

    @Test fun failedCleanupStillCannotRestoreTheAlreadyConsumedRefresh() {
        var now = 100_000L
        val server = LocalMockAuthEndpoint(MemoryState(), { now }, accessTtlMillis = 60_000)
        val store = object : MemoryRefreshStore() {
            var failWrite = false
            var failClear = false
            override fun write(refreshToken: String) {
                if (failWrite) throw java.io.IOException("injected write failure")
                super.write(refreshToken)
            }
            override fun clear() {
                if (failClear) throw java.io.IOException("injected clear failure")
                super.clear()
            }
        }
        val client = MockSessionRepository(server, store, { now })
        client.login(LocalMockAuthEndpoint.DEMO_USER, LocalMockAuthEndpoint.DEMO_PASSWORD)
        val consumed = store.read()!!
        now += 60_001
        store.failWrite = true
        store.failClear = true
        var failure: MockSessionPersistenceException? = null
        try { client.accessToken() } catch (error: MockSessionPersistenceException) { failure = error }
        assertNotNull(failure)
        assertEquals(1, failure!!.cause!!.suppressed.size)
        assertEquals(consumed, store.read()) // Cleanup really failed; a stale value is still on disk.
        assertNull(server.refresh(consumed))
        store.failClear = false
        assertNull(MockSessionRepository(server, store, { now }).accessToken())
        assertNull(store.read())
    }

    @Test fun fourthBadLoginLocksAndCorrectLoginWaitsForTimeout() {
        var now = 1_800_000_000_000L
        val server = LocalMockAuthEndpoint(MemoryState(), { now }, lockoutMillis = 30_000L)
        repeat(3) { assertEquals(MockLoginResult.InvalidCredentials, server.login("student", "wrong")) }
        assertTrue(server.login("student", "wrong") is MockLoginResult.Locked)
        assertTrue(server.login("student", LocalMockAuthEndpoint.DEMO_PASSWORD) is MockLoginResult.Locked)
        now += 30_001L
        assertTrue(server.login("student", LocalMockAuthEndpoint.DEMO_PASSWORD) is MockLoginResult.Success)
    }

    @Test fun recreatedClientRefreshesRotatesRejectsReplayAndLogoutRevokes() {
        var now = 1_800_000_000_000L
        val serverState = MemoryState()
        val server = LocalMockAuthEndpoint(serverState, { now }, accessTtlMillis = 1_000L)
        val refreshStore = MemoryRefreshStore()
        val firstClient = MockSessionRepository(server, refreshStore, { now })
        assertTrue(firstClient.login("student", LocalMockAuthEndpoint.DEMO_PASSWORD) is MockLoginResult.Success)
        val initialRefresh = refreshStore.read()!!
        now += 1_001L

        // Models an app-process restart: client memory is new; the mock endpoint ledger survives.
        val recreatedClient = MockSessionRepository(LocalMockAuthEndpoint(serverState, { now }, accessTtlMillis = 1_000L), refreshStore, { now })
        val refreshedAccess = recreatedClient.accessToken()
        assertNotNull(refreshedAccess)
        val rotatedRefresh = refreshStore.read()!!
        assertNotEquals(initialRefresh, rotatedRefresh)
        assertNull(server.refresh(initialRefresh))

        recreatedClient.logout()
        assertNull(refreshStore.read())
        assertNull(recreatedClient.accessToken())
    }
}
