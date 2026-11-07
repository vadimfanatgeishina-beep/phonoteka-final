package com.phonoteka.tasks

import com.phonoteka.viewmodel.MainDispatcherRule
import com.phonoteka.viewmodel.TaskTrackerViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeTaskStore(var stored: List<TaskItem>? = null, var failSave: Boolean = false) : TaskStore {
    var saves = 0
    override suspend fun load(): Result<List<TaskItem>?> = Result.success(stored)
    override suspend fun save(tasks: List<TaskItem>): Result<Unit> {
        saves++
        if (failSave) return Result.failure(java.io.IOException("disk"))
        stored = tasks; return Result.success(Unit)
    }
}

class TaskOpsTest {
    @Test fun seedHas60UniqueTasks() {
        val s = TaskOps.seed()
        assertEquals(60, s.size)
        assertEquals(60, s.map { it.id }.toSet().size)
    }

    @Test fun toggleKeepsUnchangedInstances() {
        val s = TaskOps.seed()
        val t = TaskOps.toggle(s, 2)
        assertTrue(t[1].done != s[1].done)
        assertSame(s[0], t[0]); assertSame(s[59], t[59])
        assertNotSame(s[0], TaskOps.remapAll(s)[0]) // антипример действительно пересоздаёт
    }

    @Test fun addValidatesAndPrepends() {
        val s = TaskOps.seed()
        assertEquals(null, TaskOps.add(s, "   "))
        assertEquals(null, TaskOps.add(s, "x".repeat(MAX_TASK_TITLE + 1)))
        val r = TaskOps.add(s, "  Купить струны ")!!
        assertEquals(61L, r[0].id); assertEquals("Купить струны", r[0].title)
    }

    @Test fun removeAndMoveUp() {
        val s = TaskOps.seed(3)
        assertEquals(listOf(1L, 3L), TaskOps.remove(s, 2).map { it.id })
        assertEquals(listOf(2L, 1L, 3L), TaskOps.moveUp(s, 2).map { it.id })
        assertSame(s, TaskOps.moveUp(s, 1))
    }

    @Test fun filterIsCaseInsensitiveAndTrimmed() {
        val s = TaskOps.seed()
        assertEquals(listOf(42L), TaskOps.filter(s, " задача 42 ").map { it.id })
        assertEquals(60, TaskOps.filter(s, "").size)
    }
}

class TaskCodecTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripWithSpecialCharacters() {
        val tasks = listOf(TaskItem(1, "a\tb\\c", true, "line1\nline2"), TaskItem(2, "просто", false, ""))
        assertEquals(tasks, TaskCodec.decode(TaskCodec.encode(tasks)))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateIds() {
        TaskCodec.decode("${TaskCodec.HEADER}\n1\t0\ta\tb\n1\t1\tc\td\n")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownHeader() { TaskCodec.decode("garbage\n") }

    @Test fun fileStoreRoundTrip() = runTest {
        val file = File(tmp.root, "t.txt")
        val store = TaskFileStore(file, kotlinx.coroutines.Dispatchers.Unconfined)
        assertEquals(null, store.load().getOrThrow())
        store.save(TaskOps.seed(5)).getOrThrow()
        assertEquals(TaskOps.seed(5), store.load().getOrThrow())
        assertFalse(File(tmp.root, "t.txt.tmp").exists())
    }
}

class PriceTickerTest {
    @Test fun listenersAreRemovedOnUnsubscribe() {
        val ticker = PriceTicker(); var last = 0
        val l: (Int) -> Unit = { last = it }
        ticker.subscribe(l); ticker.emit(7); assertEquals(7, last)
        ticker.unsubscribe(l); assertEquals(0, ticker.listenerCount)
    }

    @Test fun withoutUnsubscribeListenersAccumulate() {
        val ticker = PriceTicker()
        repeat(10) { ticker.subscribe { } } // модель эксперимента без onDispose
        assertEquals(10, ticker.listenerCount)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskTrackerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun firstLoadSeeds60AndDoesNotBumpChangeToken() = runTest(main.testDispatcher) {
        val vm = TaskTrackerViewModel(FakeTaskStore())
        vm.load(); advanceUntilIdle()
        assertEquals(60, vm.state.value.tasks.size)
        assertEquals(0, vm.state.value.changeToken)
        vm.load(); advanceUntilIdle()
        assertEquals(2, vm.loadCalls)
        assertEquals(60, vm.state.value.tasks.size)
    }

    @Test fun mutationsBumpTokenAndAutosaveSavesOnce() = runTest(main.testDispatcher) {
        val store = FakeTaskStore()
        val vm = TaskTrackerViewModel(store)
        vm.load(); advanceUntilIdle()
        vm.toggle(1); vm.toggle(2); assertTrue(vm.add("Новая"))
        assertEquals(3, vm.state.value.changeToken)
        vm.autosave(); advanceUntilIdle()
        assertEquals(1, store.saves)
        assertEquals(SaveState.SAVED, vm.state.value.saveState)
        vm.autosave(); advanceUntilIdle()
        assertEquals("без новых изменений повторно не пишем", 1, store.saves)
        assertEquals(61, store.stored!!.size)
    }

    @Test fun invalidAddAndNoOpMoveDoNotChangeToken() = runTest(main.testDispatcher) {
        val vm = TaskTrackerViewModel(FakeTaskStore())
        vm.load(); advanceUntilIdle()
        assertFalse(vm.add("  "))
        vm.moveUp(1)
        assertEquals(0, vm.state.value.changeToken)
    }

    @Test fun saveFailureShowsError() = runTest(main.testDispatcher) {
        val vm = TaskTrackerViewModel(FakeTaskStore(failSave = true))
        vm.load(); advanceUntilIdle()
        vm.toggle(3); vm.save(); advanceUntilIdle()
        assertEquals(SaveState.ERROR, vm.state.value.saveState)
        assertTrue(vm.state.value.message != null)
    }

    @Test fun restoresStoredTasks() = runTest(main.testDispatcher) {
        val vm = TaskTrackerViewModel(FakeTaskStore(stored = TaskOps.seed(4)))
        vm.load(); advanceUntilIdle()
        assertEquals(4, vm.state.value.tasks.size)
    }
}
