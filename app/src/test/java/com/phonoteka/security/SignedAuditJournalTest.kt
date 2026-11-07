package com.phonoteka.security

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SignedAuditJournalTest {
    private enum class Fault { PENDING_BEFORE, PENDING_AFTER, APPEND_BEFORE, APPEND_PARTIAL, APPEND_AFTER, HEAD_BEFORE, HEAD_AFTER, CLEANUP }
    private class Files : AuditJournalFiles {
        var data = byteArrayOf(); var checkpoint: ByteArray? = null; var intent: ByteArray? = null
        var fault: Fault? = null; var appends = 0
        override fun <T> exclusive(action: () -> T): T = synchronized(this) { action() }
        override fun log() = data.copyOf()
        override fun head() = checkpoint?.copyOf()
        override fun pending() = intent?.copyOf()
        private fun trip(at: Fault) { if (fault == at) { fault = null; throw IOException("Owned write fault: $at") } }
        override fun append(bytes: ByteArray) {
            trip(Fault.APPEND_BEFORE)
            if (fault == Fault.APPEND_PARTIAL) { data += bytes.copyOf(minOf(bytes.size - 1, 13)); trip(Fault.APPEND_PARTIAL) }
            data += bytes; appends++; trip(Fault.APPEND_AFTER)
        }
        override fun putHead(bytes: ByteArray) { trip(Fault.HEAD_BEFORE); checkpoint = bytes.copyOf(); trip(Fault.HEAD_AFTER) }
        override fun putPending(bytes: ByteArray) { trip(Fault.PENDING_BEFORE); intent = bytes.copyOf(); trip(Fault.PENDING_AFTER) }
        override fun removePending() { trip(Fault.CLEANUP); intent = null }
    }
    private fun signer() = AuditChainMac { bytes -> Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(ByteArray(32), "HmacSHA256")); doFinal(bytes)
    } }
    private fun decision(index: Int = 0, allowed: Boolean = true) = AuditDecision(index.toLong(), AuditActor("mock:student", "ADMIN"), "library.read", "track:${index + 1}", allowed)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Integrity failure was accepted") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {}
    }
    private fun corrupt(bytes: ByteArray) = String(bytes, Charsets.UTF_8).replace("library.read", "library.edit").toByteArray(Charsets.UTF_8)
    @Test fun noSilent200RowPruningAndSubjectsRemainDistinct() {
        val f = Files(); val j = SignedAuditJournal(f, signer())
        repeat(250) { j.append(decision(it, it % 2 == 0)) }
        val rows = j.read(); assertEquals(250, rows.size)
        assertEquals(1L, rows.first().sequence); assertEquals(250L, rows.last().sequence)
        assertEquals("track:1", rows.first().decision.subject); assertEquals("track:250", rows.last().decision.subject)
        assertEquals(250, j.jsonLines().size); assertEquals(125, rows.count { it.decision.allowed })
    }
    @Test fun appendNeverRewritesEarlierCommittedBytes() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision())
        val prefix = f.data.copyOf(); j.append(decision(1)); assertArrayEquals(prefix, f.data.copyOf(prefix.size))
    }
    @Test fun sameLengthMutationIsDetectedAndNeverReset() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision()); f.data = corrupt(f.data)
        val broken = f.data.copyOf(); rejected { j.read() }; rejected { j.append(decision(1)) }; assertArrayEquals(broken, f.data)
    }
    @Test fun validTailTruncationIsDetectedBySignedHead() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision()); val prefix = f.data.copyOf()
        j.append(decision(1)); f.data = prefix; rejected { j.read() }
    }
    @Test fun validRowReorderIsRejected() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision()); j.append(decision(1))
        val lines = String(f.data).trimEnd().split('\n'); f.data = (lines.reversed().joinToString("\n") + "\n").toByteArray()
        rejected { j.read() }
    }
    @Test fun deletingCommittedMiddleRowIsRejected() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); repeat(3) { j.append(decision(it)) }
        val lines = String(f.data).trimEnd().split('\n'); f.data = (listOf(lines[0], lines[2]).joinToString("\n") + "\n").toByteArray()
        rejected { j.read() }
    }
    @Test fun editedCheckpointCannotReanchorHistory() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision())
        f.checkpoint = String(checkNotNull(f.checkpoint)).replace("\"sequence\":1", "\"sequence\":0").toByteArray(); rejected { j.read() }
    }
    @Test fun missingHeadWithExistingLogIsNotNewEmptyJournal() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision()); f.checkpoint = null
        rejected { j.read() }; assertNotNull(f.data); assertNull(f.checkpoint)
    }
    @Test fun missingLogWithCommittedHeadIsDetected() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision()); f.data = byteArrayOf(); rejected { j.read() }
    }
    @Test fun wrongKeyFailsWithoutErasingFiles() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision()); val before = f.data.copyOf()
        rejected { SignedAuditJournal(f, AuditChainMac { ByteArray(32) { 1 } }).read() }; assertArrayEquals(before, f.data)
    }
    @Test fun partialAppendRecoversByAppendingOnlyMissingSuffix() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.read(); f.fault = Fault.APPEND_PARTIAL
        try { j.append(decision()); fail() } catch (_: IOException) {}
        val partial = f.data.copyOf(); val recovered = SignedAuditJournal(f, signer()).read()
        assertEquals(1, recovered.size); assertArrayEquals(partial, f.data.copyOf(partial.size)); assertNull(f.intent)
    }
    @Test fun everyDurableFaultPointRecoversExactlyOneIntent() {
        for (fault in listOf(Fault.PENDING_AFTER, Fault.APPEND_BEFORE, Fault.APPEND_AFTER, Fault.HEAD_BEFORE, Fault.HEAD_AFTER, Fault.CLEANUP)) {
            val f = Files(); val j = SignedAuditJournal(f, signer()); j.read(); f.fault = fault
            try { j.append(decision()); fail("Missing simulated failure") } catch (_: IOException) {}
            val recovered = SignedAuditJournal(f, signer()); assertEquals(1, recovered.read().size)
            assertEquals(1, recovered.read().size); recovered.append(decision(1)); assertEquals(2, recovered.read().size)
        }
    }
    @Test fun failureBeforeDurableIntentDoesNotInventDecision() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.read(); f.fault = Fault.PENDING_BEFORE
        try { j.append(decision()); fail() } catch (_: IOException) {}
        assertTrue(j.read().isEmpty()); j.append(decision()); assertEquals(1L, j.read().single().sequence)
    }
    @Test fun corruptedIntentCannotRepairEvenValidCommittedPrefix() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision()); f.fault = Fault.APPEND_BEFORE
        try { j.append(decision(1)); fail() } catch (_: IOException) {}
        f.intent = corrupt(checkNotNull(f.intent)); val before = f.data.copyOf(); rejected { j.read() }; assertArrayEquals(before, f.data)
    }
    @Test fun unexpectedPendingSuffixIsNotTruncatedOrAccepted() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.read(); f.fault = Fault.APPEND_PARTIAL
        try { j.append(decision()); fail() } catch (_: IOException) {}
        f.data[0] = '!'.code.toByte(); val before = f.data.copyOf(); rejected { j.read() }; assertArrayEquals(before, f.data)
    }
    @Test fun uncommittedCompleteTailWithoutIntentFailsClosed() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.read(); f.fault = Fault.HEAD_BEFORE
        try { j.append(decision()); fail() } catch (_: IOException) {}
        f.intent = null; rejected { j.read() }; assertTrue(f.data.isNotEmpty())
    }
    @Test fun replayedOldIntentDoesNotAppendDuplicate() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.read(); f.fault = Fault.CLEANUP
        try { j.append(decision()); fail() } catch (_: IOException) {}
        val oldIntent = checkNotNull(f.intent).copyOf(); j.read(); j.append(decision(1)); f.intent = oldIntent
        val before = f.data.copyOf(); rejected { j.read() }; assertArrayEquals(before, f.data)
    }
    @Test fun rowDomainCannotMasqueradeAsSignedHead() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision())
        f.checkpoint = f.data.copyOf(f.data.size - 1); rejected { j.read() }
    }
    @Test fun capacityRejectsWriteWithoutPruningAndReadStillWorks() {
        val f = Files(); val j = SignedAuditJournal(f, signer(), maxEntries = 2, maxBytes = 4096)
        j.append(decision()); j.append(decision(1)); val before = f.data.copyOf(); rejected { j.append(decision(2)) }
        assertEquals(2, j.read().size); assertArrayEquals(before, f.data)
    }
    @Test fun actorActionAndSubjectInjectionRejectedBeforeAnyWrite() {
        for (d in listOf(decision().copy(action = "bad\nvalue"), decision().copy(subject = "raw?token=forbidden"), decision().copy(actor = AuditActor("bad|actor", "ADMIN")), decision().copy(actor = AuditActor("mock", "FAKE_ROLE")))) {
            val f = Files(); rejected { SignedAuditJournal(f, signer()).append(d) }; assertNull(f.checkpoint); assertTrue(f.data.isEmpty())
        }
    }
    @Test fun wholeValidPairRollbackIsExplicitlyOutsideLocalGuarantee() {
        val f = Files(); val j = SignedAuditJournal(f, signer()); j.append(decision())
        val oldLog = f.data.copyOf(); val oldHead = checkNotNull(f.checkpoint).copyOf(); j.append(decision(1))
        f.data = oldLog; f.checkpoint = oldHead
        assertEquals(1, j.read().size) // Honest limitation: needs remote/hardware monotonic witness to reject.
    }
    @Test fun concurrentWritersAcrossInstancesHaveUniqueSequence() {
        val f = Files(); val one = SignedAuditJournal(f, signer()); val two = SignedAuditJournal(f, signer())
        val threads = listOf(one, two).map { journal -> Thread { repeat(15) { journal.append(decision(it)) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals((1L..30L).toList(), one.read().map { it.sequence })
    }
}
