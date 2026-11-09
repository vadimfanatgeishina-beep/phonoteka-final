package com.phonoteka.ui.screens

import android.Manifest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.PhonotekaApp
import com.phonoteka.security.HmacTextFrameSigner
import com.phonoteka.security.LocalLiveSocket
import com.phonoteka.security.PeerLab15WssServer
import com.phonoteka.security.PeerWssInvite
import com.phonoteka.security.PrincipalChangeSignal
import com.phonoteka.security.SocketPrincipal
import com.phonoteka.security.SocketUserBinding
import com.phonoteka.security.UserBoundSocketKeys
import com.phonoteka.ui.components.OwnedQrScanner
import com.phonoteka.ui.components.qrImage
import com.phonoteka.ui.permissions.PermissionRequestButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.spec.SecretKeySpec

/** Two real Android devices exchange signed WSS frames over a hotspot, without adb or a computer. */
@Composable
fun Lab15PeerScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhonotekaApp
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf<PeerLab15WssServer?>(null) }
    var invite by remember { mutableStateOf<PeerWssInvite?>(null) }
    var client by remember { mutableStateOf<okhttp3.OkHttpClient?>(null) }
    var socket by remember { mutableStateOf<LocalLiveSocket?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var peerStats by remember { mutableStateOf(Triple(0, 0, 0)) }
    var remainingSeconds by remember { mutableStateOf(0L) }

    DisposableEffect(host) { onDispose { host?.let { runCatching { it.close() } } } }
    DisposableEffect(socket, client) { onDispose {
        socket?.close()
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
    } }
    LaunchedEffect(host) {
        while (host != null) {
            host?.let {
                peerStats = Triple(it.accepted.get(), it.requests.get(), it.rejected.get())
                remainingSeconds = ((it.invite.expiresAt - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            }
            delay(1000)
        }
    }
    LaunchedEffect(socket) {
        if (socket != null) PrincipalChangeSignal.changes(app.principalChanges.epoch, app.roleSession.changes).collect {
            socket?.onPrincipalChanged()
        }
    }

    val metrics = socket?.metrics?.collectAsStateWithLifecycle()?.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.phonoteka.ui.components.AppBackTextButton(onBack)
        Text("WSS между двумя телефонами", style = MaterialTheme.typography.titleLarge)
        Text("Оба телефона подключите к одной точке доступа. На втором запустите сервер, на первом сканируйте QR. TLS-сертификат и ключ стенда создаются заново; QR передаёт отпечаток сертификата. Компьютер и интернет не нужны.")
        Button(modifier = Modifier.testTag("peer-host"), enabled = !busy && host == null, onClick = {
            busy = true; message = null
            scope.launch {
                try { host = withContext(Dispatchers.IO) { PeerLab15WssServer.start() } }
                catch (e: Exception) { message = e.message ?: "Сервер не запустился" }
                finally { busy = false }
            }
        }) { Text("Телефон 2 · Запустить WSS-сервер") }
        host?.let { current ->
            Text("Сервер ${current.invite.host}:${current.invite.port} · QR действует ещё ${remainingSeconds} с. Подключений: ${peerStats.first}, запросов: ${peerStats.second}, отказов: ${peerStats.third}")
            if (remainingSeconds > 0) {
                Image(remember(current) { qrImage(current.invite.toQr(), 360) },
                    contentDescription = "QR подключения к WSS на втором телефоне", modifier = Modifier.size(220.dp).testTag("peer-qr"))
            } else Text("QR истёк. Перезапустите сервер для нового кода.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { host = null }) { Text("Остановить сервер") }
        }
        PermissionRequestButton(Manifest.permission.CAMERA, "Телефон 1 · Сканировать WSS QR",
            "Камера нужна только для чтения QR локального сервера. Кадры не сохраняются.",
            onGranted = { scanning = true })
        if (scanning) OwnedQrScanner(Modifier.fillMaxWidth().height(280.dp),
            onCode = { raw ->
                scanning = false
                invite = PeerWssInvite.parse(raw)
                message = if (invite == null) "QR подключения повреждён или истёк" else "Сервер найден. Подключитесь."
            }, onError = { scanning = false; message = "Камера недоступна" },
            accepts = { it.startsWith("phonoteka-wss://connect?") })
        invite?.let { chosen ->
            Text("Адрес: ${chosen.host}:${chosen.port}. Отпечаток TLS проверяется по QR.")
            Button(enabled = !busy && metrics?.connected != true, onClick = {
                try {
                    socket?.close()
                    val nextClient = PeerLab15WssServer.client(chosen)
                    val keys = UserBoundSocketKeys(chosen.key)
                    val binding = SocketUserBinding(keys, currentPrincipal = {
                        val snapshot = app.localAuth.authenticationSnapshot()
                        val actor = app.roleSession.authority.auditActor(app.roleSession.session)
                        if (snapshot.authenticatedAt == null || actor.id == "unverified") null
                        else SocketPrincipal(actor.id.replace(':', '.'), snapshot.version)
                    })
                    val nextSocket = LocalLiveSocket(nextClient,
                        HmacTextFrameSigner(SecretKeySpec(chosen.key, "HmacSHA256")), scope,
                        userBinding = binding, allowedHost = chosen.host)
                    client = nextClient; socket = nextSocket
                    nextSocket.connect(chosen.url())
                    message = "Устанавливается TLS/WSS соединение"
                } catch (e: Exception) { message = e.message ?: "Не удалось подключиться" }
            }) { Text("Подключиться к телефону 2") }
            metrics?.let { state ->
                Text("WSS: ${if (state.connected) "подключено" else "не подключено"}; отправлено ${state.sent}, принято ${state.received}, отклонено ${state.rejected}")
                state.terminalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = state.connected, onClick = {
                    message = if (socket?.sendMetricRequest() == true) "Подписанный запрос отправлен" else "Запрос не ушёл"
                }) { Text("Отправить подписанный запрос") }
                Button(onClick = { socket?.close(); message = "Соединение закрыто" }) { Text("Закрыть соединение") }
            }
        }
        message?.let { Text(it) }
    }
}
