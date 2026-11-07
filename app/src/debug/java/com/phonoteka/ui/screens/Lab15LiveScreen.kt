package com.phonoteka.ui.screens
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.BuildConfig
import com.phonoteka.security.*
import javax.crypto.spec.SecretKeySpec

@Composable fun Lab15LiveScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as com.phonoteka.PhonotekaApp
    val keys = remember { runCatching { UserBoundSocketKeys(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray()) }.getOrNull() }
    if (BuildConfig.LOCAL_FIXTURE_KEY.isEmpty() || keys == null) {
        Column(Modifier.padding(24.dp)) { Text("Локальный WSS отключён: debug-сборка не настроена на собственную фикстуру (ключ ≥ 16 байт). Реальные секреты не вводите."); TextButton(onClick = onBack) { Text("Назад") } }
        return
    }
    // ЛР15: текущий пользователь = проверенная учебная роль + активный локальный вход. Иначе — null (нет подключения).
    val binding = remember {
        SocketUserBinding(keys, currentPrincipal = {
            runCatching {
                val snapshot = app.localAuth.authenticationSnapshot()
                val actor = app.roleSession.authority.auditActor(app.roleSession.session)
                if (snapshot.authenticatedAt == null || actor.id == "unverified") null
                else SocketPrincipal(actor.id, snapshot.version)
            }.getOrNull()
        })
    }
    val client = remember { LocalMockTlsClient.create(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray()) }
    val socket = remember {
        LocalLiveSocket(client, HmacTextFrameSigner(SecretKeySpec(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray(), "HmacSHA256")), scope,
            userBinding = binding)
    }
    val metrics by socket.metrics.collectAsStateWithLifecycle()
    // ЛР15: смена роли, блокировка или выход сразу закрывают сокет (USER_CHANGED).
    LaunchedEffect(socket) {
        PrincipalChangeSignal.changes(app.principalChanges.epoch, app.roleSession.changes).collect { socket.onPrincipalChanged() }
    }
    DisposableEffect(socket) { onDispose { socket.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() } }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Owned local WSS · ЛР15")
        Text("localhost:8444/ws; TLS pin, билет пользователя на 30 с, ключ кадров на пользователя, ограниченный backoff")
        Button(onClick = { socket.connect("wss://localhost:8444/ws") }) { Text("Подключиться к своему стенду") }
        Button(onClick = { socket.close() }) { Text("Остановить") }
        Text("Пользователь: ${metrics.boundUserTag?.let { "#$it" } ?: "—"}")
        Text("connected=${metrics.connected}; attempts=${metrics.attempts}; reconnects=${metrics.reconnects}")
        Text("sent=${metrics.sent}; received=${metrics.received}; rejected=${metrics.rejected}")
        metrics.terminalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onBack) { Text("Назад") }
    }
}
