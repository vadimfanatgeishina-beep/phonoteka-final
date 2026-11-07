package com.phonoteka.viewmodel
import com.phonoteka.data.TrackInvites
import com.phonoteka.security.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrackInviteViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun lateNonCooperativeIssueCannotRestoreReceiptAfterClear() = runTest {
        val released = kotlinx.coroutines.CompletableDeferred<Unit>()
        val model = TrackInviteViewModel(object : TrackInvites {
            override suspend fun issue(trackId: Long): String {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { released.await() }
                return "stale owned fixture"
            }
            override suspend fun scan(wire: String) = ScannedTrackInvite(42)
        })
        val owner = androidx.lifecycle.ViewModelStore().apply { put("vm", model) }
        try {
            model.issue(42); runCurrent(); model.clear(); released.complete(Unit); runCurrent()
            assertNull(model.state.value.wire); assertNull(model.state.value.scannedTrackId); assertFalse(model.state.value.busy)
        } finally { released.complete(Unit); owner.clear() }
    }
    @Test fun deniedScanNeverShowsTrackOrRetainsWireAndBusyResets() = runTest {
        val model = TrackInviteViewModel(object : TrackInvites {
            override suspend fun issue(trackId: Long): String = "owned fixture"
            override suspend fun scan(wire: String): ScannedTrackInvite = throw InviteException(InviteFailure.DENIED)
        })
        val owner = androidx.lifecycle.ViewModelStore().apply { put("vm", model) }
        try {
            model.issue(42); runCurrent(); assertNotNull(model.state.value.wire)
            model.scan("owned fixture"); runCurrent()
            assertNull(model.state.value.wire); assertNull(model.state.value.scannedTrackId)
            assertFalse(model.state.value.busy); assertTrue(model.state.value.message!!.contains("DENIED"))
        } finally { owner.clear() }
    }
    @Test fun stepUpDenialDoesNotPerformIssueAndClearRemovesReceipt() = runTest {
        val model = TrackInviteViewModel(object : TrackInvites {
            override suspend fun issue(trackId: Long): String = throw StepUpRequiredException()
            override suspend fun scan(wire: String) = ScannedTrackInvite(42)
        })
        val owner = androidx.lifecycle.ViewModelStore().apply { put("vm", model) }
        try {
            model.issue(42); runCurrent(); assertNull(model.state.value.wire)
            model.scan("local"); runCurrent(); assertEquals(42L, model.state.value.scannedTrackId)
            model.clear(); assertNull(model.state.value.scannedTrackId)
        } finally { owner.clear() }
    }
}
