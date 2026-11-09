package com.phonoteka.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phonoteka.data.notes.AndroidJsonDocuments
import com.phonoteka.data.notes.AttachmentLimits
import com.phonoteka.data.notes.NoteAttachment
import com.phonoteka.viewmodel.DraftPersistence
import com.phonoteka.viewmodel.TrackNotesViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackNotesScreen(viewModel: TrackNotesViewModel, onBack: () -> Unit) {
    val mayWrite = com.phonoteka.security.DemoScope.LIBRARY_WRITE in com.phonoteka.ui.LocalDemoPermissions.current
    val context = LocalContext.current
    val documents = remember(context) { AndroidJsonDocuments(context.contentResolver) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BackHandler { if (!state.busy) viewModel.leave(onBack) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushForBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    var pendingDamagedDelete by remember { mutableStateOf<Long?>(null) }
    var showTrash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var idText by remember { mutableStateOf("1") }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var previewAttachment by remember { mutableStateOf<NoteAttachment?>(null) }
    var showProtection by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) fileMessage = "Экспорт отменён" else scope.launch {
            try { documents.write(uri, viewModel.export()); fileMessage = "Экспорт v2 сохранён (обычный JSON; не секреты сессии)" }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { fileMessage = "Ошибка экспорта" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) fileMessage = "Импорт отменён" else scope.launch {
            try { viewModel.prepareImport(documents.read(uri)); fileMessage = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { fileMessage = "Ошибка чтения файла или превышен лимит 5 МБ" }
        }
    }
    // ЛР11: выбор картинки для вложения в заметку.
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) fileMessage = "Выбор картинки отменён" else scope.launch {
            try {
                val image = prepareNoteImage(context, uri)
                viewModel.attachImage(draft.id, image.name, "image/jpeg", image.bytes)
                fileMessage = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fileMessage = "Не удалось прочитать фото. Выбери другое изображение." }
        }
    }
    // ЛР11: зашифрованный архив — единственный формат, который возвращает картинки.
    val archiveExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) fileMessage = "Экспорт архива отменён" else scope.launch {
            try {
                documents.write(uri, viewModel.exportArchive())
                fileMessage = "Зашифрованный архив сохранён: заметки вместе с картинками"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fileMessage = "Ошибка экспорта архива" }
        }
    }
    val archiveImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) fileMessage = "Импорт архива отменён" else scope.launch {
            try { viewModel.prepareArchiveImport(documents.read(uri)); fileMessage = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { fileMessage = "Файл не является корректным архивом заметок — импорт не выполнен" }
        }
    }
    val editorDamaged = state.notes.any { it.note.id == draft.id && it.damaged }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Заметки к музыке", style = MaterialTheme.typography.headlineSmall)
        Text("Сохраняй мысли о треках и добавляй фотографии к заметкам.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.phonoteka.ui.components.AppBackTextButton({ viewModel.leave(onBack) }, enabled = !state.busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showTrash, onClick = { showTrash = false }, label = { Text("Заметки") })
            FilterChip(selected = showTrash, onClick = { showTrash = true }, label = { Text("Корзина") })
        }
        if (state.damagedCount > 0) Text("Повреждено заметок: ${state.damagedCount}. Здоровые заметки доступны.", color = MaterialTheme.colorScheme.error)
        if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Проверено/обработано: ${state.integrityChecked} из ${state.integrityTotal}") }
        TextButton(onClick = { showProtection = !showProtection }) { Text(if (showProtection) "Скрыть сведения о защите" else "Как защищены заметки") }
        if (showProtection) {
            Text("Заметки и фотографии зашифрованы. Корзина хранит удалённые записи 30 дней.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { viewModel.checkIntegrity() }, enabled = !state.busy) { Text("Проверить хранилище") }
        }
        if (!showTrash) {
            HorizontalDivider()
            Text("Редактор", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(idText, { idText = it.take(18) }, label = { Text("ID трека") })
            Button(onClick = { idText.toLongOrNull()?.takeIf { it > 0 }?.let { viewModel.edit(it) } }, enabled = !state.busy) { Text("Открыть заметку / черновик") }
            Text("Редактируем ID ${draft.id}")
            OutlinedTextField(draft.title, { viewModel.update(draft.id, it, draft.text, draft.pinned) }, label = { Text("Заголовок") }, readOnly = !mayWrite || state.busy || editorDamaged)
            OutlinedTextField(draft.text, { viewModel.update(draft.id, draft.title, it, draft.pinned) }, label = { Text("Текст") }, readOnly = !mayWrite || state.busy || editorDamaged, minLines = 3)
            Row { Checkbox(draft.pinned, { viewModel.update(draft.id, draft.title, draft.text, it) }, enabled = mayWrite && !state.busy && !editorDamaged); Text("Закрепить") }
            // ЛР11: вложения заметки.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { attachmentPicker.launch(arrayOf("image/*")) },
                    enabled = mayWrite && !state.busy && !editorDamaged && draft.attachments.size < AttachmentLimits.MAX_COUNT) { Text("Прикрепить фото") }
                Text("${draft.attachments.size} из ${AttachmentLimits.MAX_COUNT}")
            }
            Text("Большие снимки уменьшаются для зашифрованного хранилища. Нажми на миниатюру для просмотра.",
                style = MaterialTheme.typography.bodySmall)
            draft.attachments.forEach { attachment ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoteImagePreview(attachment, 72, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { previewAttachment = attachment })
                    Text("${attachment.fileName} · ${attachment.sizeBytes / 1024} КБ", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.removeAttachment(draft.id, attachment.id) },
                        enabled = mayWrite && !state.busy) { Text("Убрать") }
                }
            }
            Text(when (state.draftPersistence) {
                DraftPersistence.IDLE -> "Нет новых правок черновика"
                DraftPersistence.SAVING -> "Черновик сохраняется — запись ещё не подтверждена"
                DraftPersistence.SAVED -> "Черновик сохранён в зашифрованном локальном хранилище"
                DraftPersistence.FAILED -> "Черновик не сохранён: повторите запись"
            })
            if (state.draftPersistence == DraftPersistence.FAILED) {
                Button(onClick = { viewModel.retryDrafts() }, enabled = mayWrite && !state.busy) { Text("Повторить запись черновика") }
                TextButton(onClick = { confirmDiscard = true }, enabled = !state.busy) { Text("Уйти без несохранённых правок") }
            }
            Button(onClick = { viewModel.save() }, enabled = mayWrite && !state.busy && !editorDamaged && (draft.title.isNotBlank() || draft.text.isNotBlank())) { Text("Сохранить") }
        }
        HorizontalDivider()
        Text(if (showTrash) "Корзина" else "Мои заметки", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(searchText, { searchText = it; viewModel.search(it) }, label = { Text("Поиск: заголовок/текст") })
        if (showTrash) {
            if (state.trash.isEmpty()) Text("Корзина пуста")
            state.trash.forEach { row ->
                Card(Modifier.fillMaxWidth().testTag("trash-note-${row.item.note.id}")) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${row.item.note.id}: ${row.item.note.title}")
                        Text("Удалено: ${noteDate(row.deletedAt)}")
                        if (row.item.damaged) Text("Повреждена — восстановление недоступно", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { viewModel.restore(row.item.note.id) }, enabled = mayWrite && !state.busy && !row.item.damaged) { Text("Восстановить") }
                    }
                }
            }
            Button(onClick = { confirmEmptyTrash = true }, enabled = mayWrite && !state.busy && state.trash.isNotEmpty()) { Text("Очистить корзину") }
        } else {
            if (state.notes.isEmpty()) Text("Заметок пока нет")
            state.notes.forEach { row ->
                Card(Modifier.fillMaxWidth().testTag("note-card-${row.note.id}").then(
                    if (!row.damaged) Modifier.combinedClickable(
                        enabled = !state.busy,
                        onClick = { idText = row.note.id.toString(); viewModel.edit(row.note.id) },
                        onLongClick = { if (mayWrite) pendingDelete = row.note.id }
                    ) else Modifier // No disabled parent semantics around the usable damaged-delete button.
                )) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${row.note.id}: ${row.note.title}")
                        if (row.damaged) {
                            Text("Повреждена: нет валидной основной и предыдущей копии", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { pendingDamagedDelete = row.note.id }, enabled = mayWrite && !state.busy) { Text("Удалить повреждённую безвозвратно") }
                        } else {
                            Text(row.note.text.lineSequence().firstOrNull().orEmpty())
                            Text("Изменено: ${noteDate(row.note.updatedAt)}")
                            if (row.recovered) Text("Восстановлена предыдущая копия")
                            Row {
                                TextButton(onClick = { idText = row.note.id.toString(); viewModel.edit(row.note.id) }, enabled = !state.busy) { Text("Открыть") }
                                TextButton(onClick = { pendingDelete = row.note.id }, enabled = mayWrite && !state.busy) { Text("В корзину") }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("Резервная копия", style = MaterialTheme.typography.titleMedium)
        Text("Для переноса фото выбирай зашифрованный архив. JSON содержит только текст заметок.",
            style = MaterialTheme.typography.bodySmall)
        // ЛР11: зашифрованный архив сохраняет и восстанавливает вложенные картинки.
        Button(onClick = { archiveExporter.launch("phonoteka-notes-archive.bin") }, enabled = !state.busy) {
            Text("Сохранить архив с фотографиями")
        }
        Button(onClick = { archiveImporter.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
            enabled = mayWrite && !state.busy) { Text("Восстановить архив с фотографиями") }
        TextButton(onClick = { exporter.launch("phonoteka-track-notes-v2.json") }, enabled = !state.busy) { Text("Экспортировать только текст (JSON)") }
        TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }, enabled = mayWrite && !state.busy) { Text("Импортировать только текст (JSON)") }
        if (state.message?.startsWith("Ключ недоступен") == true) Button(onClick = { confirmReset = true }, enabled = mayWrite && !state.busy) { Text("Предложить сброс файлового хранилища") }
        if (state.message != null) Button(onClick = { viewModel.retryLoad() }, enabled = !state.busy) { Text("Повторить загрузку") }
        state.message?.let { Text(it) }; fileMessage?.let { Text(it) }
    }
    previewAttachment?.let { attachment -> AlertDialog(onDismissRequest = { previewAttachment = null },
        title = { Text("Фото заметки") },
        text = { NoteImagePreview(attachment, 1200, Modifier.fillMaxWidth().heightIn(max = 480.dp)) },
        confirmButton = { TextButton(onClick = { previewAttachment = null }) { Text("Закрыть") } }) }
    pendingDelete?.let { id -> AlertDialog(onDismissRequest = { pendingDelete = null },
        title = { Text("Переместить заметку в корзину?") }, text = { Text("ID $id. Восстановление доступно 30 дней.") },
        confirmButton = { TextButton(onClick = { pendingDelete = null; viewModel.delete(id) }, enabled = !state.busy) { Text("Подтвердить удаление") } },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } }) }
    pendingDamagedDelete?.let { id -> AlertDialog(onDismissRequest = { pendingDamagedDelete = null },
        title = { Text("Удалить повреждённую заметку навсегда?") }, text = { Text("Только ID $id. Здоровые заметки не удаляются.") },
        confirmButton = { TextButton(onClick = { pendingDamagedDelete = null; viewModel.removeCorruptedAfterConfirmation(id) }, enabled = !state.busy) { Text("Удалить безвозвратно") } },
        dismissButton = { TextButton(onClick = { pendingDamagedDelete = null }) { Text("Отмена") } }) }
    if (confirmEmptyTrash) AlertDialog(onDismissRequest = { confirmEmptyTrash = false },
        title = { Text("Очистить всю корзину?") }, text = { Text("Восстановить удалённые заметки после этого нельзя.") },
        confirmButton = { TextButton(onClick = { confirmEmptyTrash = false; viewModel.emptyTrashAfterConfirmation() }, enabled = !state.busy) { Text("Подтверждаю очистку") } },
        dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("Отмена") } })
    state.importCount?.let { count -> AlertDialog(onDismissRequest = { viewModel.cancelImport() },
        title = { Text(if (state.importIsArchive) "Зашифрованный архив: заметок $count" else "Найдено заметок: $count") },
        text = { Text(if (state.importIsArchive)
            "Архив проверен и расшифрован. Импорт восстановит заметки ВМЕСТЕ с вложенными картинками. Заменить текущие заметки? Сначала сохраните экспорт."
        else "Файл прочитан и проверен. Заменить текущие заметки? Сначала сохраните экспорт. Пустой импорт очистит активный список.") },
        confirmButton = { TextButton(onClick = { if (state.importIsArchive) viewModel.applyPreparedArchiveImport() else viewModel.applyPreparedImport() },
            enabled = mayWrite && !state.busy) { Text("Заменить заметки") } },
        dismissButton = { TextButton(onClick = { viewModel.cancelImport() }, enabled = !state.busy) { Text("Отмена") } }) }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("Отбросить несохранённые правки?") }, text = { Text("Уже сохранённые зашифрованные файлы и ключи не удаляются.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.leaveDiscardingAfterConfirmation(onBack) }) { Text("Отбросить и уйти") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Остаться") } })
    if (confirmReset && mayWrite) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("Удалить только заметки треков?") }, text = { Text("Заметки, backup, корзина и черновики будут удалены. Библиотека и токены не затрагиваются.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetAfterConfirmation() }) { Text("Подтверждаю удаление заметок") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } })
}
private fun noteDate(value: Long): String = if (value <= 0) "неизвестна (старый формат)" else DateFormat.getDateTimeInstance().format(Date(value))

@Composable
private fun NoteImagePreview(attachment: NoteAttachment, maxSide: Int, modifier: Modifier = Modifier) {
    val image by produceState<Bitmap?>(null, attachment.data, maxSide) {
        value = withContext(Dispatchers.Default) {
            val bytes = AttachmentLimits.decodeBase64(attachment.data) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    if (image != null) Image(image!!.asImageBitmap(), contentDescription = "Просмотр ${attachment.fileName}",
        modifier = modifier, contentScale = if (maxSide < 100) ContentScale.Crop else ContentScale.Fit)
    else Box(modifier, contentAlignment = Alignment.Center) { Text("Фото", style = MaterialTheme.typography.labelSmall) }
}
