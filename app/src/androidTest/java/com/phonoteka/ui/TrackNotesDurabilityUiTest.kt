package com.phonoteka.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso
import com.phonoteka.data.notes.*
import com.phonoteka.security.DemoScope
import com.phonoteka.ui.screens.TrackNotesScreen
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.TrackNotesViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Isolated component fixtures only. Does NOT authenticate the real app or certify KeyStore. */
class TrackNotesDurabilityUiTest {
    @get:Rule val compose = createComposeRule()
    private class Fake : TrackNotes {
        val gate = CompletableDeferred<Unit>()
        var fail = false
        var written: TrackNote? = null
        override suspend fun saveDraft(note: TrackNote) {
            gate.await()
            if (fail) throw NoteException(NoteError.Io)
            written = note
        }
        override suspend fun draft(id: Long) = written?.takeIf { it.id == id }
        override suspend fun list() = emptyList<NoteRead>()
        override suspend fun purgeTrash() {}
        override suspend fun save(note: TrackNote) {}
        override suspend fun delete(id: Long) {}
        override suspend fun restore(id: Long) {}
        override suspend fun export() = byteArrayOf()
        override suspend fun importDocument(document: ByteArray) {}
        override suspend fun clearDraft(id: Long) {}
        override suspend fun resetAfterConfirmation() { written = null }
    }
    @Test fun backButtonCannotLeaveBeforeTheNewestDraftWriteCompletes() {
        val repo = Fake(); val owner = ViewModelStore(); val left = AtomicInteger()
        lateinit var vm: TrackNotesViewModel
        compose.runOnIdle { vm = TrackNotesViewModel(repo, SavedStateHandle()); owner.put("notes", vm) }
        compose.setContent {
            CompositionLocalProvider(LocalDemoPermissions provides setOf(DemoScope.LIBRARY_READ, DemoScope.LIBRARY_WRITE)) {
                PhonotekaTheme { TrackNotesScreen(vm) { left.incrementAndGet() } }
            }
        }
        try {
            compose.runOnIdle { vm.update(1, "Owned fixture", "pending") }
            compose.onNodeWithText("Черновик сохраняется — запись ещё не подтверждена").assertExists()
            compose.onNodeWithText("Назад").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(0, left.get()); assertNull(repo.written) }
            compose.runOnIdle { repo.gate.complete(Unit) }
            compose.waitUntil(5_000) { left.get() == 1 }
            compose.runOnIdle { assertEquals("pending", repo.written?.text) }
        } finally { compose.runOnIdle { repo.gate.complete(Unit); owner.clear() } }
    }
    @Test fun failedWriteBlocksSystemBackAndRetryDoesNotRequireRetyping() {
        val repo = Fake().apply { fail = true; gate.complete(Unit) }
        val owner = ViewModelStore(); val left = AtomicInteger(); lateinit var vm: TrackNotesViewModel
        compose.runOnIdle { vm = TrackNotesViewModel(repo, SavedStateHandle()); owner.put("notes", vm) }
        compose.setContent {
            CompositionLocalProvider(LocalDemoPermissions provides setOf(DemoScope.LIBRARY_READ, DemoScope.LIBRARY_WRITE)) {
                PhonotekaTheme { TrackNotesScreen(vm) { left.incrementAndGet() } }
            }
        }
        try {
            compose.runOnIdle { vm.update(1, "Owned fixture", "retry same snapshot") }
            compose.onNodeWithText("Черновик не сохранён: повторите запись").assertExists()
            Espresso.pressBack()
            compose.runOnIdle { assertEquals(0, left.get()); assertNull(repo.written); repo.fail = false }
            compose.onNodeWithText("Повторить запись черновика").performScrollTo().performClick()
            compose.onNodeWithText("Черновик сохранён в зашифрованном локальном хранилище").assertExists()
            Espresso.pressBack()
            compose.waitUntil(5_000) { left.get() == 1 }
            compose.runOnIdle { assertEquals("retry same snapshot", repo.written?.text) }
        } finally { compose.runOnIdle { owner.clear() } }
    }    @Test fun failedWriteCanBeDiscardedOnlyAfterExplicitConfirmation() {
        val repo = Fake().apply { fail = true; gate.complete(Unit) }
        val owner = ViewModelStore(); val left = AtomicInteger(); lateinit var vm: TrackNotesViewModel
        compose.runOnIdle { vm = TrackNotesViewModel(repo, SavedStateHandle()); owner.put("notes", vm) }
        compose.setContent {
            CompositionLocalProvider(LocalDemoPermissions provides setOf(DemoScope.LIBRARY_READ, DemoScope.LIBRARY_WRITE)) {
                PhonotekaTheme { TrackNotesScreen(vm) { left.incrementAndGet() } }
            }
        }
        try {
            compose.runOnIdle { vm.update(1, "Owned fixture", "unconfirmed") }
            compose.onNodeWithText("Уйти без несохранённых правок").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(0, left.get()) }
            compose.onNodeWithText("Отбросить и уйти").performClick()
            compose.waitUntil(5_000) { left.get() == 1 }
            compose.runOnIdle { assertNull(repo.written) }
        } finally { compose.runOnIdle { owner.clear() } }
    }

}
