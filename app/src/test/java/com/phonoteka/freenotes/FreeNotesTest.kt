package com.phonoteka.freenotes

import com.phonoteka.data.freenotes.FreeNote
import com.phonoteka.data.freenotes.FreeNotesCodec
import com.phonoteka.data.freenotes.FreeNotesDocuments
import com.phonoteka.data.freenotes.FreeNotesError
import com.phonoteka.data.freenotes.FreeNotesException
import com.phonoteka.data.freenotes.FreeNotesRepository
import com.phonoteka.viewmodel.FreeNotesViewModel
import com.phonoteka.viewmodel.MainDispatcherRule
import com.phonoteka.viewmodel.NotesSaveStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FreeNotesCodecTest {
    private val sample = listOf(FreeNote("a", "Первая", "текст \"в кавычках\"", 1L), FreeNote("b", "", "", 2L))

    @Test fun roundTrip() = assertEquals(sample, FreeNotesCodec.decode(FreeNotesCodec.encode(sample)))

    private fun expect(err: FreeNotesError, json: String) {
        try { FreeNotesCodec.decode(json.toByteArray()); fail("expected $err") }
        catch (e: FreeNotesException) { assertEquals(err, e.error) }
    }

    @Test fun rejectsBrokenJson() = expect(FreeNotesError.CORRUPT, "{\"version\":1,\"notes\":[")
    @Test fun rejectsWrongTypes() = expect(FreeNotesError.CORRUPT, "{\"version\":1,\"notes\":[{\"id\":1,\"title\":\"t\",\"body\":\"b\",\"updatedAt\":1}]}")
    @Test fun rejectsDuplicateIds() = expect(FreeNotesError.CORRUPT,
        "{\"version\":1,\"notes\":[{\"id\":\"x\",\"title\":\"\",\"body\":\"\",\"updatedAt\":1},{\"id\":\"x\",\"title\":\"\",\"body\":\"\",\"updatedAt\":2}]}")
    @Test fun rejectsUnknownVersion() = expect(FreeNotesError.UNSUPPORTED_VERSION, "{\"version\":2,\"notes\":[]}")
    // ЛР11 (kotlinx.serialization): числа в кавычках и строка-версия не принимаются, лишние поля игнорируются.
    @Test fun rejectsQuotedNumbers() = expect(FreeNotesError.CORRUPT, "{\"version\":1,\"notes\":[{\"id\":\"a\",\"title\":\"t\",\"body\":\"b\",\"updatedAt\":\"1\"}]}")
    @Test fun rejectsQuotedVersion() = expect(FreeNotesError.CORRUPT, "{\"version\":\"1\",\"notes\":[]}")
    @Test fun rejectsMissingField() = expect(FreeNotesError.CORRUPT, "{\"version\":1,\"notes\":[{\"id\":\"a\",\"title\":\"t\",\"updatedAt\":1}]}")
    @Test fun ignoresUnknownFields() = assertEquals(listOf(FreeNote("a", "t", "b", 1)), FreeNotesCodec.decode(
        "{\"version\":1,\"extra\":true,\"notes\":[{\"id\":\"a\",\"title\":\"t\",\"body\":\"b\",\"updatedAt\":1,\"x\":2}]}".toByteArray()))
    @Test fun legacyGsonFileStillReadable() = assertEquals(listOf(FreeNote("a", "t", "b", 5)), FreeNotesCodec.decode(
        "{\n  \"version\": 1,\n  \"notes\": [\n    {\n      \"id\": \"a\",\n      \"title\": \"t\",\n      \"body\": \"b\",\n      \"updatedAt\": 5\n    }\n  ]\n}".toByteArray()))
    @Test fun rejectsFractionalVersion() = expect(FreeNotesError.CORRUPT, "{\"version\":1.5,\"notes\":[]}")
}

class FreeNotesRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun missingFileIsEmpty() = runTest {
        assertEquals(emptyList<FreeNote>(), FreeNotesRepository(File(tmp.root, "notes.json")).load())
    }

    @Test fun saveThenLoadSurvivesNewInstance_noTmpLeft() = runTest {
        val f = File(tmp.root, "notes.json")
        val notes = listOf(FreeNote("1", "t", "b", 5))
        FreeNotesRepository(f).save(notes)
        assertEquals(notes, FreeNotesRepository(f).load())
        assertFalse(File(tmp.root, "notes.json.tmp").exists())
    }

    @Test fun corruptFileIsReportedAndKept() = runTest {
        val f = File(tmp.root, "notes.json").apply { writeText("not json") }
        try { FreeNotesRepository(f).load(); fail() } catch (e: FreeNotesException) { assertEquals(FreeNotesError.CORRUPT, e.error) }
        assertEquals("not json", f.readText())
        val moved = FreeNotesRepository(f).quarantineCorrupt(42)
        assertEquals("notes.json.corrupt-42", moved!!.name)
        assertEquals("not json", moved.readText())
        assertFalse(f.exists())
    }
}

private class FakeDocs : FreeNotesDocuments {
    val files = mutableMapOf<String, ByteArray>()
    override suspend fun read(uri: String): ByteArray = files[uri] ?: throw FreeNotesException(FreeNotesError.IO)
    override suspend fun write(uri: String, bytes: ByteArray) { files[uri] = bytes }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FreeNotesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private var ids = 0

    private fun vm(file: File, docs: FreeNotesDocuments = FakeDocs()) =
        FreeNotesViewModel(FreeNotesRepository(file, main.testDispatcher), docs, clock = { 100L }, newId = { "n${++ids}" })

    @Test fun autosaveAfterDebounce_onlyOnce() = runTest(main.testDispatcher) {
        val f = File(tmp.root, "notes.json")
        val v = vm(f); advanceUntilIdle()
        v.create(); val id = v.state.value.editingId!!
        "Привет".forEachIndexed { i, _ -> v.updateTitle(id, "Привет".take(i + 1)); advanceTimeBy(100) }
        assertFalse(f.exists())
        assertEquals(NotesSaveStatus.PENDING, v.state.value.saveStatus)
        advanceTimeBy(FreeNotesViewModel.AUTOSAVE_DELAY_MS + 1); runCurrent(); advanceUntilIdle()
        assertEquals(NotesSaveStatus.SAVED, v.state.value.saveStatus)
        assertEquals("Привет", FreeNotesRepository(f).load().single().title)
    }

    @Test fun flushSavesImmediatelyAndSurvivesRestart() = runTest(main.testDispatcher) {
        val f = File(tmp.root, "notes.json")
        val v = vm(f); advanceUntilIdle()
        v.create(); val id = v.state.value.editingId!!
        v.updateBody(id, "текст")
        v.flush(); runCurrent(); advanceUntilIdle()
        val restarted = vm(f); advanceUntilIdle()
        assertEquals("текст", restarted.state.value.notes.single().body)
    }

    @Test fun closingEmptyNewNoteDropsIt_deleteWorks() = runTest(main.testDispatcher) {
        val f = File(tmp.root, "notes.json")
        val v = vm(f); advanceUntilIdle()
        v.create(); v.closeEditor(); advanceUntilIdle()
        assertTrue(v.state.value.notes.isEmpty())
        v.create(); val id = v.state.value.editingId!!; v.updateTitle(id, "x"); v.closeEditor(); advanceUntilIdle()
        assertEquals(1, v.state.value.notes.size)
        v.delete(id); advanceUntilIdle()
        assertTrue(FreeNotesRepository(f).load().isEmpty())
    }

    @Test fun corruptFileBlocksWritesUntilUserDecides() = runTest(main.testDispatcher) {
        val f = File(tmp.root, "notes.json").apply { writeText("{broken") }
        val v = vm(f); advanceUntilIdle()
        assertEquals(FreeNotesError.CORRUPT, v.state.value.loadError)
        v.create(); advanceUntilIdle()
        assertTrue(v.state.value.notes.isEmpty())
        assertEquals("{broken", f.readText())
        v.startFresh(); advanceUntilIdle()
        assertNull(v.state.value.loadError)
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("notes.json.corrupt-") })
    }

    @Test fun exportImport_cancelAndInvalidDoNotChangeData() = runTest(main.testDispatcher) {
        val docs = FakeDocs()
        val f = File(tmp.root, "notes.json")
        val v = vm(f, docs); advanceUntilIdle()
        v.create(); val id = v.state.value.editingId!!; v.updateTitle(id, "A"); v.closeEditor(); advanceUntilIdle()
        v.exportTo("content://out"); advanceUntilIdle()
        assertArrayEquals(FreeNotesCodec.encode(v.state.value.notes), docs.files["content://out"])

        v.exportTo(null); v.importFrom(null); advanceUntilIdle()
        assertNull(v.state.value.importPreview)

        docs.files["content://bad"] = "garbage".toByteArray()
        v.importFrom("content://bad"); advanceUntilIdle()
        assertNull(v.state.value.importPreview)
        assertEquals("A", v.state.value.notes.single().title)

        docs.files["content://in"] = FreeNotesCodec.encode(listOf(FreeNote("z1", "B", "", 1), FreeNote("z2", "C", "", 2)))
        v.importFrom("content://in"); advanceUntilIdle()
        assertNotNull(v.state.value.importPreview)
        v.cancelImport()
        assertEquals("A", v.state.value.notes.single().title)

        v.importFrom("content://in"); advanceUntilIdle(); v.confirmImport(); advanceUntilIdle()
        assertEquals(listOf("B", "C"), v.state.value.notes.map { it.title })
        assertEquals(listOf("B", "C"), FreeNotesRepository(f).load().map { it.title })
    }
}
