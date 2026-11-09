package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonoteka.PhonotekaApp
import com.phonoteka.security.AuditEvent
import com.phonoteka.security.AuditSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Реальные события локального входа, PIN, восстановления и смены сессии. */
@Composable
fun AuthAuditScreen(onBack: () -> Unit) {
    val audit = (LocalContext.current.applicationContext as PhonotekaApp).unifiedAudit
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<AuditEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var invalid by remember { mutableStateOf(false) }
    suspend fun reload() {
        loading = true
        val result = withContext(Dispatchers.IO) { audit.readVerified() }
        invalid = result == null
        events = result.orEmpty().filter { it.source == AuditSource.AUTH || it.source == AuditSource.RECOVERY }.takeLast(100).asReversed()
        loading = false
    }
    LaunchedEffect(audit) { reload() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Журнал входов", style = MaterialTheme.typography.headlineSmall)
        Text("События настоящего локального входа: PIN, блокировка, восстановление и сессия. Пароли и коды в журнал не пишутся.")
        Button(onClick = { scope.launch { reload() } }, enabled = !loading) { Text("Обновить") }
        if (invalid) Text("Целостность журнала нарушена: записи не показываются.", color = MaterialTheme.colorScheme.error)
        else if (loading) CircularProgressIndicator()
        else if (events.isEmpty()) Text("Событий входа пока нет. Войдите заново и нажмите «Обновить».")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { event ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))} · ${event.type.wire} · ${event.result}", style = MaterialTheme.typography.titleSmall)
                        Text("${event.action} · ${event.subject}")
                        Text("Устройство: ${event.device ?: "неизвестно"}; сессия: ${event.sessionId ?: "—"}", style = MaterialTheme.typography.bodySmall)
                        event.reason?.let { Text("Причина: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
