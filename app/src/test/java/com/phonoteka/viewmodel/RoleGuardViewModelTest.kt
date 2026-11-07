package com.phonoteka.viewmodel
import com.phonoteka.data.TrackLibrary
import com.phonoteka.security.DemoScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RoleGuardViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun directViewModelMutationIsRejectedWithoutUi() = runTest {
        val backing = FakeTrackLibrary()
        val guarded = object : TrackLibrary by backing {
            override fun requireAccess(scope: DemoScope, resource: String) { throw SecurityException("denied") }
        }
        val vm = TrackListViewModel(guarded)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("vm", vm) }
        try {
            vm.addTrack("fixture", "forbidden")
            assertTrue(backing.tracks.value.isEmpty())
            assertFalse(vm.addTrackState.value.busy)
            assertTrue(vm.addTrackState.value.message.orEmpty().contains("Роль"))
        } finally { owner.clear() }
    }
}
