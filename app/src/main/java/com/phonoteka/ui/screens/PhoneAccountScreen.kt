package com.phonoteka.ui.screens

import android.Manifest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.identity.*
import com.phonoteka.ui.components.AppBackTextButton
import com.phonoteka.ui.components.OwnedQrScanner
import com.phonoteka.ui.components.qrImage
import com.phonoteka.ui.permissions.PermissionRequestButton
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PhoneAccountScreen(controller: PhoneAccountsController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var hosting by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var ownerLogin by remember { mutableStateOf("") }
    var ownerPassword by remember { mutableStateOf("") }
    var newLogin by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf(IdentityRole.EMPLOYEE) }
    var remaining by remember { mutableStateOf(0L) }
    LaunchedEffect(state.pairing) {
        while (state.pairing != null) {
            remaining = ((state.pairing!!.expiresAt - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            if (remaining == 0L) break
            delay(1000)
        }
    }
    fun date(value: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(value))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppBackTextButton(onBack)
        Text("Аккаунт и устройства", style = MaterialTheme.typography.headlineSmall)
        Text("Сервер на вашем втором телефоне подтверждает вход и права. Интернет не нужен: подключите телефоны к одной точке доступа. Музыкальная библиотека пока хранится отдельно на каждом устройстве.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = !hosting, onClick = { hosting = false }, label = { Text("Мой аккаунт") }, modifier = Modifier.testTag("account-client-tab"))
            FilterChip(selected = hosting, onClick = { hosting = true }, label = { Text("Телефон-сервер") }, modifier = Modifier.testTag("account-host-tab"))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("account-busy"))
        if (hosting) {
            Text("Телефон-сервер", style = MaterialTheme.typography.titleLarge)
            Text("Оставьте Фонотеку открытой на этом телефоне. При блокировке локального входа сервер остановится. Учётные записи сохранятся. После смены сети остановите и запустите сервер заново, затем повторите сопряжение.")
            if (!state.hostOpened) Button(enabled = !state.busy, onClick = controller::openHost,
                modifier = Modifier.testTag("account-open-host")) { Text("Открыть хранилище аккаунтов") }
            else if (state.needsOwner) {
                Text("Первый запуск: создайте владельца сервера. Это отдельный пароль, а не четырёхзначный PIN приложения.")
                OutlinedTextField(ownerLogin, { ownerLogin = it.take(64) }, label = { Text("Логин владельца") },
                    singleLine = true, enabled = !state.busy, modifier = Modifier.testTag("account-owner-login"))
                OutlinedTextField(ownerPassword, { ownerPassword = it.take(256) }, label = { Text("Пароль владельца · от 8 символов") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.busy,
                    modifier = Modifier.testTag("account-owner-password"))
                Button(enabled = !state.busy && ownerLogin.isNotBlank() && ownerPassword.length >= 8,
                    onClick = { controller.provisionOwner(ownerLogin, ownerPassword); ownerPassword = "" },
                    modifier = Modifier.testTag("account-provision")) { Text("Создать владельца") }
            } else if (state.hostEndpoint == null) {
                Text("Учётные записи готовы. Включите точку доступа и запустите сервер.")
                Button(enabled = !state.busy, onClick = controller::startHost, modifier = Modifier.testTag("account-start-host")) { Text("Запустить сервер аккаунтов") }
            }
            state.hostEndpoint?.let { endpoint ->
                Text("Сервер работает: ${endpoint.host}:${endpoint.port}", modifier = Modifier.testTag("account-host-address"))
                if (remaining > 0 && state.pairing != null) {
                    Image(remember(state.pairing) { qrImage(state.pairing!!.encode(), 400) },
                        contentDescription = "QR сопряжения аккаунта", modifier = Modifier.size(240.dp).testTag("account-pair-qr"))
                    Text("QR действует ещё $remaining с. Он передаёт адрес и отпечаток сертификата; пароль и права не передаются.")
                } else Text("QR истёк. Обновите его перед сканированием.")
                Button(enabled = !state.busy, onClick = controller::refreshPairing) { Text("Обновить QR сопряжения") }
                Button(enabled = !state.busy && state.user == null, onClick = { controller.useOwnHost(); hosting = false },
                    modifier = Modifier.testTag("account-use-own-host")) { Text("Войти на этом телефоне") }
                OutlinedButton(enabled = !state.busy, onClick = controller::stopHost) { Text("Остановить сервер") }
            }
        } else if (state.user == null) {
            Text("Вход в серверный аккаунт", style = MaterialTheme.typography.titleLarge)
            if (!state.busy) PermissionRequestButton(Manifest.permission.CAMERA, "Сканировать QR сервера",
                "Камера считывает адрес вашего сервера. Снимки не сохраняются.", onGranted = { scanning = true })
            if (scanning) {
                OwnedQrScanner(Modifier.fillMaxWidth().height(260.dp), onCode = {
                    scanning = false; controller.pair(it)
                }, onError = { scanning = false }, accepts = { it.startsWith("phonoteka-identity://pair?") })
                TextButton(onClick = { scanning = false }) { Text("Отменить сканирование") }
            }
            state.pairedEndpoint?.let { endpoint ->
                Text("Выбран сервер ${endpoint.host}:${endpoint.port}")
                OutlinedTextField(login, { login = it.take(64) }, label = { Text("Логин") }, singleLine = true,
                    enabled = !state.busy, modifier = Modifier.testTag("account-login"))
                OutlinedTextField(password, { password = it.take(256) }, label = { Text("Пароль сервера") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.busy,
                    modifier = Modifier.testTag("account-password"))
                Button(enabled = !state.busy && login.isNotBlank() && password.isNotEmpty(),
                    onClick = { controller.login(login, password); password = "" },
                    modifier = Modifier.testTag("account-sign-in")) { Text("Войти в аккаунт") }
            }
        } else {
            val user = state.user!!
            Text("${user.login} · ${user.role.label()}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("account-user"))
            Text("Роль подтверждена сервером. Поле «Роль о себе» в профиле её не меняет.")
            Button(enabled = !state.busy, onClick = controller::refreshSessions) { Text("Обновить аккаунт и сессии") }
            Text("Активные сессии", style = MaterialTheme.typography.titleMedium)
            state.sessions.sortedByDescending { it.createdAt }.forEach { session ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(session.deviceLabel.ifBlank { "Устройство без названия" })
                    Text("Вход: ${date(session.createdAt)} · действует до ${date(session.refreshExpiresAt)}")
                    if (session.current) Text("Эта сессия")
                    OutlinedButton(enabled = !state.busy, onClick = { controller.revokeSession(session.id, session.current) },
                        modifier = Modifier.testTag("account-revoke-${if (session.current) "current" else session.id}")) { Text("Отозвать сессию") }
                } }
            }
            if (user.role == IdentityRole.ADMIN) {
                HorizontalDivider()
                Text("Создать пользователя", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(newLogin, { newLogin = it.take(64) }, label = { Text("Логин пользователя") }, singleLine = true, enabled = !state.busy)
                OutlinedTextField(newPassword, { newPassword = it.take(256) }, label = { Text("Первый пароль · от 8 символов") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.busy)
                IdentityRole.entries.forEach { role ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = newRole == role, onClick = { newRole = role }, enabled = !state.busy)
                        Text(role.label())
                    }
                }
                Button(enabled = !state.busy && newLogin.isNotBlank() && newPassword.length >= 8,
                    onClick = { controller.createUser(newLogin, newPassword, newRole); newPassword = "" }) { Text("Создать пользователя на сервере") }
            }
            OutlinedButton(enabled = !state.busy, onClick = controller::logout, modifier = Modifier.testTag("account-logout")) { Text("Выйти из серверного аккаунта") }
        }
        state.message?.let { Text(it, modifier = Modifier.testTag("account-message")) }
    }
}

private fun IdentityRole.label(): String = when (this) {
    IdentityRole.ADMIN -> "Администратор"
    IdentityRole.SECURITY -> "Безопасность"
    IdentityRole.EMPLOYEE -> "Сотрудник"
}
