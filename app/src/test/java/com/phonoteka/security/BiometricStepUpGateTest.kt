package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricStepUpGateTest {
    private var now = 1_000L
    private var nextNonce = 41L
    private val gate = BiometricStepUpGate({ now }, 60_000L, { ++nextNonce })
    private val read = BiometricStepUpOperation.AUDIT_READ
    private val export = BiometricStepUpOperation.AUDIT_EXPORT

    @Test fun noGrantMeansDenied() {
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(read, 1) }
    }

    @Test fun confirmedGrantIsSingleUse() {
        val n = gate.begin(read)
        assertTrue(gate.confirm(n, read, 1, cryptoProof = true))
        gate.consume(read, 1)
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(read, 1) }
    }

    @Test fun withoutCryptoProofNoGrant() {
        val n = gate.begin(read)
        assertFalse(gate.confirm(n, read, 1, cryptoProof = false))
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(read, 1) }
    }

    @Test fun nonceAndOperationMustMatch_andNonceIsNotReplayable() {
        val n = gate.begin(read)
        assertFalse(gate.confirm(n + 1, read, 1, true))
        assertFalse(gate.confirm(n, read, 1, true)) // уже сожжён
        val m = gate.begin(read)
        assertFalse(gate.confirm(m, export, 1, true))
    }

    @Test fun readGrantDoesNotAuthorizeExport() {
        val n = gate.begin(read); assertTrue(gate.confirm(n, read, 1, true))
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(export, 1) }
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(read, 1) } // попытка сжигает
    }

    @Test fun grantExpiresAfterTtl() {
        val n = gate.begin(export); assertTrue(gate.confirm(n, export, 1, true))
        now += 60_000L
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(export, 1) }
    }

    @Test fun slowPromptExpires() {
        val n = gate.begin(read)
        now += 61_000L
        assertFalse(gate.confirm(n, read, 1, true))
    }

    @Test fun localSessionChangeInvalidatesGrant() {
        val n = gate.begin(read); assertTrue(gate.confirm(n, read, 1, true))
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(read, 2) }
    }

    @Test fun invalidateAndNewBeginDropOldGrant() {
        var n = gate.begin(read); assertTrue(gate.confirm(n, read, 1, true))
        gate.invalidate()
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(read, 1) }
        n = gate.begin(read); assertTrue(gate.confirm(n, read, 1, true))
        gate.begin(export) // новая попытка аннулирует неиспользованный grant
        assertThrows(BiometricStepUpRequired::class.java) { gate.consume(read, 1) }
    }

    @Test fun resourceMapping() {
        assertEquals(read, BiometricStepUpOperation.fromResource("audit.read"))
        assertEquals(export, BiometricStepUpOperation.fromResource("audit.export"))
        assertNull(BiometricStepUpOperation.fromResource("library.export"))
    }
}
