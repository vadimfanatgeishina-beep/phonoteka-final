package com.phonoteka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phonoteka.data.freenotes.FreeNote
import com.phonoteka.data.freenotes.FreeNotesCodec
import com.phonoteka.data.freenotes.FreeNotesDocuments
import com.phonoteka.data.freenotes.FreeNotesError
import com.phonoteka.data.freenotes.FreeNotesException
import com.phonoteka.data.freenotes.FreeNotesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class NotesSaveStatus { IDLE, PENDING, SAVING, SAVED, ERROR }

@Immutable
data class ImportPreview(val notes: List<FreeNote>) { val count: Int get() = notes.size }

@Immutable
data class FreeNotesState(
    val notes: List<FreeNote> = emptyList(),
    val loaded: Boolean = false,
    /** Файл повреждён: запись заблокирована, пока пользователь не выберет «Начать заново» или импорт. */
    val loadError: FreeNotesError? = null,
    val editingId: String? = null,
    val changeToken: Int = 0,
    val savedToken: Int = 0,
    val saveStatus: NotesSaveStatus = NotesSaveStatus.IDLE,
    val importPreview: ImportPreview? = null,
    val message: String? = null,
    val importing: Boolean = false
) {
    val editing: FreeNote? get() = editingId?.let { id -> notes.firstOrNull { it.id == id } }
    val dirty: Boolean get() = changeToken != savedToken
    val writable: Boolean get() = loaded && loadError == null && !importing
}

class FreeNotesViewModel(
    private val repository: FreeNotesRepository,
    private val documents: FreeNotesDocuments,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val autosaveDelayMs: Long = AUTOSAVE_DELAY_MS
) : ViewModel() {
    private val _state = MutableStateFlow(FreeNotesState())
    val state: StateFlow<FreeNotesState> = _state.asStateFlow()
    private val saveMutex = Mutex()
    private var autosaveJob: Job? = null

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            try {
                val notes = repository.load()
                _state.update { it.copy(notes = notes, loaded = true, loadError = null) }
            } catch (e: FreeNotesException) {
                _state.update { it.copy(loaded = true, loadError = e.error, message = errorText(e.error, loading = true)) }
            }
        }
    }

    // ---------- CRUD ----------
    fun create() {
        if (!_state.value.writable) return
        val note = FreeNote(newId(), "", "", clock())
        mutate(open = note.id) { listOf(note) + it }
    }

    fun open(id: String) = _state.update { s -> if (s.notes.any { it.id == id }) s.copy(editingId = id) else s }

    fun updateTitle(id: String, title: String) =
        mutate { list -> list.map { if (it.id == id) it.copy(title = title.take(FreeNotesCodec.MAX_TITLE), updatedAt = clock()) else it } }

    fun updateBody(id: String, body: String) =
        mutate { list -> list.map { if (it.id == id) it.copy(body = body.take(FreeNotesCodec.MAX_BODY), updatedAt = clock()) else it } }

    fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        _state.update { if (it.editingId == id) it.copy(editingId = null) else it }
    }

    /** Закрыть редактор: пустая новая заметка удаляется, остальное сразу сохраняется. */
    fun closeEditor() {
        val editing = _state.value.editing
        if (editing != null && editing.title.isBlank() && editing.body.isBlank()) delete(editing.id)
        _state.update { it.copy(editingId = null) }
        flush()
    }

    private inline fun mutate(open: String? = null, op: (List<FreeNote>) -> List<FreeNote>) {
        val s = _state.value
        if (!s.writable) return
        val next = op(s.notes)
        if (next == s.notes && open == null) return
        _state.update {
            it.copy(notes = next, changeToken = it.changeToken + 1, saveStatus = NotesSaveStatus.PENDING,
                editingId = open ?: it.editingId)
        }
        scheduleAutosave()
    }

    // ---------- Автосохранение ----------
    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(autosaveDelayMs)
            persist()
        }
    }

    /** Вызывается при ON_STOP, «Назад» и закрытии редактора: сохранить сейчас, не дожидаясь задержки. */
    fun flush() {
        if (!_state.value.dirty) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch { persist() }
    }

    private suspend fun persist() {
        saveMutex.withLock {
            val snapshot = _state.value
            if (!snapshot.writable || !snapshot.dirty) return
            _state.update { it.copy(saveStatus = NotesSaveStatus.SAVING) }
            try {
                repository.save(snapshot.notes)
                _state.update {
                    it.copy(savedToken = snapshot.changeToken,
                        saveStatus = if (it.changeToken == snapshot.changeToken) NotesSaveStatus.SAVED else NotesSaveStatus.PENDING)
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(saveStatus = NotesSaveStatus.PENDING) }
                throw e
            } catch (e: FreeNotesException) {
                _state.update { it.copy(saveStatus = NotesSaveStatus.ERROR, message = errorText(e.error, loading = false)) }
            }
        }
    }

    // ---------- Повреждённый файл ----------
    fun startFresh() {
        if (_state.value.loadError == null) return
        viewModelScope.launch {
            try {
                val moved = repository.quarantineCorrupt(clock())
                _state.update {
                    it.copy(notes = emptyList(), loadError = null, changeToken = 0, savedToken = 0,
                        message = moved?.let { f -> "Повреждённый файл сохранён как ${f.name}" })
                }
            } catch (e: FreeNotesException) {
                _state.update { it.copy(message = errorText(e.error, loading = false)) }
            }
        }
    }

    // ---------- SAF: экспорт / импорт ----------
    /** uri == null — пользователь отменил диалог: ничего не делаем. */
    fun exportTo(uri: String?) {
        if (uri == null) { _state.update { it.copy(message = "Экспорт отменён") }; return }
        val notes = _state.value.notes
        viewModelScope.launch {
            try {
                documents.write(uri, FreeNotesCodec.encode(notes))
                _state.update { it.copy(message = "Экспортировано заметок: ${notes.size}") }
            } catch (e: FreeNotesException) {
                _state.update { it.copy(message = errorText(e.error, loading = false)) }
            }
        }
    }

    /** Шаг 1: прочитать и проверить. Данные не заменяются до confirmImport(). */
    fun importFrom(uri: String?) {
        if (uri == null) { _state.update { it.copy(message = "Импорт отменён") }; return }
        viewModelScope.launch {
            try {
                val notes = FreeNotesCodec.decode(documents.read(uri))
                _state.update { it.copy(importPreview = ImportPreview(notes), message = null) }
            } catch (e: FreeNotesException) {
                _state.update { it.copy(importPreview = null, message = errorText(e.error, loading = false)) }
            }
        }
    }

    fun cancelImport() = _state.update { if (it.importing) it else it.copy(importPreview = null, message = "Импорт отменён, заметки не изменены") }

    /** Шаг 2: замена текущих заметок импортированными и немедленная запись. Работает и для восстановления после повреждения. */
    fun confirmImport() {
        val preview = _state.value.importPreview ?: return
        if (_state.value.importing) return
        autosaveJob?.cancel()
        _state.update { it.copy(importing = true, saveStatus = NotesSaveStatus.SAVING, message = null) }
        viewModelScope.launch {
            // Once explicitly confirmed, finish one commit even if the editor is leaving the screen.
            withContext(NonCancellable) {
                saveMutex.withLock {
                    try {
                        if (_state.value.loadError != null) repository.preserveCorruptForImport(clock())
                        repository.save(preview.notes)
                        _state.update {
                            val token = it.changeToken + 1
                            it.copy(notes = preview.notes, loadError = null, editingId = null, importPreview = null,
                                changeToken = token, savedToken = token, saveStatus = NotesSaveStatus.SAVED,
                                message = "Импортировано заметок: ${preview.count}")
                        }
                    } catch (e: FreeNotesException) {
                        _state.update { it.copy(saveStatus = NotesSaveStatus.ERROR, message = errorText(e.error, loading = false)) }
                    } finally { _state.update { it.copy(importing = false) } }
                }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        // viewModelScope уже отменён; данные сохраняются flush() на ON_STOP и при выходе с экрана.
        super.onCleared()
    }

    private fun errorText(e: FreeNotesError, loading: Boolean): String = when (e) {
        FreeNotesError.CORRUPT -> if (loading) "Файл заметок повреждён. Он не будет перезаписан: импортируйте копию или начните заново." else "Файл не является корректным notes.json"
        FreeNotesError.UNSUPPORTED_VERSION -> "Неподдерживаемая версия файла заметок"
        FreeNotesError.TOO_LARGE -> "Файл слишком большой"
        FreeNotesError.IO -> if (loading) "Не удалось прочитать заметки" else "Ошибка ввода-вывода, повторите"
    }

    companion object { const val AUTOSAVE_DELAY_MS = 800L }

    class Factory(private val repository: FreeNotesRepository, private val documents: FreeNotesDocuments) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FreeNotesViewModel(repository, documents) as T
    }
}
