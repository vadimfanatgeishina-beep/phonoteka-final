package com.phonoteka.data.notes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class TrackNotesRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    // Only a deterministic test double, never wired to the Android application.
    private val crypto = object : NoteCrypto {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(cipher: ByteArray) = encrypt(cipher)
        override fun mac(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
    private fun repo(root: File, now: () -> Long = { 1000L }, index: AtomicNoteIndex = TestFileNoteIndex(File(root, "index.json"))) =
        FileTrackNotesRepository(root, File(root, "cache"), crypto, index, { it in 1L..10L }, now)
    @Test fun encryptedRoundTripAndDraftSurviveRepositoryRecreation() = runTest {
        val root = temporary.newFolder()
        val note = TrackNote(1, "title", "body", true, updatedAt = 1000)
        repo(root).save(note)
        repo(root).saveDraft(note.copy(text = "unfinished"))
        assertEquals(note, repo(root).list().single().note)
        assertEquals("unfinished", repo(root).draft(1)?.text)
        assertFalse(File(root, "notes").listFiles()!!.single().readText().contains("body"))
    }
    @Test fun corruptedRevisionFallsBackToPreviousAuthenticatedRevision() = runTest {
        val root = temporary.newFolder()
        val first = TrackNote(1, "first", "text", updatedAt = 1000)
        val r = repo(root)
        r.save(first)
        r.save(first.copy(title = "second"))
        File(root, "notes").listFiles()!!.single().writeBytes(byteArrayOf(1))
        val recovered = r.list().single()
        assertEquals(first, recovered.note)
        assertTrue(recovered.recovered)
        assertFalse("Recovery repaired the current pointer", r.list().single().recovered)
    }
    @Test fun v1ImportAddsPinnedAndExportUsesVersionTwo() = runTest {
        val r = repo(temporary.newFolder())
        r.importDocument("""{"schemaVersion":1,"notes":[{"id":1,"title":"v1","text":"text"}]}""".toByteArray())
        assertFalse(r.list().single().note.pinned)
        assertTrue(String(r.export()).contains("\"schemaVersion\":2"))
    }
    @Test fun trashRestoresBeforeTtlAndPurgesAfterThirtyDays() = runTest {
        var now = 1000L
        val r = repo(temporary.newFolder(), { now })
        val note = TrackNote(1, "title", "body", updatedAt = 1000)
        r.save(note); r.delete(1); assertTrue(r.list().isEmpty())
        r.restore(1); assertEquals(note, r.list().single().note)
        r.delete(1); now += 30L * 24 * 60 * 60 * 1000 + 1
        r.purgeTrash(); r.restore(1); assertTrue(r.list().isEmpty())
    }
    @Test fun failedIndexCommitLeavesEntirePreviousCatalogVisible() = runTest {
        val root = temporary.newFolder()
        val index = TestFileNoteIndex(File(root, "index.json"))
        val r = repo(root, index = index)
        val note = TrackNote(1, "old", "text", updatedAt = 1000)
        r.save(note)
        val failing = object : AtomicNoteIndex {
            override fun read() = index.read()
            override fun write(bytes: ByteArray) { throw java.io.IOException("injected commit failure") }
        }
        var failed = false
        try { repo(root, index = failing).save(note.copy(title = "new")) } catch (_: NoteException) { failed = true }
        assertTrue(failed)
        assertEquals(note, r.list().single().note)
    }
    @Test fun tamperedManifestAndInvalidImportAreRejectedWithoutReplacingData() = runTest {
        val root = temporary.newFolder()
        val r = repo(root)
        val note = TrackNote(1, "old", "text", updatedAt = 1000)
        r.save(note)
        var failed = false
        try { r.importDocument("""{"schemaVersion":2,"notes":[{"id":99,"title":"bad","text":"x"}]}""".toByteArray()) }
        catch (_: NoteException) { failed = true }
        assertTrue(failed); assertEquals(note, r.list().single().note)
        File(root, "index.json").writeText("{}")
        failed = false
        try { r.list() } catch (_: NoteException) { failed = true }
        assertTrue(failed)
    }
}
