package com.phonoteka.data.notes
import com.phonoteka.security.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class AuthorizedNotesStepUpTest {
    @Test fun repositoryImportCannotRunBeforeFreshOperationSpecificApproval() = runTest {
        val now = 1000L
        var auth = AuthenticationSnapshot(1, now)
        var imports = 0
        val delegate = object : TrackNotes {
            override suspend fun list() = emptyList<NoteRead>()
            override suspend fun save(note: TrackNote) {}
            override suspend fun delete(id: Long) {}
            override suspend fun restore(id: Long) {}
            override suspend fun purgeTrash() {}
            override suspend fun export() = byteArrayOf()
            override suspend fun importDocument(document: ByteArray) { imports++ }
            override suspend fun saveDraft(note: TrackNote) {}
            override suspend fun draft(id: Long): TrackNote? = null
            override suspend fun clearDraft(id: Long) {}
            override suspend fun resetAfterConfirmation() {}
        }
        val controller = RiskStepUpController { now }
        val repository = AuthorizedTrackNotes(delegate) { _, resource -> controller.require(ProtectedOperation.fromResource(resource), auth, StepUpRisk()) }
        var denied = false
        try { repository.importDocument(byteArrayOf()) } catch (_: StepUpRequiredException) { denied = true }
        assertTrue(denied); assertEquals(0, imports)
        auth = AuthenticationSnapshot(2, now)
        assertTrue(controller.confirm(controller.pending.value!!.id, auth))
        repository.importDocument(byteArrayOf()); assertEquals(1, imports)
        controller.invalidate()
        denied = false
        try { repository.importDocument(byteArrayOf()) } catch (_: StepUpRequiredException) { denied = true }
        assertTrue(denied); assertEquals(1, imports)
    }
}
