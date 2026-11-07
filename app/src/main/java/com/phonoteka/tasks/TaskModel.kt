package com.phonoteka.tasks

import androidx.compose.runtime.Immutable

/**
 * ЛР9: модель «Трекера задач».
 *
 * @Immutable обоснован: все поля val и неизменяемых типов (Long/String/Boolean),
 * экземпляр после создания не меняется — любое изменение = новый экземпляр через copy().
 * Поэтому Compose может сравнивать параметр TaskRow по equals и пропускать строку.
 */
@Immutable
data class TaskItem(
    val id: Long,
    val title: String,
    val done: Boolean,
    val details: String
)

enum class SaveState { IDLE, SAVING, SAVED, ERROR }

/** Варианты фильтрации для эксперимента модуля 5 (наивный / неверный remember / derivedStateOf). */
enum class FilterMode { NAIVE, STALE_REMEMBER, DERIVED }

const val INITIAL_TASK_COUNT = 60
const val SEARCH_DEBOUNCE_MS = 300L
const val AUTOSAVE_DELAY_MS = 1_000L
const val SCROLL_TOP_THRESHOLD = 5
const val MAX_TASK_TITLE = 80

/** Чистые операции над списком. Неизменённые строки сохраняют тот же экземпляр (без remap()). */
object TaskOps {
    fun seed(count: Int = INITIAL_TASK_COUNT): List<TaskItem> = List(count) { index ->
        val id = index + 1L
        TaskItem(
            id = id,
            title = "Задача $id",
            done = index % 7 == 0,
            details = "Подробности задачи $id: проверить, выполнить и отметить результат."
        )
    }

    fun toggle(tasks: List<TaskItem>, id: Long): List<TaskItem> =
        tasks.map { if (it.id == id) it.copy(done = !it.done) else it }

    /** Новая задача добавляется в начало списка, чтобы её сразу было видно. */
    fun add(tasks: List<TaskItem>, title: String): List<TaskItem>? {
        val clean = title.trim()
        if (clean.isEmpty() || clean.length > MAX_TASK_TITLE) return null
        val nextId = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
        return listOf(TaskItem(nextId, clean, false, "Добавлено пользователем")) + tasks
    }

    fun remove(tasks: List<TaskItem>, id: Long): List<TaskItem> = tasks.filterNot { it.id == id }

    /** Перестановка: поднять элемент на одну позицию выше. */
    fun moveUp(tasks: List<TaskItem>, id: Long): List<TaskItem> {
        val index = tasks.indexOfFirst { it.id == id }
        if (index <= 0) return tasks
        return tasks.toMutableList().apply { add(index - 1, removeAt(index)) }
    }

    fun filter(tasks: List<TaskItem>, query: String): List<TaskItem> {
        val q = query.trim()
        return if (q.isEmpty()) tasks else tasks.filter { it.title.contains(q, ignoreCase = true) }
    }

    /**
     * АНТИПРИМЕР (до оптимизации): пересоздаёт ВСЕ экземпляры. Оставлен только для
     * теста/отчёта, в экране не используется. Для @Immutable класса equals совпадает,
     * но для нестабильной модели это ломало пропуск по ссылке.
     */
    fun remapAll(tasks: List<TaskItem>): List<TaskItem> = tasks.map { it.copy() }
}

/** Простой счётчик для эксперимента «сколько раз пересчитан фильтр». */
class ComputationCounter {
    @Volatile var value: Int = 0
        private set
    fun increment() { value++ }
    fun reset() { value = 0 }
}
