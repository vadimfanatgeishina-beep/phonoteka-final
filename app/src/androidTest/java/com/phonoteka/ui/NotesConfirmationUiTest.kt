package com.phonoteka.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.phonoteka.data.notes.*
import com.phonoteka.security.DemoScope
import com.phonoteka.ui.screens.TrackNotesScreen
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.TrackNotesViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Compiled component negatives; actual Android execution is required separately. */
class NotesConfirmationUiTest {
    @get:Rule val compose = createComposeRule()
    private class Fake(private val damaged: Boolean = false) : TrackNotes {
        val deletes = AtomicInteger(); val purges = AtomicInteger()
        override suspend fun list() = listOf(NoteRead(TrackNote(1, "Owned fixture", "body", updatedAt = 1000), damaged = damaged))
        override suspend fun trash() = listOf(TrashNote(NoteRead(TrackNote(2, "Trash fixture", "body")), 1000))
        override suspend fun save(note: TrackNote) {}
        override suspend fun delete(id: Long) { deletes.incrementAndGet() }
        override suspend fun restore(id: Long) {}
        override suspend fun purgeTrash() {}
        override suspend fun emptyTrashAfterConfirmation() { purges.incrementAndGet() }
        override suspend fun export() = byteArrayOf()
        override suspend fun importDocument(document: ByteArray) {}
        override suspend fun saveDraft(note: TrackNote) {}
        override suspend fun draft(id: Long): TrackNote? = null
        override suspend fun clearDraft(id: Long) {}
        override suspend fun resetAfterConfirmation() {}
    }
    private fun render(repo: Fake, owner: ViewModelStore) {
        lateinit var vm: TrackNotesViewModel
        compose.runOnIdle { vm = TrackNotesViewModel(repo, SavedStateHandle()); owner.put("notes", vm) }
        compose.setContent {
            CompositionLocalProvider(LocalDemoPermissions provides setOf(DemoScope.LIBRARY_READ, DemoScope.LIBRARY_WRITE)) {
                PhonotekaTheme { TrackNotesScreen(vm) {} }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("note-card-1").fetchSemanticsNodes().size == 1 }
    }
    @Test fun cancelDeleteCannotMutateTheRepository() {
        val repo = Fake(); val owner = ViewModelStore()
        try { render(repo, owner)
            compose.onNodeWithText("В корзину").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(0, repo.deletes.get()) }
            compose.onNodeWithText("Отмена").performClick()
            compose.runOnIdle { assertEquals(0, repo.deletes.get()) }
        } finally { compose.runOnIdle { owner.clear() } }
    }
    @Test fun confirmedDeleteCallsRepositoryOnlyOnce() {
        val repo = Fake(); val owner = ViewModelStore()
        try { render(repo, owner)
            compose.onNodeWithText("В корзину").performScrollTo().performClick()
            compose.onNodeWithText("Подтвердить удаление").performClick()
            compose.waitUntil(5_000) { repo.deletes.get() == 1 }
        } finally { compose.runOnIdle { owner.clear() } }
    }
    @Test fun trashClearRequiresSeparateConfirmationAndCancelPreservesIt() {
        val repo = Fake(); val owner = ViewModelStore()
        try { render(repo, owner)
            compose.onNodeWithText("Корзина").performScrollTo().performClick()
            compose.onNodeWithText("Очистить корзину").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(0, repo.purges.get()) }
            compose.onNodeWithText("Отмена").performClick()
            compose.runOnIdle { assertEquals(0, repo.purges.get()) }
        } finally { compose.runOnIdle { owner.clear() } }
    }
    @Test fun damagedCardDoesNotOfferOpenOrNormalDelete() {
        val repo = Fake(true); val owner = ViewModelStore()
        try { render(repo, owner)
            compose.onNodeWithText("Открыть").assertDoesNotExist()
            compose.onNodeWithText("В корзину").assertDoesNotExist()
            compose.onNodeWithText("Удалить повреждённую безвозвратно").assertExists()
        } finally { compose.runOnIdle { owner.clear() } }
    }
}
