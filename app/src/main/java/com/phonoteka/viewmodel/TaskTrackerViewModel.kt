package com.phonoteka.viewmodel

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phonoteka.tasks.SaveState
import com.phonoteka.tasks.TaskItem
import com.phonoteka.tasks.TaskOps
import com.phonoteka.tasks.TaskStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * @Immutable: все поля val; tasks — список, который никогда не мутируется после публикации
 * (каждая операция TaskOps возвращает новый список).
 * changeToken увеличивается только при пользовательских изменениях — не при загрузке.
 */
@Immutable
data class TaskTrackerState(
    val tasks: List<TaskItem> = emptyList(),
    val loaded: Boolean = false,
    val changeToken: Int = 0,
    val savedToken: Int = 0,
    val saveState: SaveState = SaveState.IDLE,
    val message: String? = null
)

class TaskTrackerViewModel(private val store: TaskStore) : ViewModel() {
    private val _state = MutableStateFlow(TaskTrackerState())
    val state: StateFlow<TaskTrackerState> = _state.asStateFlow()

    /** Для доказательства «загрузка один раз на вход в композицию». */
    var loadCalls: Int = 0
        private set
    private var saveJobRunning = false
    private var saveRequestedAgain = false

    /** Вызывается из LaunchedEffect(Unit), не из тела composable. */
    fun load() {
        loadCalls++
        Log.d("LOAD", "load() call #$loadCalls")
        if (_state.value.loaded) return
        viewModelScope.launch {
            val result = store.load()
            _state.update { current ->
                if (current.loaded) return@update current
                result.fold(
                    onSuccess = { stored -> current.copy(tasks = stored ?: TaskOps.seed(), loaded = true) },
                    onFailure = {
                        current.copy(tasks = TaskOps.seed(), loaded = true,
                            message = "Файл задач не прочитан — показан исходный список. Файл не перезаписан до первого изменения.")
                    }
                )
            }
        }
    }

    fun toggle(id: Long) = mutate { TaskOps.toggle(it, id) }
    fun remove(id: Long) = mutate { TaskOps.remove(it, id) }
    fun moveUp(id: Long) = mutate { TaskOps.moveUp(it, id) }

    /** @return false, если название пустое или слишком длинное. */
    fun add(title: String): Boolean {
        val next = TaskOps.add(_state.value.tasks, title) ?: return false
        _state.update { it.copy(tasks = next, changeToken = it.changeToken + 1) }
        return true
    }

    private inline fun mutate(op: (List<TaskItem>) -> List<TaskItem>) {
        val current = _state.value
        val next = op(current.tasks)
        if (next === current.tasks || next == current.tasks) return
        _state.update { it.copy(tasks = next, changeToken = it.changeToken + 1) }
    }

    /** Ручное сохранение и автосохранение идут через одну точку; параллельные запросы склеиваются. */
    fun save() {
        if (!_state.value.loaded) return
        if (saveJobRunning) { saveRequestedAgain = true; return }
        saveJobRunning = true
        viewModelScope.launch {
            do {
                saveRequestedAgain = false
                val snapshot = _state.value
                _state.update { it.copy(saveState = SaveState.SAVING) }
                val result = store.save(snapshot.tasks)
                _state.update {
                    if (result.isSuccess) it.copy(saveState = SaveState.SAVED, savedToken = snapshot.changeToken, message = null)
                    else it.copy(saveState = SaveState.ERROR, message = "Не удалось сохранить: повторите")
                }
            } while (saveRequestedAgain && result.isSuccess)
            saveJobRunning = false
        }
    }

    /** Вызывается из debounced LaunchedEffect(changeToken). */
    fun autosave() {
        val s = _state.value
        if (s.changeToken > s.savedToken) save()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    class Factory(private val store: TaskStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TaskTrackerViewModel(store) as T
    }
}
