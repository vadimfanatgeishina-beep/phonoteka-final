package com.phonoteka.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.phonoteka.data.notes.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrackNotesDurabilityTest {
    @get:Rule val main = MainDispatcherRule()
    private class FakeNotes : TrackNotes {
        val drafts = mutableMapOf<Long, TrackNote>()
        val entries = mutableMapOf<Long, TrackNote>()
        val started = mutableListOf<TrackNote>()
        val events = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var error: NoteException? = null
        var purgeError: NoteException? = null
        var active = 0
        var maxActive = 0
        override suspend fun saveDraft(note: TrackNote) {
            active++; maxActive = maxOf(maxActive, active); started += note
            val captured = gate; gate = null
            try {
                captured?.await()
                error?.let { throw it }
                drafts[note.id] = note; events += "draft:${note.id}"
            } finally { active-- }
        }
        override suspend fun draft(id: Long) = drafts[id]
        override suspend fun list() = entries.values.map { NoteRead(it) }
        override suspend fun save(note: TrackNote) { events += "save:${note.id}"; entries[note.id] = note; drafts.remove(note.id) }
        override suspend fun delete(id: Long) { events += "delete:$id"; entries.remove(id); drafts.remove(id) }
        override suspend fun restore(id: Long) {}
        override suspend fun purgeTrash() { purgeError?.let { throw it } }
        override suspend fun export() = byteArrayOf()
        override suspend fun importDocument(document: ByteArray) {}
        override suspend fun clearDraft(id: Long) { drafts.remove(id) }
        override suspend fun resetAfterConfirmation() { events += "reset"; drafts.clear(); entries.clear() }
    }
    private fun TestScope.observe(vm: TrackNotesViewModel) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }

    @Test fun writeStartsWithoutWaitingForFourHundredMillisecondDebounce() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }
        observe(vm)
        try {
            runCurrent(); val before = testScheduler.currentTime
            vm.update(1, "owned title", "latest"); runCurrent()
            assertEquals(before, testScheduler.currentTime)
            assertEquals("latest", repo.drafts[1L]?.text)
            assertEquals(DraftPersistence.SAVED, vm.state.value.draftPersistence)
        } finally { owner.clear() }
    }
    @Test fun slowWriteCoalescesSameIdAndBackWaitsForTheNewestDurableSnapshot() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }; val gate = CompletableDeferred<Unit>()
        observe(vm)
        try {
            runCurrent(); repo.gate = gate
            vm.update(1, "title", "first"); runCurrent()
            vm.update(1, "title", "superseded"); vm.update(1, "title", "last"); runCurrent()
            var left = false; vm.leave { left = true }; runCurrent()
            assertFalse(left); assertEquals(DraftPersistence.SAVING, vm.state.value.draftPersistence)
            assertNull(repo.drafts[1L])
            gate.complete(Unit); runCurrent()
            assertTrue(left); assertEquals("last", repo.drafts[1L]?.text)
            assertEquals(listOf("first", "last"), repo.started.map { it.text })
            assertEquals(1, repo.maxActive)
            assertEquals(DraftPersistence.SAVED, vm.state.value.draftPersistence)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun coalescingDoesNotDropADifferentTracksPendingDraft() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }; val gate = CompletableDeferred<Unit>()
        try {
            runCurrent(); repo.gate = gate
            vm.update(1, "one", "first"); runCurrent()
            vm.update(2, "two", "second"); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals("first", repo.drafts[1L]?.text); assertEquals("second", repo.drafts[2L]?.text)
            assertEquals(1, repo.maxActive)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun failedWriteDoesNotAcknowledgeBackAndCanBeRetriedWithoutReenteringText() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }; observe(vm)
        try {
            runCurrent(); repo.error = NoteException(NoteError.Io)
            vm.update(1, "title", "retain for retry"); runCurrent()
            assertEquals(DraftPersistence.FAILED, vm.state.value.draftPersistence)
            var left = false; vm.leave { left = true }; runCurrent(); assertFalse(left)
            repo.error = null; vm.retryDrafts(); runCurrent()
            assertEquals("retain for retry", repo.drafts[1L]?.text)
            assertEquals(DraftPersistence.SAVED, vm.state.value.draftPersistence)
            vm.leave { left = true }; runCurrent(); assertTrue(left)
        } finally { owner.clear() }
    }
    @Test fun explicitSaveWaitsForDraftAndLateWriteCannotResurrectIt() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }; val gate = CompletableDeferred<Unit>()
        try {
            runCurrent(); repo.gate = gate; vm.update(1, "title", "committed"); runCurrent()
            vm.save(); runCurrent(); assertTrue(repo.entries.isEmpty())
            vm.update(1, "ignored while busy", "must not race the save")
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("draft:1", "save:1"), repo.events)
            assertEquals("committed", repo.entries[1L]?.text); assertNull(repo.drafts[1L])
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun confirmedResetWaitsForCapturedIoAndDropsQueuedDrafts() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }; val gate = CompletableDeferred<Unit>()
        observe(vm)
        try {
            runCurrent(); repo.gate = gate; vm.update(1, "one", "captured"); runCurrent()
            vm.update(2, "two", "queued"); vm.resetAfterConfirmation(); runCurrent()
            assertFalse("reset" in repo.events)
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("draft:1", "reset"), repo.events)
            assertTrue(repo.drafts.isEmpty()); assertEquals(DraftPersistence.IDLE, vm.state.value.draftPersistence)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun switchingEditorWaitsForPreviousIdAndDoesNotOverwriteItsDraft() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }; val gate = CompletableDeferred<Unit>()
        try {
            runCurrent(); repo.gate = gate; vm.update(1, "one", "keep before switch"); runCurrent()
            vm.edit(2); runCurrent(); assertEquals(1L, vm.draft.value.id)
            gate.complete(Unit); runCurrent()
            assertEquals(2L, vm.draft.value.id); assertEquals("keep before switch", repo.drafts[1L]?.text)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun deleteWaitsForIoAndDoesNotLeaveASavedLabelOnADeletedDraft() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }; val gate = CompletableDeferred<Unit>()
        observe(vm)
        try {
            runCurrent(); repo.gate = gate; vm.update(1, "owned", "pending deletion"); runCurrent()
            vm.delete(1); runCurrent(); assertFalse("delete:1" in repo.events)
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("draft:1", "delete:1"), repo.events)
            assertNull(repo.drafts[1L]); assertEquals("", vm.draft.value.text)
            assertEquals(DraftPersistence.IDLE, vm.state.value.draftPersistence)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun distinctIdQueueIsBoundedAndDoesNotAcceptAnUnqueueableInput() = runTest {
        val repo = FakeNotes(); val vm = TrackNotesViewModel(repo, SavedStateHandle())
        val owner = ViewModelStore().apply { put("vm", vm) }; val gate = CompletableDeferred<Unit>()
        try {
            runCurrent(); repo.gate = gate; vm.update(1, "one", "pending"); runCurrent()
            for (id in 2L..32L) vm.update(id, "owned", "pending")
            vm.update(33, "not accepted", "overflow"); runCurrent()
            assertEquals(32L, vm.draft.value.id)
            gate.complete(Unit); runCurrent()
            assertEquals(32, repo.drafts.size); assertFalse(repo.drafts.containsKey(33L)); assertEquals(1, repo.maxActive)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun confirmedDiscardDoesNotTrapTheUserOrDeleteAnyConfirmedStorage() = runTest {
        val repo = FakeNotes(); val saved = SavedStateHandle(); val vm = TrackNotesViewModel(repo, saved)
        val owner = ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent(); repo.drafts[1L] = TrackNote(1, "owned", "previous confirmed")
            repo.error = NoteException(NoteError.KeyUnavailable)
            vm.update(1, "owned", "unconfirmed"); runCurrent()
            var left = false; vm.leave { left = true }; runCurrent(); assertFalse(left)
            vm.leaveDiscardingAfterConfirmation { left = true }; runCurrent()
            assertTrue(left); assertEquals("previous confirmed", repo.drafts[1L]?.text)
            assertFalse("reset" in repo.events); assertFalse(saved.contains("note.title")); assertFalse(saved.contains("note.text")); assertFalse(saved.contains("note.restorePending"))
        } finally { owner.clear() }
    }
    @Test fun confirmedDiscardMarkerRestoresDiskInsteadOfAutosavingTheBlankEditor() = runTest {
        val repo = FakeNotes(); repo.drafts[1L] = TrackNote(1, "owned", "previous confirmed")
        val vm = TrackNotesViewModel(repo, SavedStateHandle(mapOf("note.id" to 1L, "note.title" to "", "note.text" to "", "note.restorePending" to false)))
        val owner = ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent(); assertEquals("previous confirmed", vm.draft.value.text)
            assertTrue(repo.started.isEmpty()); assertEquals("previous confirmed", repo.drafts[1L]?.text)
        } finally { owner.clear() }
    }
    @Test fun noteContentNeverEntersNewSavedStateSnapshots() = runTest {
        val repo = FakeNotes(); val saved = SavedStateHandle(); val vm = TrackNotesViewModel(repo, saved)
        val owner = ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent(); vm.update(2, "owned", "private fixture content", true); runCurrent()
            assertEquals(setOf("note.id"), saved.keys())
            assertEquals(2L, saved.get<Long>("note.id"))
            assertEquals("private fixture content", repo.drafts[2L]?.text)
        } finally { owner.clear() }
    }
    @Test fun legacyPlaintextBundleIsNotRenderedWhenKeyValidationFails() = runTest {
        val repo = FakeNotes().apply { purgeError = NoteException(NoteError.KeyUnavailable) }
        val saved = SavedStateHandle(mapOf("note.id" to 1L, "note.title" to "owned", "note.text" to "legacy private input"))
        val vm = TrackNotesViewModel(repo, saved); val owner = ViewModelStore().apply { put("vm", vm) }; observe(vm)
        try {
            runCurrent(); assertEquals("", vm.draft.value.text); assertTrue(repo.started.isEmpty())
            assertFalse(saved.contains("note.text")); assertTrue(vm.state.value.message!!.startsWith("Ключ недоступен"))
        } finally { owner.clear() }
    }
    @Test fun plaintextSavedStateCannotOverwriteAConfirmedDraftOrRequestAnImplicitWrite() = runTest {
        val repo = FakeNotes(); repo.drafts[2L] = TrackNote(2, "owned", "confirmed disk")
        val saved = SavedStateHandle(mapOf("note.id" to 2L, "note.title" to "untrusted", "note.text" to "must not overwrite"))
        val vm = TrackNotesViewModel(repo, saved); val owner = ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent(); assertEquals("confirmed disk", vm.draft.value.text)
            assertTrue(repo.started.isEmpty()); assertEquals(setOf("note.id"), saved.keys())
            assertEquals("confirmed disk", repo.drafts[2L]?.text)
        } finally { owner.clear() }
    }
    @Test fun plaintextSavedStateWithoutAnEncryptedFileIsNotAutomaticallyPersisted() = runTest {
        val repo = FakeNotes(); val saved = SavedStateHandle(mapOf("note.id" to 2L, "note.title" to "untrusted", "note.text" to "unconfirmed input"))
        val vm = TrackNotesViewModel(repo, saved); val owner = ViewModelStore().apply { put("vm", vm) }; observe(vm)
        try {
            runCurrent(); assertEquals("", vm.draft.value.text); assertTrue(repo.started.isEmpty()); assertTrue(repo.drafts.isEmpty())
            assertEquals(DraftPersistence.IDLE, vm.state.value.draftPersistence); assertEquals(setOf("note.id"), saved.keys())
        } finally { owner.clear() }
    }

}
