package com.phonoteka.data.notes

import com.phonoteka.security.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotesDestructiveAuthorizationTest {
    @Test fun directTrashClearAndDamagedRemovalEachNeedTheirOwnFreshApproval() = runTest {
        var auth = AuthenticationSnapshot(1, 1000)
        val controller = RiskStepUpController { 1000L }
        var cleared = 0; var removed = 0
        val raw = object : TrackNotes {
            override suspend fun list() = emptyList<NoteRead>()
            override suspend fun save(note: TrackNote) {}
            override suspend fun delete(id: Long) {}
            override suspend fun restore(id: Long) {}
            override suspend fun purgeTrash() {}
            override suspend fun export() = byteArrayOf()
            override suspend fun importDocument(document: ByteArray) {}
            override suspend fun saveDraft(note: TrackNote) {}
            override suspend fun draft(id: Long): TrackNote? = null
            override suspend fun clearDraft(id: Long) {}
            override suspend fun resetAfterConfirmation() {}
            override suspend fun emptyTrashAfterConfirmation() { cleared++ }
            override suspend fun removeCorrupted(id: Long) { removed++ }
        }
        val guarded = AuthorizedTrackNotes(raw) { _, resource -> controller.require(ProtectedOperation.fromResource(resource), auth, StepUpRisk()) }
        try { guarded.emptyTrashAfterConfirmation(); fail("Missing step-up") } catch (_: StepUpRequiredException) {}
        assertEquals(0, cleared); auth = AuthenticationSnapshot(2, 1000)
        assertTrue(controller.confirm(controller.pending.value!!.id, auth)); guarded.emptyTrashAfterConfirmation(); assertEquals(1, cleared)
        try { guarded.removeCorrupted(1); fail("Other operation approval was reused") } catch (_: StepUpRequiredException) {}
        assertEquals(0, removed); auth = AuthenticationSnapshot(3, 1000)
        assertTrue(controller.confirm(controller.pending.value!!.id, auth)); guarded.removeCorrupted(1); assertEquals(1, removed)
    }
}
