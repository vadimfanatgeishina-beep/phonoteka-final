package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

// JVM-заглушки с теми же простыми именами, что у android.security.keystore.*
private class KeyPermanentlyInvalidatedException : java.security.InvalidKeyException()
private class UserNotAuthenticatedException : java.security.InvalidKeyException()

class KeyLossPolicyTest {
    @Test fun classifiesKeystoreFailures() {
        assertEquals(KeyStoreFailure.KEY_INVALIDATED, KeyLossPolicy.classify(KeyPermanentlyInvalidatedException()))
        assertEquals(KeyStoreFailure.LOCKED, KeyLossPolicy.classify(UserNotAuthenticatedException()))
        assertEquals(KeyStoreFailure.CORRUPT_BLOB, KeyLossPolicy.classify(AEADBadTagException()))
        assertEquals(KeyStoreFailure.KEY_MISSING, KeyLossPolicy.classify(java.security.UnrecoverableKeyException()))
        assertEquals(KeyStoreFailure.KEY_MISSING, KeyLossPolicy.classify(KeyMissingException()))
        assertEquals(KeyStoreFailure.CORRUPT_BLOB, KeyLossPolicy.classify(InvalidBlobException()))
        assertEquals(KeyStoreFailure.CORRUPT_BLOB, KeyLossPolicy.classify(IllegalArgumentException("bad base64")))
        assertEquals(KeyStoreFailure.KEY_INVALIDATED, KeyLossPolicy.classify(KeyLostException(KeyStoreFailure.KEY_INVALIDATED)))
    }

    @Test fun unknownProviderErrorIsTransient_andNotDestructive() {
        val kind = KeyLossPolicy.classify(java.security.ProviderException("keystore busy"))
        assertEquals(KeyStoreFailure.TRANSIENT, kind)
        assertEquals(KeyRecoveryAction.KEEP_AND_RETRY, KeyLossPolicy.action(kind))
        assertEquals(KeyRecoveryAction.KEEP_AND_AUTHENTICATE, KeyLossPolicy.action(KeyStoreFailure.LOCKED))
    }

    @Test fun wrappedCauseIsFound() {
        val wrapped = java.security.ProviderException("wrap", KeyPermanentlyInvalidatedException())
        assertEquals(KeyStoreFailure.KEY_INVALIDATED, KeyLossPolicy.classify(wrapped))
    }

    @Test fun destructiveActionsOnlyForUnrecoverable() {
        assertEquals(KeyRecoveryAction.DELETE_KEY_AND_BLOB_RELOGIN, KeyLossPolicy.action(KeyStoreFailure.KEY_INVALIDATED))
        assertEquals(KeyRecoveryAction.DELETE_BLOB_RELOGIN, KeyLossPolicy.action(KeyStoreFailure.KEY_MISSING))
        assertEquals(KeyRecoveryAction.DELETE_BLOB_RELOGIN, KeyLossPolicy.action(KeyStoreFailure.CORRUPT_BLOB))
    }

    @Test fun journalKeepsOnlyRealLoss_andSanitizesSource() {
        val j = InMemoryKeyLossJournal { 7L }
        j.record(KeyStoreFailure.LOCKED, "x"); j.record(KeyStoreFailure.TRANSIENT, "x")
        assertNull(j.peek())
        j.record(KeyStoreFailure.KEY_INVALIDATED, "refresh-store token=abc/")
        val n = j.peek()!!
        assertEquals(KeyStoreFailure.KEY_INVALIDATED, n.failure)
        assertTrue(n.source.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(n.message.contains("войдите заново"))
        j.acknowledge(); assertNull(j.peek())
    }

    @Test fun messagesNeverEmpty() {
        KeyStoreFailure.entries.forEach { assertTrue(KeyLossPolicy.userMessage(it).isNotBlank()) }
    }
}
