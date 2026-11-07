package com.phonoteka.data.notes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class DraftStorageMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val crypto = object : NoteCrypto {
        var failEncryption = false
        var failMac = false
        override fun encrypt(plain: ByteArray): ByteArray {
            if (failEncryption) throw NoteException(NoteError.KeyUnavailable)
            return plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
        override fun decrypt(cipher: ByteArray) = cipher.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun mac(bytes: ByteArray): ByteArray {
            if (failMac) throw NoteException(NoteError.KeyUnavailable)
            return MessageDigest.getInstance("SHA-256").digest(bytes)
        }
    }
    private fun repo(root: File, drafts: File, legacy: File? = null) = FileTrackNotesRepository(
        root, drafts, crypto, TestFileNoteIndex(File(root, "index.json")), { it in 1L..10L }, legacyDraftDirectory = legacy)

    @Test fun allLegacyDraftsArePromotedBeforeAnOsCacheEvictionWithoutOverwritingNewerFiles() = runTest {
        val root = temporary.newFolder(); val legacy = File(root, "old-cache"); val durable = File(root, "files-drafts")
        val old = repo(File(root, "old-repository"), legacy)
        old.saveDraft(TrackNote(1, "old", "legacy one")); old.saveDraft(TrackNote(2, "old", "legacy two"))
        repo(File(root, "new-repository"), durable).saveDraft(TrackNote(1, "new", "newer one"))
        val current = repo(File(root, "new-repository"), durable, legacy)
        current.list() // Eagerly promotes every legacy encrypted draft, not only the selected ID.
        assertFalse(File(legacy, "track-note-1.enc").exists()); assertFalse(File(legacy, "track-note-2.enc").exists())
        legacy.deleteRecursively()
        val recreated = repo(File(root, "new-repository"), durable, legacy)
        assertEquals("newer one", recreated.draft(1)?.text); assertEquals("legacy two", recreated.draft(2)?.text)
        assertFalse(File(durable, "track-note-2.enc").readText().contains("legacy two"))
    }
    @Test fun migrationWriteFailureKeepsLegacyCiphertextAndDoesNotInventDurableAck() = runTest {
        val root = temporary.newFolder(); val legacy = File(root, "old-cache"); val durable = File(root, "files-drafts")
        repo(File(root, "old-repository"), legacy).saveDraft(TrackNote(1, "owned", "pending"))
        val source = File(legacy, "track-note-1.enc"); val original = source.readBytes()
        crypto.failEncryption = true
        try { repo(File(root, "new-repository"), durable, legacy).draft(1); fail("Expected failed migration") }
        catch (e: NoteException) { assertEquals(NoteError.KeyUnavailable, e.reason) }
        assertArrayEquals(original, source.readBytes()); assertFalse(File(durable, "track-note-1.enc").exists())
    }
    @Test fun interruptedTmpFileIsIgnoredAndNextWritePreservesLastConfirmedDraft() = runTest {
        val root = temporary.newFolder(); val drafts = File(root, "files-drafts")
        val current = repo(root, drafts); val confirmed = TrackNote(1, "owned", "confirmed")
        current.saveDraft(confirmed); File(drafts, "track-note-1.enc.tmp").writeBytes(byteArrayOf(1, 2))
        assertEquals(confirmed, repo(root, drafts).draft(1))
        crypto.failEncryption = true
        try { current.saveDraft(confirmed.copy(text = "not committed")); fail("Expected failed write") }
        catch (e: NoteException) { assertEquals(NoteError.KeyUnavailable, e.reason) }
        assertEquals(confirmed, repo(root, drafts).draft(1))
        crypto.failEncryption = false; current.saveDraft(confirmed.copy(text = "new confirmed"))
        assertEquals("new confirmed", repo(root, drafts).draft(1)?.text)
    }
    @Test fun explicitSaveAndClearCannotResurrectRetiredLegacyDrafts() = runTest {
        val root = temporary.newFolder(); val legacy = File(root, "old-cache"); val drafts = File(root, "files-drafts")
        repo(File(root, "old-repository"), legacy).saveDraft(TrackNote(1, "old", "stale"))
        val current = repo(root, drafts, legacy); current.save(TrackNote(1, "new", "committed"))
        assertNull(current.draft(1)); assertEquals("committed", current.list().single().note.text)
        assertFalse(File(legacy, "track-note-1.enc").exists())
        current.saveDraft(TrackNote(1, "owned", "another draft")); current.clearDraft(1)
        assertNull(repo(root, drafts, legacy).draft(1))
    }
    @Test fun missingTrackIdCannotCreateAnUnlinkedDraft() = runTest {
        val root = temporary.newFolder(); val drafts = File(root, "files-drafts")
        try { repo(root, drafts).saveDraft(TrackNote(99, "invalid", "not linked")); fail("Expected invalid ID") }
        catch (e: NoteException) { assertEquals(NoteError.InvalidInput, e.reason) }
        assertFalse(File(drafts, "track-note-99.enc").exists())
    }
    @Test fun failedMacBootstrapDoesNotReplaceTheLastConfirmedCiphertext() = runTest {
        val root = temporary.newFolder(); val drafts = File(root, "files-drafts"); val current = repo(root, drafts)
        val first = TrackNote(1, "owned", "last confirmed")
        current.saveDraft(first); val file = File(drafts, "track-note-1.enc"); val original = file.readBytes()
        crypto.failMac = true
        try { current.saveDraft(first.copy(text = "not confirmed")); fail("Expected failed key bootstrap") }
        catch (e: NoteException) { assertEquals(NoteError.KeyUnavailable, e.reason) }
        assertArrayEquals(original, file.readBytes()); assertEquals(first, current.draft(1))
    }
    @Test fun resetRemovesBothDurableAndLegacyEncryptedDrafts() = runTest {
        val root = temporary.newFolder(); val legacy = File(root, "old-cache"); val drafts = File(root, "files-drafts")
        repo(File(root, "old-repository"), legacy).saveDraft(TrackNote(1, "old", "pending"))
        val current = repo(File(root, "repository"), drafts, legacy)
        current.saveDraft(TrackNote(2, "new", "pending")); current.resetAfterConfirmation()
        assertFalse(drafts.exists()); assertFalse(legacy.exists())
    }
}
