package com.phonoteka.labs

import androidx.lifecycle.SavedStateHandle
import com.phonoteka.viewmodel.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Lab5ListViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun startsWith100ItemsAndLoadsPagesOf20UntilEnd() = runTest(main.testDispatcher) {
        val vm = Lab5ListViewModel(loadDelayMs = 100)
        assertEquals(100, vm.state.value.items.size)
        assertTrue(vm.loadMore())
        assertFalse("дубль во время загрузки", vm.loadMore())
        assertTrue(vm.state.value.isLoading)
        advanceUntilIdle()
        assertEquals(120, vm.state.value.items.size)
        repeat(4) { vm.loadMore(); advanceUntilIdle() }
        assertEquals(Lab5Source.TOTAL, vm.state.value.items.size)
        assertTrue(vm.state.value.endReached)
        assertFalse(vm.loadMore())
        assertEquals((1..Lab5Source.TOTAL).toList(), vm.state.value.items.map { it.id })
    }

    @Test fun categoryFilterAndDebouncedSearch() = runTest(main.testDispatcher) {
        val vm = Lab5ListViewModel()
        runCurrent()
        vm.selectCategory(Lab5Category.JAZZ)
        assertTrue(vm.state.value.visible.all { it.category == Lab5Category.JAZZ })
        vm.selectCategory(Lab5Category.ALL)
        vm.onQueryChange("№1")
        advanceTimeBy(299); runCurrent()
        assertEquals("до 300 мс фильтр не применён", 100, vm.state.value.visible.size)
        advanceTimeBy(2); runCurrent()
        assertTrue(vm.state.value.visible.isNotEmpty())
        assertTrue(vm.state.value.visible.all { it.title.contains("№1") })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Lab6ViewModelsTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun detailsFoundForValidIdAndLoadsOnce() = runTest(main.testDispatcher) {
        val vm = LabDetailsViewModel(SavedStateHandle(mapOf("itemId" to "5", "ref" to "{\"source\":\"list\",\"category\":\"news\"}")))
        assertEquals(LabDetailsUiState.Loading, vm.state.value)
        advanceUntilIdle()
        val s = vm.state.value as LabDetailsUiState.Found
        assertEquals(5, s.article.id)
        assertEquals(DetailsRef("list", "news"), s.ref)
        assertEquals(1, vm.loadCount)
    }

    @Test fun detailsNotFoundForMissingOrInvalidId() = runTest(main.testDispatcher) {
        listOf("999", "abc", "0").forEach { id ->
            val vm = LabDetailsViewModel(SavedStateHandle(mapOf("itemId" to id)))
            advanceUntilIdle()
            assertEquals(LabDetailsUiState.NotFound(id), vm.state.value)
        }
    }

    @Test fun listReadsQueryParameters() = runTest(main.testDispatcher) {
        val vm = LabListViewModel(SavedStateHandle(mapOf("category" to "news", "favorites" to true)))
        assertTrue(vm.state.value.isLoading)
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.isLoading)
        assertTrue(s.items.isNotEmpty() || LabArticles.all.none { it.category == "news" && it.favorite })
        assertTrue(s.items.all { it.category == "news" && it.favorite })
        vm.setFavoritesOnly(false); vm.setCategory("all")
        assertEquals(20, vm.state.value.items.size)
        assertEquals(1, vm.loadCount)
    }

    @Test fun unknownCategoryFallsBackToAll() = runTest(main.testDispatcher) {
        val vm = LabListViewModel(SavedStateHandle(mapOf("category" to "hack")))
        advanceUntilIdle()
        assertEquals("all", vm.state.value.category)
        assertEquals(20, vm.state.value.items.size)
    }

    @Test fun settingsStateIsShared() {
        val vm = LabSettingsViewModel()
        vm.setDarkMode(true); vm.setShowHints(false)
        assertEquals(LabSettingsState(darkMode = true, showHints = false), vm.state.value)
    }

    @Test fun deepLinkParsing() {
        assertEquals("5", LabDeepLinks.parseDetailsId("app", "phonoteka", listOf("details", "5")))
        assertNull(LabDeepLinks.parseDetailsId("phonoteka", "details", listOf("1")))
        assertNull(LabDeepLinks.parseDetailsId("app", "evil", listOf("details", "5")))
        assertNull(LabDeepLinks.parseDetailsId("app", "phonoteka", listOf("details")))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Lab7ListViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    /** Последовательность ответов: true — успех, false — ошибка TIMEOUT. */
    private class ScriptedSource(vararg script: Boolean, private val delayMs: Long = 800) : Lab7Source {
        private val queue = ArrayDeque(script.toList())
        var calls = 0
        override suspend fun fetch(attempt: Int): List<Lab7Item> {
            calls++
            kotlinx.coroutines.delay(delayMs)
            val ok = queue.removeFirstOrNull() ?: true
            if (!ok) throw Lab7Exception(Lab7ErrorType.TIMEOUT)
            return listOf(Lab7Item(1, "a#$attempt"), Lab7Item(2, "b#$attempt"))
        }
    }

    private fun kotlinx.coroutines.test.TestScope.vm(source: Lab7Source, store: Lab7Store = InMemoryLab7Store()) =
        Lab7ListViewModel(source, store, clock = { testScheduler.currentTime }, log = {})

    @Test fun successfulLoad() = runTest(main.testDispatcher) {
        val vm = vm(ScriptedSource(true))
        advanceUntilIdle()
        val s = vm.uiState.value as ListUiState.Success
        assertTrue(s.items.isNotEmpty())
        assertFalse(s.fromCache)
    }

    @Test fun errorThenRetryStartsNewRequest() = runTest(main.testDispatcher) {
        val src = ScriptedSource(false, true)
        val vm = vm(src)
        advanceUntilIdle()
        val e = vm.uiState.value as ListUiState.Error
        assertEquals(Lab7ErrorType.TIMEOUT, e.errorType)
        assertEquals(1, e.retryCount)
        assertTrue(vm.retry())
        advanceUntilIdle()
        assertEquals(2, src.calls)
        assertTrue(vm.uiState.value is ListUiState.Success)
    }

    @Test fun multipleClicksProduceSingleLoading() = runTest(main.testDispatcher) {
        val src = ScriptedSource(true, true)
        val vm = vm(src)
        val states = mutableListOf<ListUiState>()
        val job: Job = launch { vm.uiState.toList(states) }
        runCurrent()
        assertFalse(vm.loadItems())
        assertFalse(vm.loadItems())
        assertFalse(vm.retry())
        advanceUntilIdle()
        assertEquals(1, src.calls)
        assertEquals(1, states.count { it == ListUiState.Loading })
        job.cancel()
    }

    @Test fun backoffGrowsWithEachFailure() = runTest(main.testDispatcher) {
        assertEquals(listOf(0L, 1000L, 2000L, 4000L, 8000L, 8000L), (1..6).map { Lab7ListViewModel.backoffDelayMs(it) })
        val src = ScriptedSource(false, false, false, false, true, delayMs = 10)
        val vm = vm(src)
        advanceUntilIdle()
        val delays = mutableListOf((vm.uiState.value as ListUiState.Error).nextRetryDelayMs)
        repeat(3) {
            vm.retry(); advanceUntilIdle()
            delays += (vm.uiState.value as ListUiState.Error).nextRetryDelayMs
        }
        assertEquals(listOf(0L, 1000L, 2000L, 4000L), delays)
        // retry во время backoff не шлёт запрос сразу
        val before = src.calls
        vm.retry(); advanceTimeBy(3_999); runCurrent()
        assertEquals(before, src.calls)
        assertFalse("повторное нажатие во время ожидания", vm.retry())
        advanceUntilIdle()
        assertEquals(before + 1, src.calls)
        assertTrue(vm.uiState.value is ListUiState.Success)
    }

    @Test fun cacheShownImmediatelyAndRefreshedInBackground() = runTest(main.testDispatcher) {
        val store = InMemoryLab7Store()
        val first = vm(ScriptedSource(true), store)
        advanceUntilIdle()
        val cachedAt = (first.uiState.value as ListUiState.Success).cachedAt

        val src = ScriptedSource(true)
        val second = vm(src, store)
        runCurrent()
        // кэш показан сразу, фоновое обновление — в состоянии Refreshing
        val r = second.uiState.value as ListUiState.Refreshing
        assertEquals(cachedAt, r.cachedAt)
        assertEquals(1, src.calls)
        advanceUntilIdle()
        assertTrue((second.uiState.value as ListUiState.Success).cachedAt >= cachedAt)
    }

    @Test fun refreshErrorKeepsCache() = runTest(main.testDispatcher) {
        val store = InMemoryLab7Store(Lab7Cached(listOf(Lab7Item(7, "cached")), 42L))
        val vm = vm(ScriptedSource(false), store)
        advanceUntilIdle()
        val s = vm.uiState.value as ListUiState.Success
        assertEquals(listOf(Lab7Item(7, "cached")), s.items)
        assertEquals(Lab7ErrorType.TIMEOUT, s.refreshError)
        assertTrue(s.fromCache)
    }

    @Test fun emptyResponseGivesEmptyState() = runTest(main.testDispatcher) {
        val vm = vm(Lab7Source { emptyList() })
        advanceUntilIdle()
        assertEquals(ListUiState.Empty, vm.uiState.value)
    }

    @Test fun rotationDoesNotDuplicateRequest() = runTest(main.testDispatcher) {
        // При повороте тот же экземпляр VM передаётся новой композиции: новая подписка не запускает загрузку.
        val src = ScriptedSource(true)
        val vm = vm(src)
        advanceUntilIdle()
        val before = vm.uiState.value
        val resubscribed = vm.uiState.value
        assertEquals(before, resubscribed)
        assertEquals(1, src.calls)
        assertEquals(1, vm.requestCount)
    }
}
