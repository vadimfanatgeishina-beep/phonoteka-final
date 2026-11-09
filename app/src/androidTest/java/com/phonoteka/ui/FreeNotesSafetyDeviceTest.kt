package com.phonoteka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.phonoteka.data.freenotes.*
import com.phonoteka.ui.screens.FreeNotesScreen
import com.phonoteka.ui.screens.FreeNotesTags
import com.phonoteka.viewmodel.FreeNotesViewModel
import com.phonoteka.viewmodel.NotesSaveStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class FreeNotesSafetyDeviceTest {
    @get:Rule val ui = createComposeRule()
    private fun fixture(notes: List<FreeNote>, block: (File, FreeNotesViewModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "free-safety-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(root, "notes.json")
        val owner = ViewModelStore()
        val repo = FreeNotesRepository(file)
        lateinit var vm: FreeNotesViewModel
        try {
            runBlocking { repo.save(notes) }
            ui.runOnIdle {
                vm = FreeNotesViewModel(repo, AndroidFreeNotesDocuments(context.contentResolver))
                owner.put("notes", vm)
            }
            ui.setContent { MaterialTheme { FreeNotesScreen(vm, {}) } }
            ui.waitUntil(5_000) { vm.state.value.loaded }
            block(file, vm)
        } finally { ui.runOnIdle { owner.clear() }; root.deleteRecursively() }
    }

    @Test fun deletionRequiresConfirmationAndSurvivesRepositoryReopen() {
        val initial = listOf(FreeNote("a", "Сохранённая заметка", "Не удалять случайно", 1000))
        fixture(initial) { file, vm ->
            val before = file.readBytes()
            ui.onNodeWithTag(FreeNotesTags.row("a")).performClick()
            ui.onNodeWithTag(FreeNotesTags.DELETE).performClick()
            assertEquals(initial, vm.state.value.notes)
            assertArrayEquals(before, file.readBytes())
            ui.onNodeWithTag("freeNoteDeleteCancel").performClick()
            assertEquals(initial, vm.state.value.notes)
            ui.onNodeWithTag(FreeNotesTags.DELETE).performClick()
            ui.onNodeWithTag("freeNoteDeleteConfirm").performClick()
            ui.waitUntil(5_000) { vm.state.value.notes.isEmpty() && vm.state.value.saveStatus == NotesSaveStatus.SAVED }
            assertTrue(runBlocking { FreeNotesRepository(file).load() }.isEmpty())
        }
    }

    @Test fun newestNoteIsFirstAndModificationTimeIsVisible() {
        val old = FreeNote("old", "Старая", "", 1_700_000_000_000L)
        val recent = FreeNote("recent", "Новая", "", old.updatedAt + 120_000)
        fixture(listOf(old, recent)) { _, _ ->
            val newestTop = ui.onNodeWithTag(FreeNotesTags.row("recent")).getUnclippedBoundsInRoot().top
            val oldestTop = ui.onNodeWithTag(FreeNotesTags.row("old")).getUnclippedBoundsInRoot().top
            assertTrue(newestTop < oldestTop)
            val formatted = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(recent.updatedAt))
            ui.onNodeWithText("Изменено: $formatted").assertExists()
        }
    }
}
