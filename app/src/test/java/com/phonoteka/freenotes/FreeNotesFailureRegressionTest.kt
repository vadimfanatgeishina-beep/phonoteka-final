package com.phonoteka.freenotes

import com.phonoteka.data.freenotes.*
import com.phonoteka.viewmodel.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FreeNotesFailureRegressionTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private val initial = listOf(FreeNote("original", "Моя заметка", "Текст", 100))
    private val replacement = listOf(FreeNote("new", "Импорт", "Новый текст", 200))
    private val docs = object : FreeNotesDocuments {
        override suspend fun read(uri: String) = FreeNotesCodec.encode(replacement)
        override suspend fun write(uri: String, bytes: ByteArray) {}
    }

    @Test fun rejectedAtomicReplacementKeepsOriginalBytesAndRemovesTemporaryFile() = runTest(main.testDispatcher) {
        val file = File(tmp.root, "notes.json")
        FreeNotesRepository(file, main.testDispatcher).save(initial)
        val before = file.readBytes()
        val repo = FreeNotesRepository(file, main.testDispatcher, replaceFile = { _, _ -> false })
        val failure = runCatching { repo.save(replacement) }.exceptionOrNull()
        assertTrue(failure is FreeNotesException)
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(tmp.root, "notes.json.tmp").exists())
        assertEquals(initial, FreeNotesRepository(file, main.testDispatcher).load())
    }

    @Test fun failedImportKeepsOldStateAndCanBeRetriedWithoutReadingDocumentAgain() = runTest(main.testDispatcher) {
        val file = File(tmp.root, "notes.json")
        FreeNotesRepository(file, main.testDispatcher).save(initial)
        val before = file.readBytes()
        var reject = true
        val repo = FreeNotesRepository(file, main.testDispatcher, replaceFile = { source, target ->
            if (reject) false else com.phonoteka.data.replaceAtomically(source, target)
        })
        val vm = FreeNotesViewModel(repo, docs)
        advanceUntilIdle()
        vm.importFrom("content://fixture"); advanceUntilIdle()
        vm.confirmImport(); advanceUntilIdle()
        assertEquals(initial, vm.state.value.notes)
        assertArrayEquals(before, file.readBytes())
        assertEquals(NotesSaveStatus.ERROR, vm.state.value.saveStatus)
        assertNotNull(vm.state.value.importPreview)
        assertFalse(vm.state.value.message.orEmpty().contains("Импортировано"))
        reject = false
        vm.confirmImport(); advanceUntilIdle()
        assertEquals(replacement, vm.state.value.notes)
        assertEquals(replacement, FreeNotesRepository(file, main.testDispatcher).load())
        assertEquals(NotesSaveStatus.SAVED, vm.state.value.saveStatus)
        assertNull(vm.state.value.importPreview)
        assertFalse(vm.state.value.dirty)
    }

    @Test fun failedRecoveryImportPreservesCorruptOriginalAtItsOriginalPath() = runTest(main.testDispatcher) {
        val file = File(tmp.root, "notes.json").apply { writeText("{broken") }
        val repo = FreeNotesRepository(file, main.testDispatcher, replaceFile = { _, _ -> false })
        val vm = FreeNotesViewModel(repo, docs)
        advanceUntilIdle()
        vm.importFrom("content://fixture"); advanceUntilIdle()
        vm.confirmImport(); advanceUntilIdle()
        assertEquals("{broken", file.readText())
        assertEquals(FreeNotesError.CORRUPT, vm.state.value.loadError)
        assertNotNull(vm.state.value.importPreview)
        val preserved = tmp.root.listFiles()!!.single { it.name.startsWith("notes.json.corrupt-") }
        assertEquals("{broken", preserved.readText())
    }
}
