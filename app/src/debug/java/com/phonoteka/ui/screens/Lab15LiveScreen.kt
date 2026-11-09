package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.BuildConfig
import com.phonoteka.security.AuditEventType
import com.phonoteka.security.AuditSource
import com.phonoteka.security.HmacTextFrameSigner
import com.phonoteka.security.LocalLiveSocket
import com.phonoteka.security.LocalMockTlsClient
import com.phonoteka.security.PrincipalChangeSignal
import com.phonoteka.security.SecurityProfile
import com.phonoteka.security.SocketPrincipal
import com.phonoteka.security.SocketUserBinding
import com.phonoteka.security.UserBoundSocketKeys
import javax.crypto.spec.SecretKeySpec

/**
 * ЛР15: «Открыть живой WSS» — воспроизводимый сценарий на СОБСТВЕННОМ локальном стенде.
 *
 * Сценарий: подключение → статус → сообщение → ошибка/повтор.
 * Никаких внешних хостов и реальных аккаунтов: адрес всегда localhost, ключи — учебные.
 *
 * Важно: OkHttp моделирует защищённый WebSocket-хендшейк как HTTPS, поэтому адрес
 * стенда вводится как wss://, а внутри превращается в https:// (см. normalizeLabWsUrl).
 */
@Composable
fun Lab15LiveScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScopeCompat()
    val app = LocalContext.current.applicationContext as com.phonoteka.PhonotekaApp
    val keys = remember { runCatching { UserBoundSocketKeys(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray()) }.getOrNull() }
    if (BuildConfig.LOCAL_FIXTURE_KEY.isEmpty() || keys == null) {
        Column(Modifier.padding(24.dp)) {
            Text("Локальный WSS отключён: debug-сборка не настроена на собственную фикстуру (ключ ≥ 16 байт). Реальные секреты не вводите.")
            com.phonoteka.ui.components.AppBackTextButton(onBack)
        }
        return
    }

    // ЛР15: текущий пользователь = проверенная учебная роль + активный локальный вход.
    // Иначе principal = null и подключение не создаётся вовсе.
    val binding = remember {
        SocketUserBinding(keys, currentPrincipal = {
            runCatching {
                val snapshot = app.localAuth.authenticationSnapshot()
                val actor = app.roleSession.authority.auditActor(app.roleSession.session)
                if (snapshot.authenticatedAt == null || actor.id == "unverified") null
                else SocketPrincipal(actor.id.replace(':', '.'), snapshot.version)
            }.getOrNull()
        })
    }
    val client = remember { LocalMockTlsClient.create(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray()) }
    val socket = remember {
        LocalLiveSocket(client, HmacTextFrameSigner(SecretKeySpec(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray(), "HmacSHA256")), scope,
            userBinding = binding)
    }
    val metrics by socket.metrics.collectAsStateWithLifecycle()
    val role by app.roleSession.changes.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }
    var lastActor by remember { mutableStateOf(app.roleSession.currentActorId) }

    // ЛР15: смена роли, пользователя, блокировка или выход сразу закрывают сокет.
    LaunchedEffect(socket) {
        PrincipalChangeSignal.changes(app.principalChanges.epoch, app.roleSession.changes).collect {
            socket.onPrincipalChanged()
            notice = "Пользователь или роль изменились — прежнее соединение закрыто"
            app.auditEvent(AuditEventType.WSS, AuditSource.NETWORK, "wss.user_changed", "channel",
                "CLOSED", reason = "Смена пользователя закрывает привязанное соединение")
        }
    }
    DisposableEffect(socket) {
        onDispose {
            socket.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    val principal = binding.currentPrincipal()
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Живой сетевой канал · ЛР15", style = MaterialTheme.typography.titleLarge)
        Text("Это проверка защищённого соединения с учебным сервером на компьютере. Для работы нужен запущенный WSS-стенд и adb reverse порта 8444. Без стенда подключение честно покажет ошибку.")
        Text("Порядок: подключиться → запросить статус → отправить подписанный запрос → увидеть рост счётчиков «Отправлено» и «Принято». Ошибка и повтор проверяют восстановление соединения.")

        Card(Modifier.testTag("lab15-state")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Роль: ${role.name}; учебный пользователь: ${app.roleSession.currentActorId}")
                Text("Привязка канала: ${metrics.boundUserTag?.let { "#$it" } ?: "—"}")
                Text("Соединение: ${if (metrics.connected) "установлено" else "нет"}; попыток: ${metrics.attempts}; переподключений: ${metrics.reconnects}")
                Text("Отправлено: ${metrics.sent}; принято: ${metrics.received}; отклонено: ${metrics.rejected}")
                metrics.terminalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        if (principal == null) {
            Text("Активного локального входа нет: сначала войдите в профиле. Соединение не создаётся.",
                color = MaterialTheme.colorScheme.error)
        }

        // --- Шаг 1: подключение ---
        Button(modifier = Modifier.testTag("lab15-connect"),
            enabled = principal != null && !metrics.connected,
            onClick = {
                notice = null
                app.auditEvent(AuditEventType.WSS, AuditSource.NETWORK, "wss.connect", "channel", "ATTEMPT")
                socket.connect("wss://localhost:8444/ws")
            }) { Text("1. Подключиться к своему стенду") }

        // --- Шаг 2: статус (сводка соединения) ---
        Button(modifier = Modifier.testTag("lab15-status"), enabled = metrics.connected,
            onClick = {
                notice = "Статус: соединение установлено, принято кадров — ${metrics.received}"
                app.auditEvent(AuditEventType.WSS, AuditSource.NETWORK, "wss.status", "channel", "OK")
            }) { Text("2. Показать состояние канала") }

        // --- Шаг 3: сообщение ---
        Button(modifier = Modifier.testTag("lab15-message"), enabled = metrics.connected,
            onClick = {
                val sent = socket.sendMetricRequest()
                notice = if (sent) "Подписанный запрос отправлен; ожидается ответ сервера. Счётчики обновятся ниже."
                    else "Запрос не ушёл: проверьте соединение и пользователя."
                app.auditEvent(AuditEventType.WSS, AuditSource.NETWORK, "wss.message", "channel", if (sent) "SENT" else "FAILED")
            }) { Text("3. Отправить настоящий запрос") }

        // --- Шаг 4: ошибка и повтор ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.testTag("lab15-error"),
                onClick = {
                    socket.close()
                    notice = "Соединение закрыто для проверки восстановления"
                    app.auditEvent(AuditEventType.WSS, AuditSource.NETWORK, "wss.error", "channel", "CLOSED", reason = "Ручной разрыв для проверки retry")
                }) { Text("4a. Смоделировать ошибку") }
            Button(modifier = Modifier.testTag("lab15-retry"), enabled = principal != null,
                onClick = {
                    notice = null
                    socket.connect("wss://localhost:8444/ws")
                    app.auditEvent(AuditEventType.WSS, AuditSource.NETWORK, "wss.retry", "channel", "ATTEMPT")
                }) { Text("4b. Повторить") }
        }

        // --- Смена пользователя: старое соединение обязано закрыться ---
        if (BuildConfig.DEBUG) {
            Text("Смена пользователя на стенде", style = MaterialTheme.typography.titleMedium)
            com.phonoteka.security.DemoRoleSession.FIXTURE_ACTORS.forEach { candidate ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(modifier = Modifier.testTag("lab15-switch-" + candidate.substringAfter(':')),
                        enabled = candidate != app.roleSession.currentActorId,
                        onClick = {
                            val changed = app.roleSession.switchUser(candidate)
                            lastActor = app.roleSession.currentActorId
                            notice = if (changed)
                                "Пользователь сменён на $candidate. Прежнее соединение закрыто, новое привязано к новому пользователю."
                            else "Смена не выполнена"
                            app.principalChanges.signal()
                            app.auditEvent(AuditEventType.WSS, AuditSource.NETWORK, "wss.switch_user", candidate,
                                "SWITCHED", reason = "Прежний сокет закрыт при смене пользователя")
                        }) { Text("Сменить на $candidate") }
                }
            }
            Text("Текущий учебный пользователь: ${app.roleSession.currentActorId}")
        }

        Text("Режим сборки: ${SecurityProfile.mode.shortLabel}. " +
            "Pinning: ${if (SecurityProfile.enforceCertificatePinning) "включён (fail-closed)" else "ослаблен (учебная сборка)"}.")
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        com.phonoteka.ui.components.AppBackTextButton(onBack)
    }
}

/** Локальная обёртка: scope нужен для жизненного цикла сокета. */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()
