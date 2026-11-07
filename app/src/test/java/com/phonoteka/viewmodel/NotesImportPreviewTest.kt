package com.phonoteka.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.phonoteka.data.notes.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotesImportPreviewTest {
    @get:Rule val main = MainDispatcherRule()
    private class Fake : TrackNotes {
        var previewed: ByteArray? = null; var imported: ByteArray? = null
        var error: NoteException? = null; var damaged = false
        override suspend fun list() = listOf(NoteRead(TrackNote(1, "owned", "body"), damaged = damaged))
        override suspend fun previewImport(document: ByteArray): Int { error?.let { throw it }; previewed = document.copyOf(); return 2 }
        override suspend fun importDocument(document: ByteArray) { imported = document.copyOf() }
        override suspend fun save(note: TrackNote) {}
        override suspend fun delete(id: Long) {}
        override suspend fun restore(id: Long) {}
        override suspend fun purgeTrash() {}
        override suspend fun export() = byteArrayOf()
        override suspend fun saveDraft(note: TrackNote) {}
        override suspend fun draft(id: Long): TrackNote? = null
        override suspend fun clearDraft(id: Long) {}
        override suspend fun resetAfterConfirmation() {}
    }
    @Test fun previewNeverAppliesAndCancelDoesNotImport() = runTest {
        val r = Fake(); val vm = TrackNotesViewModel(r, SavedStateHandle()); val owner = ViewModelStore().apply { put("notes", vm) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        try {
            runCurrent(); vm.prepareImport("owned".toByteArray()); runCurrent()
            assertEquals(2, vm.state.value.importCount); assertNull(r.imported)
            vm.cancelImport(); vm.applyPreparedImport(); runCurrent()
            assertNull(r.imported); assertNull(vm.state.value.importCount)
        } finally { owner.clear() }
    }
    @Test fun confirmationAppliesExactlyTheValidatedSnapshotNotMutatedCallerBytes() = runTest {
        val r = Fake(); val vm = TrackNotesViewModel(r, SavedStateHandle()); val owner = ViewModelStore().apply { put("notes", vm) }
        try {
            runCurrent(); val bytes = "owned".toByteArray(); vm.prepareImport(bytes); runCurrent(); bytes[0] = 'X'.code.toByte()
            assertNull(r.imported); vm.applyPreparedImport(); runCurrent(); assertEquals("owned", String(r.imported!!))
        } finally { owner.clear() }
    }
    @Test fun invalidNewPreviewCannotLeaveAnOlderPendingImportAvailable() = runTest {
        val r = Fake(); val vm = TrackNotesViewModel(r, SavedStateHandle()); val owner = ViewModelStore().apply { put("notes", vm) }
        try {
            runCurrent(); vm.prepareImport("older".toByteArray()); runCurrent()
            r.error = NoteException(NoteError.InvalidInput); vm.prepareImport("invalid".toByteArray()); runCurrent()
            vm.applyPreparedImport(); runCurrent(); assertNull(r.imported)
        } finally { owner.clear() }
    }
    @Test fun damagedEntryCannotBeOpenedThroughDirectViewModelCall() = runTest {
        val r = Fake().apply { damaged = true }; val vm = TrackNotesViewModel(r, SavedStateHandle()); val owner = ViewModelStore().apply { put("notes", vm) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        try {
            runCurrent(); vm.edit(1); runCurrent()
            assertEquals("", vm.draft.value.text); assertNotNull(vm.state.value.message)
            assertEquals(1, vm.state.value.damagedCount)
        } finally { owner.clear() }
    }
}
