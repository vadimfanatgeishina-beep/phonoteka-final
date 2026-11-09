package com.phonoteka.ui.screens
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonoteka.diagnostics.RecompositionProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
@Composable fun Lab9MeasurementsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var payload by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val bytes = payload; payload = null
        if (uri == null || bytes == null) message = "Экспорт отменён" else scope.launch {
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("I/O") }; message = "Сохранены фактические счётчики этого debug-прогона" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Ошибка экспорта" }
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ЛР9 · собственные измерения", style = MaterialTheme.typography.titleLarge)
        Text("Счётчик фиксирует committed compositions, включая первую композицию. Это НЕ FrameTiming/CPU, не Layout Inspector и не исторический baseline. Запустите окно, вернитесь в список и выполните один сценарий: tick, поиск, scroll или delete. Затем остановите и сохраните CSV.")
        Button(onClick = { RecompositionProbe.start(); message = "Окно начато; вернитесь к сценарию" }) { Text("Начать окно измерения") }
        Button(onClick = {
            try { payload = RecompositionProbe.stopCsv(); exporter.launch("owned-compose-counts.csv") }
            catch (_: IllegalStateException) { message = "Сначала начните окно измерения" }
        }) { Text("Остановить и экспортировать") }
        com.phonoteka.ui.components.AppBackTextButton(onBack)
        message?.let { Text(it) }
    }
}
