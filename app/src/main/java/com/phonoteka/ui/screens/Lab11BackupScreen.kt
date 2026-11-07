package com.phonoteka.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonoteka.data.PhonotekaRepository
import kotlinx.coroutines.launch
import android.net.Uri

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun Lab11BackupScreen(repository: PhonotekaRepository, onBack: () -> Unit, onNotes: () -> Unit = {}) {
    val mayWrite = com.phonoteka.security.DemoScope.LIBRARY_WRITE in com.phonoteka.ui.LocalDemoPermissions.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = runCatching {
                val json = repository.exportBackup()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                        ?: error("Не удалось открыть файл для записи")
                }
                "Резервная копия сохранена"
            }.getOrElse { "Ошибка экспорта: ${it.message ?: "неизвестная ошибка"}" }
            busy = false
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImport = uri
    }
    fun importSelected(uri: Uri) {
        scope.launch {
            busy = true
            message = runCatching {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
                input.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 25 * 1024 * 1024) { "Файл больше 25 МБ" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                }
                "Импортировано треков: ${repository.importBackup(bytes.toString(Charsets.UTF_8))}"
            }.getOrElse { "Ошибка импорта: ${it.message ?: "неизвестная ошибка"}" }
            busy = false
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Резервная копия · ЛР11") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Назад") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Экспорт и восстановление каталога треков и кандидатов в JSON.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { exportPicker.launch("phonoteka-backup.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Экспортировать")
            }
            Button(onClick = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) }, enabled = mayWrite && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Импортировать")
            }
            Button(onClick = onNotes, enabled = !busy) { Text("Заметки треков · файловый PRO") }
            if (busy) CircularProgressIndicator()
            message?.let { Text(it, color = if (it.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        }
    }
    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Заменить текущую библиотеку?") },
            text = { Text("Импорт заменит каталог треков и кандидатов. Экспортируйте текущую библиотеку перед продолжением.") },
            confirmButton = { TextButton(onClick = { pendingImport = null; importSelected(uri) }) { Text("Импортировать") } },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Отмена") } }
        )
    }
}
