package com.phonoteka.security
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Owned isolated namespaces only. Never edits production audit/key/files. */
class AndroidAuditJournalTest {
    private fun fixture(action: (AndroidAuditJournalFiles, SignedAuditJournal, String) -> Unit) {
        val ns = java.util.UUID.randomUUID().toString()
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "audit.fixture.$ns")
        val files = AndroidAuditJournalFiles(root); val journal = SignedAuditJournal(files, AndroidAuditChainMac(files::hasData, ns))
        try { action(files, journal, ns) }
        finally { root.deleteRecursively(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("phonoteka.lab14.audit-chain.hmac.v1.fixture.$ns") }
    }
    private fun decision(id: Int) = AuditDecision(id.toLong(), AuditActor("mock:student", "ADMIN"), "library.read", "track:$id", true)
    @Test fun realKeystoreChainAndFilesRoundTripPreserveCommittedPrefix() = fixture { files, journal, _ ->
        journal.append(decision(1)); val prefix = files.log()
        journal.append(decision(2)); assertArrayEquals(prefix, files.log().copyOf(prefix.size)); assertEquals(2, journal.read().size)
        assertEquals(2, journal.jsonLines().size)
    }
    @Test fun validTailTruncationIsRejectedWithRealSignedHead() = fixture { files, journal, _ ->
        journal.append(decision(1)); val prefix = files.log(); journal.append(decision(2))
        File(files.directory, "audit.log").writeBytes(prefix)
        try { journal.read(); fail("Truncated history accepted") } catch (_: IllegalStateException) {}
    }
    @Test fun deletingRealKeyCannotSilentlyResetExistingLog() = fixture { _, journal, ns ->
        journal.append(decision(1))
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "phonoteka.lab14.audit-chain.hmac.v1.fixture.$ns"; keyStore.deleteEntry(alias)
        try { journal.read(); fail("Missing key accepted") } catch (_: IllegalStateException) {}
        assertFalse(keyStore.containsAlias(alias))
    }
    @Test fun isolatedAtomicNewIntentIsNotPresentedAsCommittedHistory() = fixture { files, journal, _ ->
        assertTrue(journal.read().isEmpty()) // Generates verified genesis/key, not an event.
        File(files.directory, "audit.pending.new").writeText("unfinished owned write")
        assertTrue(journal.read().isEmpty()); journal.append(decision(1)); assertEquals(1, journal.read().size)
    }
}
