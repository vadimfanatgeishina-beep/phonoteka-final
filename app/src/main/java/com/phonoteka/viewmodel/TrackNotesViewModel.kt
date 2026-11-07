package com.phonoteka.viewmodel

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.phonoteka.data.notes.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** SAVED is emitted only AFTER saveDraft returns, never after merely accepting input. */
enum class DraftPersistence { IDLE, SAVING, SAVED, FAILED }
data class NotesUiState(val notes: List<NoteRead> = emptyList(), val busy: Boolean = false,
    val message: String? = null, val draftPersistence: DraftPersistence = DraftPersistence.IDLE,
    val trash: List<TrashNote> = emptyList(), val damagedCount: Int = 0,
    val integrityChecked: Int = 0, val integrityTotal: Int = 0, val importCount: Int? = null)
@MainThread
class TrackNotesViewModel(private val repository: TrackNotes, private val saved: SavedStateHandle) : ViewModel() {
    // SavedStateHandle may also contain default/Intent arguments. Plaintext legacy
    // fields are NEVER trusted as a request to overwrite an authenticated draft.
    // New snapshots contain only the editor ID; disk ciphertext is authoritative.
    private val hadLegacyContent = saved.contains("note.title") || saved.contains("note.text")
    private val all = MutableStateFlow(NotesUiState())
    private val query = MutableStateFlow("")
    private var pendingImport: ByteArray? = null
    private val editId = saved.getStateFlow("note.id", 1L)
    private val editing = MutableStateFlow(TrackNote(editId.value, "", ""))
    val draft: StateFlow<TrackNote> = editing.asStateFlow()
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val state = combine(all, query.debounce(300).onStart { emit("") }) { current, text ->
        current.copy(notes = current.notes.filter { text.isBlank() || it.damaged || it.note.title.contains(text, true) || it.note.text.contains(text, true) },
            trash = current.trash.filter { text.isBlank() || it.item.damaged || it.item.note.title.contains(text, true) || it.item.note.text.contains(text, true) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotesUiState())
    private data class Pending(val version: Long, val note: TrackNote)
    // Main-thread confined. Coalesce by ID, retaining OTHER tracks' pending edits.
    private val pending = linkedMapOf<Long, Pending>()
    private val failed = linkedMapOf<Long, Pair<Pending, Exception>>()
    private var version = 0L
    private var editGeneration = 0L
    private var draftJob: Job? = null
    init {
        saved.remove<String>("note.title"); saved.remove<String>("note.text")
        saved.remove<Boolean>("note.pinned"); saved.remove<Boolean>("note.restorePending")
        action {
            repository.purgeTrash(); reload()
            val generation = editGeneration
            if (generation == 0L) {
                val id = editId.value
                val restored = repository.draft(id) ?: all.value.notes.firstOrNull { it.note.id == id && !it.damaged }?.note ?: TrackNote(id, "", "")
                if (editGeneration == generation) setDraft(restored, persist = false)
            }
            if (hadLegacyContent) all.update { it.copy(message = "Восстановлены данные из зашифрованного хранилища. Неподтверждённые правки старого saved state не переносятся.") }
        }
    }
    fun search(text: String) { query.value = text }
    fun edit(id: Long) = action {
        awaitDrafts()
        val item = repository.list().firstOrNull { it.note.id == id }
        if (item?.damaged == true) throw NoteException(NoteError.Corrupted)
        val note = repository.draft(id) ?: item?.note ?: TrackNote(id, "", "")
        setDraft(note, persist = false)
    }
    private fun snapshot() = editing.value
    private fun setDraft(note: TrackNote, persist: Boolean) {
        if (persist && note.id !in pending && note.id !in failed && (pending.keys + failed.keys).size >= 32) {
            all.update { it.copy(message = "Очередь черновиков заполнена: дождитесь сохранения или повторите запись.") }
            return // Do not accept an input we cannot queue; never claim it is durable.
        }
        editGeneration++
        saved["note.id"] = note.id
        editing.value = note
        if (persist) enqueue(note) else all.update { it.copy(draftPersistence = DraftPersistence.IDLE) }
    }
    private fun enqueue(note: TrackNote) {
        pending[note.id] = Pending(++version, note)
        failed.remove(note.id)
        all.update { it.copy(draftPersistence = DraftPersistence.SAVING) }
        startWriter()
    }
    private fun startWriter() {
        if (draftJob?.isActive == true) return
        draftJob = viewModelScope.launch {
            while (pending.isNotEmpty()) {
                val work = pending.values.first()
                var error: Exception? = null
                try { repository.saveDraft(work.note) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { error = e }
                // A newer edit may have arrived while IO was suspended. An old
                // completion must not acknowledge/remove that newer snapshot.
                if (pending[work.note.id]?.version == work.version) {
                    pending.remove(work.note.id)
                    if (error != null) failed[work.note.id] = work to error
                }
                all.update { it.copy(draftPersistence = when {
                    pending.isNotEmpty() -> DraftPersistence.SAVING
                    failed.isNotEmpty() -> DraftPersistence.FAILED
                    else -> DraftPersistence.SAVED
                }, message = if (failed.isNotEmpty()) errorMessage(failed.values.first().second) else it.message) }
            }
        }
    }
    private suspend fun awaitDrafts() {
        draftJob?.join()
        failed.values.firstOrNull()?.let { throw it.second }
    }
    fun retryDrafts() = action {
        val retry = failed.values.map { it.first.note }
        retry.forEach { enqueue(it) }
        awaitDrafts()
    }
    fun update(id: Long, title: String, text: String, pinned: Boolean = false) {
        if (id > 0 && !all.value.busy) setDraft(TrackNote(id, title.take(1000), text.take(100_000), pinned), true)
    }
    fun save() = action {
        awaitDrafts(); repository.save(snapshot()); reload()
        all.update { it.copy(message = "Заметка сохранена", draftPersistence = DraftPersistence.IDLE) }
    }
    fun delete(id: Long) = action {
        awaitDrafts(); repository.delete(id); reload()
        if (editId.value == id) setDraft(TrackNote(id, "", ""), false)
    }
    fun restore(id: Long) = action {
        awaitDrafts(); repository.restore(id); reload()
        if (editId.value == id) setDraft(all.value.notes.firstOrNull { it.note.id == id && !it.damaged }?.note ?: TrackNote(id, "", ""), false)
    }
    /** Back navigation is acknowledged only after the pending IO barrier succeeds. */
    fun leave(onCommitted: () -> Unit) = action { awaitDrafts(); onCommitted() }
    /** Explicitly confirmed discard: no storage deletion, key reset or authorization bypass.
     * Clear only volatile pending edits; retain every already-committed encrypted file.
     */
    fun leaveDiscardingAfterConfirmation(onLeave: () -> Unit) = action {
        pending.clear(); failed.clear(); draftJob?.join()
        setDraft(TrackNote(editId.value, "", ""), false)
        onLeave()
    }
    fun flushForBackground() = action { awaitDrafts() }
    fun resetAfterConfirmation() = action {
        pending.clear(); failed.clear()
        // Do NOT cancel an IO write and then delete/reset keys underneath it.
        // Let any captured write finish before removing storage and key material.
        draftJob?.join()
        repository.resetAfterConfirmation(); setDraft(TrackNote(1, "", ""), false); reload()
    }
    fun importDocument(bytes: ByteArray) = action {
        awaitDrafts(); repository.importDocument(bytes); reload()
        val id = editId.value
        setDraft(repository.draft(id) ?: all.value.notes.firstOrNull { it.note.id == id && !it.damaged }?.note ?: TrackNote(id, "", ""), false)
    }
    fun retryLoad() = action { reload() }
    fun removeCorruptedAfterConfirmation(id: Long) = action {
        awaitDrafts(); repository.removeCorrupted(id); reload()
        if (editId.value == id) setDraft(TrackNote(id, "", ""), false)
    }
    fun emptyTrashAfterConfirmation() = action { awaitDrafts(); repository.emptyTrashAfterConfirmation(); reload() }
    fun checkIntegrity() = action {
        all.update { it.copy(integrityChecked = 0, integrityTotal = 0) }
        val summary = repository.checkIntegrity { checked, total ->
            all.update { it.copy(integrityChecked = checked, integrityTotal = total) }
        }
        reload()
        all.update { it.copy(message = "Проверено: ${summary.checked}; восстановлено: ${summary.recovered}; повреждено: ${summary.damaged}") }
    }
    fun prepareImport(bytes: ByteArray) = action {
        pendingImport = null; all.update { it.copy(importCount = null) }
        val captured = bytes.copyOf() // Caller/URI mutations cannot change the confirmed payload.
        val count = repository.previewImport(captured)
        pendingImport = captured; all.update { it.copy(importCount = count) }
    }
    fun cancelImport() { if (!all.value.busy) { pendingImport = null; all.update { it.copy(importCount = null) } } }
    fun applyPreparedImport() = action {
        val bytes = pendingImport ?: throw NoteException(NoteError.InvalidInput)
        awaitDrafts(); repository.importDocument(bytes); pendingImport = null
        reload(); all.update { it.copy(importCount = null, message = "Импорт завершён") }
        val id = editId.value
        setDraft(repository.draft(id) ?: all.value.notes.firstOrNull { it.note.id == id && !it.damaged }?.note ?: TrackNote(id, "", ""), false)
    }
    suspend fun export(): ByteArray = repository.export()
    private suspend fun reload() {
        val notes = repository.list(); val trash = repository.trash()
        all.update { it.copy(notes = notes, trash = trash, damagedCount = notes.count { note -> note.damaged }) }
    }
    private fun errorMessage(e: Exception): String = if (e is NoteException) when (e.reason) {
        NoteError.KeyUnavailable -> "Ключ недоступен. Не удаляйте данные без отдельного подтверждения сброса."
        NoteError.Corrupted -> "Обнаружено повреждение: валидная предыдущая копия недоступна."
        NoteError.TooLarge -> "Превышен лимит 5 МБ или 10 000 заметок."
        NoteError.UnsupportedVersion -> "Версия файла не поддерживается."
        NoteError.InvalidInput -> "Неверный формат или ID отсутствует в библиотеке."
        NoteError.Io -> "Ошибка ввода/вывода. Черновик не подтверждён как сохранённый."
    } else "Не удалось выполнить файловую операцию. Черновик не подтверждён как сохранённый."
    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        if (all.value.busy) return@launch
        all.update { it.copy(busy = true, message = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { all.update { it.copy(message = errorMessage(e)) } }
        finally { all.update { it.copy(busy = false) } }
    }
    class Factory(private val repo: TrackNotes) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            TrackNotesViewModel(repo, extras.createSavedStateHandle()) as T
    }
}
