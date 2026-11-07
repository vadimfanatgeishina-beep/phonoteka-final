package com.phonoteka.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeProfileStore(var data: ProfileData = ProfileData(), var fail: Boolean = false) : ProfileStore {
    var writes = 0
    override suspend fun read() = data
    override suspend fun write(data: ProfileData) { writes++; if (fail) throw java.io.IOException("disk"); this.data = data }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun saveTrimsPersistsAndShowsMessage() = runTest(main.testDispatcher) {
        val store = FakeProfileStore()
        val vm = ProfileViewModel(store); advanceUntilIdle()
        vm.onNameChange("  Анна  "); vm.save(); advanceUntilIdle()
        assertEquals("Анна", store.data.name)
        assertEquals(ProfileSaveStatus.SAVED, vm.state.value.status)
        assertEquals("Изменения сохранены", vm.state.value.message)
        assertFalse(vm.state.value.dirty)
    }

    @Test fun blankNameBlocksSave() = runTest(main.testDispatcher) {
        val store = FakeProfileStore()
        val vm = ProfileViewModel(store); advanceUntilIdle()
        vm.onNameChange("   ")
        assertTrue(vm.state.value.nameError != null)
        vm.save(); advanceUntilIdle()
        assertEquals(0, store.writes)
    }

    @Test fun cancelRestoresSavedAndResetDoesNotWrite() = runTest(main.testDispatcher) {
        val store = FakeProfileStore(ProfileData("Иван", "bio"))
        val vm = ProfileViewModel(store); advanceUntilIdle()
        vm.onNameChange("Другое"); vm.cancel()
        assertEquals("Иван", vm.state.value.draft.name)
        vm.reset()
        assertEquals(ProfileData(), vm.state.value.draft)
        assertEquals(0, store.writes)
    }

    @Test fun longBioWarnsAndFailureIsReported() = runTest(main.testDispatcher) {
        val vm = ProfileViewModel(FakeProfileStore(fail = true)); advanceUntilIdle()
        vm.onBioChange("x".repeat(ProfileUiState.BIO_WARN + 1))
        assertTrue(vm.state.value.bioWarning != null)
        vm.save(); advanceUntilIdle()
        assertEquals(ProfileSaveStatus.ERROR, vm.state.value.status)
    }
}
