package com.phonoteka.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.data.freenotes.FreeNote
import com.phonoteka.ui.components.AppButton
import com.phonoteka.ui.components.AppButtonSize
import com.phonoteka.ui.components.AppButtonStyle
import com.phonoteka.viewmodel.FreeNotesState
import com.phonoteka.viewmodel.FreeNotesViewModel
import com.phonoteka.viewmodel.NotesSaveStatus

object FreeNotesTags {
    const val LIST = "notesList"
    const val NEW = "newNoteButton"
    const val TITLE = "noteTitle"
    const val BODY = "noteBody"
    const val STATUS = "noteSaveStatus"
    const val EXPORT = "notesExport"
    const val IMPORT = "notesImport"
    const val START_FRESH = "notesStartFresh"
    const val DELETE = "noteDelete"
    fun row(id: String) = "noteRow_$id"
}

fun saveStatusText(s: NotesSaveStatus): String = when (s) {
    NotesSaveStatus.IDLE -> "Без изменений"
    NotesSaveStatus.PENDING -> "Есть несохранённые изменения"
    NotesSaveStatus.SAVING -> "Сохранение…"
    NotesSaveStatus.SAVED -> "Сохранено"
    NotesSaveStatus.ERROR -> "Ошибка сохранения"
}

/** ЛР11: самостоятельные заметки (CRUD + автосохранение + SAF). Никаких storage-разрешений. */
@Composable
fun FreeNotesScreen(viewModel: FreeNotesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Уход в фон / смена приложения — сохранить немедленно.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.flush() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel.flush() }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        viewModel.exportTo(uri?.toString())
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importFrom(uri?.toString())
    }

    BackHandler(enabled = state.editingId != null) { viewModel.closeEditor() }

    FreeNotesContent(
        state = state,
        onBack = { viewModel.flush(); onBack() },
        onNew = viewModel::create,
        onOpen = viewModel::open,
        onTitle = viewModel::updateTitle,
        onBody = viewModel::updateBody,
        onDelete = viewModel::delete,
        onCloseEditor = viewModel::closeEditor,
        onExport = { exportLauncher.launch("phonoteka-notes.json") },
        onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
        onStartFresh = viewModel::startFresh,
        onConfirmImport = viewModel::confirmImport,
        onCancelImport = viewModel::cancelImport,
        onDismissMessage = viewModel::dismissMessage
    )
}

@Composable
fun FreeNotesContent(
    state: FreeNotesState,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onTitle: (String, String) -> Unit,
    onBody: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onCloseEditor: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onStartFresh: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Заметки · ЛР11", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            com.phonoteka.ui.components.AppBackTextButton(onBack)
        }
        val statusText = saveStatusText(state.saveStatus)
        Text(statusText, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(FreeNotesTags.STATUS).semantics { stateDescription = statusText; liveRegion = LiveRegionMode.Polite })

        state.message?.let { msg ->
            Card(Modifier.fillMaxWidth().clickable(onClick = onDismissMessage)) {
                Text(msg, Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Assertive })
            }
        }

        if (!state.loaded) { Text("Загрузка…"); return@Column }

        if (state.loadError != null) {
            Text("Редактирование отключено, пока файл повреждён.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(Modifier.testTag(FreeNotesTags.IMPORT), text = "Импорт копии", onClick = onImport, style = AppButtonStyle.PRIMARY, size = AppButtonSize.MEDIUM)
                AppButton(Modifier.testTag(FreeNotesTags.START_FRESH), text = "Начать заново", onClick = onStartFresh, style = AppButtonStyle.OUTLINED, size = AppButtonSize.MEDIUM)
            }
        } else {
            val editing = state.editing
            if (editing != null) {
                NoteEditor(editing, onTitle, onBody, onDelete, onCloseEditor)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(Modifier.testTag(FreeNotesTags.NEW), text = "Новая заметка", onClick = onNew, style = AppButtonStyle.PRIMARY, size = AppButtonSize.MEDIUM)
                    AppButton(Modifier.testTag(FreeNotesTags.EXPORT), text = "Экспорт", onClick = onExport, style = AppButtonStyle.SECONDARY, size = AppButtonSize.SMALL)
                    AppButton(Modifier.testTag(FreeNotesTags.IMPORT), text = "Импорт", onClick = onImport, style = AppButtonStyle.OUTLINED, size = AppButtonSize.SMALL)
                }
                if (state.notes.isEmpty()) Text("Заметок пока нет")
                LazyColumn(Modifier.fillMaxWidth().testTag(FreeNotesTags.LIST), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.notes.sortedByDescending { it.updatedAt }, key = { it.id }) { note -> NoteRow(note, onOpen) }
                }
            }
        }
    }

    state.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.importing) onCancelImport() },
            title = { Text("Заменить заметки?") },
            text = { Column {
                Text("В файле заметок: ${preview.count}. Текущие заметки (${state.notes.size}) будут заменены.")
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(onClick = onConfirmImport, enabled = !state.importing, modifier = Modifier.testTag("importConfirm")) { Text(if (state.importing) "Сохранение…" else "Заменить") } },
            dismissButton = { TextButton(onClick = onCancelImport, enabled = !state.importing, modifier = Modifier.testTag("importCancel")) { Text("Отмена") } }
        )
    }
}

@Composable
private fun NoteRow(note: FreeNote, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(FreeNotesTags.row(note.id)).clickable { onOpen(note.id) }) {
        Column(Modifier.padding(12.dp)) {
            Text(note.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.body.isNotBlank()) Text(note.body, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (note.updatedAt > 0) "Изменено: " + java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(note.updatedAt))
                else "Дата изменения неизвестна", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NoteEditor(
    note: FreeNote,
    onTitle: (String, String) -> Unit,
    onBody: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit
) {
    var confirmDelete by remember(note.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = note.title, onValueChange = { onTitle(note.id, it) }, label = { Text("Заголовок") },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag(FreeNotesTags.TITLE))
        OutlinedTextField(value = note.body, onValueChange = { onBody(note.id, it) }, label = { Text("Текст") },
            minLines = 6, modifier = Modifier.fillMaxWidth().testTag(FreeNotesTags.BODY))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(Modifier, text = "Готово", onClick = onClose, style = AppButtonStyle.PRIMARY, size = AppButtonSize.MEDIUM)
            AppButton(Modifier.testTag(FreeNotesTags.DELETE), text = "Удалить", onClick = { confirmDelete = true }, style = AppButtonStyle.OUTLINED, size = AppButtonSize.MEDIUM)
            // ЛР16 hardening: текст помечается sensitive и стирается из буфера через 30 с.
            val context = androidx.compose.ui.platform.LocalContext.current
            AppButton(Modifier.testTag("noteCopy"), text = "Копировать", onClick = {
                com.phonoteka.security.SensitiveClipboard.copy(context, note.body)
            }, style = AppButtonStyle.OUTLINED, size = AppButtonSize.MEDIUM)
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить заметку?") },
        text = { Text("Заметка будет удалена. Это действие нельзя отменить после сохранения.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(note.id) }, modifier = Modifier.testTag("freeNoteDeleteConfirm")) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, modifier = Modifier.testTag("freeNoteDeleteCancel")) { Text("Отмена") } })
}
