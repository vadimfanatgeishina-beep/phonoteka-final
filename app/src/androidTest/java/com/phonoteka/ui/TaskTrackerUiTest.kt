package com.phonoteka.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.phonoteka.tasks.AUTOSAVE_DELAY_MS
import com.phonoteka.tasks.SEARCH_DEBOUNCE_MS
import com.phonoteka.tasks.TaskItem
import com.phonoteka.tasks.TaskStore
import com.phonoteka.ui.screens.TaskTrackerScreen
import com.phonoteka.ui.screens.TaskTrackerTags
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.TaskTrackerViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** ЛР9, модуль 4: только mainClock / waitUntil, без Thread.sleep и delay в тестах. */
class TaskTrackerUiTest {
    @get:Rule val compose = createComposeRule()

    private class CountingStore : TaskStore {
        @Volatile var saves = 0
        override suspend fun load(): Result<List<TaskItem>?> = Result.success(null)
        override suspend fun save(tasks: List<TaskItem>): Result<Unit> { saves++; return Result.success(Unit) }
    }

    private val store = CountingStore()

    private fun launch() {
        compose.setContent {
            PhonotekaTheme { TaskTrackerScreen(viewModel = TaskTrackerViewModel(store), onBack = {}, showExperiments = false) }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TaskTrackerTags.row(1))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun rowExposesSelectedSemanticsAndTogglesOnClick() {
        launch()
        compose.onNodeWithTag(TaskTrackerTags.row(1)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithTag(TaskTrackerTags.row(2)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.onNodeWithTag(TaskTrackerTags.row(2)).performClick()
        compose.onNodeWithTag(TaskTrackerTags.row(2)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test fun addTaskShowsItAtTop() {
        launch()
        compose.onNodeWithTag(TaskTrackerTags.NEW_TASK).performTextInput("Купить струны")
        compose.onNodeWithTag(TaskTrackerTags.ADD).performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(hasTestTag(TaskTrackerTags.row(61))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TaskTrackerTags.row(61), useUnmergedTree = false).assert(hasText("Купить струны", substring = true))
    }

    @Test fun searchAppliesOnlyAfterDebounce() {
        launch()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(TaskTrackerTags.SEARCH).performTextInput("Задача 42")
        compose.mainClock.advanceTimeBy(SEARCH_DEBOUNCE_MS - 50)
        compose.onNodeWithTag(TaskTrackerTags.row(1)).assertExists()
        compose.mainClock.advanceTimeBy(100)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag(TaskTrackerTags.row(1)).assertDoesNotExist()
        compose.onNodeWithTag(TaskTrackerTags.row(42)).assertExists()
        compose.mainClock.autoAdvance = true
    }

    @Test fun autosaveHappensOnceAfterDelay() {
        launch()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(TaskTrackerTags.row(2)).performClick()
        compose.onNodeWithTag(TaskTrackerTags.row(3)).performClick()
        compose.mainClock.advanceTimeBy(AUTOSAVE_DELAY_MS - 100)
        assertEquals(0, store.saves)
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(3_000) { store.saves == 1 }
        compose.onNodeWithTag(TaskTrackerTags.SAVE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Сохранено"))
    }
}
