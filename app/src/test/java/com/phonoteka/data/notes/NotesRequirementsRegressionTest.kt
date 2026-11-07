package com.phonoteka.data.notes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import com.google.gson.JsonParser
import java.util.Base64

class NotesRequirementsRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val crypto = object : NoteCrypto {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(cipher: ByteArray) = encrypt(cipher)
        override fun mac(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
    private fun repo(root: File, clock: () -> Long = { 1000L }, index: AtomicNoteIndex = TestFileNoteIndex(File(root, "index.json"))) =
        FileTrackNotesRepository(root, File(root, "drafts"), crypto, index, { it in 1L..100L }, clock)
    private suspend fun expect(reason: NoteError, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") } catch (e: NoteException) { assertEquals(reason, e.reason) }
    }
    private fun breakRevisions(root: File, id: Long) {
        listOf("notes", "backup").forEach { dir -> File(root, dir).listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.writeBytes(byteArrayOf(1)) } }
    }
    @Test fun completelyEmptyNoteIsRejectedButEmptyEditingDraftIsAllowed() = runTest {
        val root = temporary.newFolder(); val r = repo(root)
        expect(NoteError.InvalidInput) { r.save(TrackNote(1, "  ", "\n\t")) }
        assertFalse(File(root, "index.json").exists())
        r.saveDraft(TrackNote(1, "", "")); assertEquals("", r.draft(1)!!.text)
    }
    @Test fun mutationsStampUpdatedAtAndNewestNotesAreListedFirst() = runTest {
        var time = 1000L; val r = repo(temporary.newFolder(), { time })
        r.save(TrackNote(1, "one", "body")); time = 2000
        r.save(TrackNote(2, "two", "body")); assertEquals(listOf(2L, 1L), r.list().map { it.note.id })
        time = 3000; r.save(TrackNote(1, "edited", "body"))
        assertEquals(listOf(1L, 2L), r.list().map { it.note.id }); assertEquals(3000L, r.list().first().note.updatedAt)
    }
    @Test fun indexCarriesAuthenticatedUpdatedAt() = runTest {
        val root = temporary.newFolder(); repo(root).save(TrackNote(1, "owned", "body"))
        val envelope = JsonParser.parseString(File(root, "index.json").readText()).asJsonObject
        val payload = JsonParser.parseString(String(Base64.getDecoder().decode(envelope.get("payload").asString))).asJsonObject
        assertEquals(1000L, payload.getAsJsonObject("active").getAsJsonObject("1").get("updatedAt").asLong)
    }
    @Test fun versionlessArrayAndObjectAcceptLegacyBodyWithoutInventingDates() = runTest {
        val r = repo(temporary.newFolder())
        r.importDocument("""[{"id":1,"title":"legacy","body":"owned"}]""".toByteArray())
        assertEquals("owned", r.list().single().note.text); assertEquals(0L, r.list().single().note.updatedAt)
        r.importDocument("""{"notes":[{"id":2,"title":"legacy2","text":"owned"}]}""".toByteArray())
        assertFalse(r.list().single().note.pinned); assertEquals(2L, r.list().single().note.id)
    }
    @Test fun unknownFieldsAreIgnoredButMissingRequiredFieldsAndAmbiguousBodyAreRejected() = runTest {
        val r = repo(temporary.newFolder())
        r.importDocument("""{"schemaVersion":2,"notes":[{"id":1,"title":"owned","text":"body","futureField":"ignored"}]}""".toByteArray())
        val before = r.export()
        expect(NoteError.InvalidInput) { r.importDocument("""{"notes":[{"id":1,"text":"missing title"}]}""".toByteArray()) }
        expect(NoteError.InvalidInput) { r.importDocument("""{"notes":[{"id":1,"title":"owned","text":"one","body":"two"}]}""".toByteArray()) }
        assertArrayEquals(before, r.export())
    }
    @Test fun hugeSchemaVersionCannotWrapToSupportedVersion() = runTest {
        val r = repo(temporary.newFolder())
        expect(NoteError.UnsupportedVersion) { r.importDocument("""{"schemaVersion":4294967298,"notes":[]}""".toByteArray()) }
    }
    @Test fun duplicateIdsAndWrongTypesDoNotPartiallyReplaceTheCatalog() = runTest {
        val r = repo(temporary.newFolder()); r.save(TrackNote(1, "keep", "body")); val before = r.export()
        expect(NoteError.InvalidInput) { r.importDocument("""{"notes":[{"id":2,"title":"a","body":"b"},{"id":2,"title":"c","body":"d"}]}""".toByteArray()) }
        expect(NoteError.InvalidInput) { r.importDocument("""{"notes":[{"id":"2","title":"a","body":"b"}]}""".toByteArray()) }
        assertArrayEquals(before, r.export())
    }
    @Test fun importPreviewReadsAndCountsButDoesNotWriteAnything() = runTest {
        val root = temporary.newFolder(); val r = repo(root)
        assertEquals(1, r.previewImport("""{"notes":[{"id":1,"title":"owned","body":"body"}]}""".toByteArray()))
        assertEquals(0, root.listFiles()!!.size)
        expect(NoteError.InvalidInput) { r.previewImport("""{"notes":[{"id":99,"title":"","body":""}]}""".toByteArray()) }
        assertEquals(0, root.listFiles()!!.size)
    }
    @Test fun bothCorruptCopiesProduceOneDamagedCardAndHealthyNotesRemainVisible() = runTest {
        val root = temporary.newFolder(); val r = repo(root)
        r.save(TrackNote(1, "private title", "private body")); r.save(TrackNote(1, "new private", "new body"))
        r.save(TrackNote(2, "healthy", "owned")); breakRevisions(root, 1)
        val rows = r.list(); assertEquals(2, rows.size)
        val damaged = rows.single { it.note.id == 1L }; assertTrue(damaged.damaged)
        assertEquals("Повреждена", damaged.note.title); assertEquals("", damaged.note.text)
        assertFalse(rows.single { it.note.id == 2L }.damaged)
    }
    @Test fun recoveredCopyIsActuallyRepairedAndIntegrityLogDoesNotContainNoteText() = runTest {
        val root = temporary.newFolder(); val r = repo(root)
        r.save(TrackNote(1, "do-not-log-title", "do-not-log-body")); r.save(TrackNote(1, "edited", "body"))
        File(root, "notes").listFiles()!!.single().writeBytes(byteArrayOf(1))
        assertTrue(r.list().single().recovered); assertFalse(r.list().single().recovered)
        val log = File(root, "integrity.log").readText()
        assertTrue(log.contains("restored_previous")); assertFalse(log.contains("do-not-log"))
    }
    @Test fun integrityCheckReportsProgressAndDamagedCount() = runTest {
        val root = temporary.newFolder(); val r = repo(root)
        r.save(TrackNote(1, "one", "body")); r.save(TrackNote(2, "two", "body")); breakRevisions(root, 1)
        val progress = mutableListOf<Pair<Int, Int>>()
        val summary = r.checkIntegrity { current, total -> progress += current to total }
        assertEquals(IntegritySummary(2, 0, 1), summary)
        assertEquals(0 to 2, progress.first()); assertEquals(2 to 2, progress.last())
    }
    @Test fun permanentDamagedRemovalCannotDeleteAHealthyNote() = runTest {
        val root = temporary.newFolder(); val r = repo(root)
        r.save(TrackNote(1, "bad", "body")); r.save(TrackNote(2, "healthy", "body"))
        expect(NoteError.InvalidInput) { r.removeCorrupted(2) }; breakRevisions(root, 1)
        r.removeCorrupted(1); assertEquals(listOf(2L), r.list().map { it.note.id })
    }
    @Test fun trashListsDeletionTimeAndClearingTrashNeverDeletesActiveNotes() = runTest {
        var time = 1000L; val r = repo(temporary.newFolder(), { time })
        r.save(TrackNote(1, "trash", "body")); r.save(TrackNote(2, "active", "body")); time = 2000
        r.delete(1); assertEquals(2000L, r.trash().single().deletedAt)
        r.emptyTrashAfterConfirmation(); assertTrue(r.trash().isEmpty()); assertEquals(2L, r.list().single().note.id)
        r.restore(1); assertEquals(1, r.list().size)
    }
    @Test fun failedTrashCommitKeepsPreviouslyCommittedTrashRecoverable() = runTest {
        val root = temporary.newFolder(); val r = repo(root); r.save(TrackNote(1, "owned", "body")); r.delete(1)
        val index = TestFileNoteIndex(File(root, "index.json"))
        val failing = object : AtomicNoteIndex {
            override fun read() = index.read()
            override fun write(bytes: ByteArray) { throw java.io.IOException("injected failure") }
        }
        expect(NoteError.Io) { repo(root, index = failing).emptyTrashAfterConfirmation() }
        assertEquals(1L, r.trash().single().item.note.id)
    }
    @Test fun exportDoesNotSilentlyOmitDamagedNotes() = runTest {
        val root = temporary.newFolder(); val r = repo(root); r.save(TrackNote(1, "owned", "body")); breakRevisions(root, 1)
        expect(NoteError.Corrupted) { r.export() }
    }
    @Test fun overFiveMiBImportIsRejectedBeforeAnyWrites() = runTest {
        val root = temporary.newFolder(); val r = repo(root)
        expect(NoteError.TooLarge) { r.previewImport(ByteArray(5 * 1024 * 1024 + 1)) }
        assertEquals(0, root.listFiles()!!.size)
    }
}
