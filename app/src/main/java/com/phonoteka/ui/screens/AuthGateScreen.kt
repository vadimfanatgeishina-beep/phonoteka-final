package com.phonoteka.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.phonoteka.security.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mock password -> system Keystore authentication -> local PIN setup/login/recovery. */
@Composable
fun AuthGateScreen(auth: LocalAuthManager, repository: MockSessionRepository,
    biometricAvailable: Boolean, onBiometric: () -> Unit, onUnlock: () -> Unit,
    biometricSaved: Boolean = false, biometricBusy: Boolean = false, biometricMessage: String? = null) {
    val context = LocalContext.current
    val keyguard = remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("mock") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var receipt by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockUntil by remember { mutableStateOf(0L) }
    var seconds by remember { mutableStateOf(0L) }
    var pending by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    val systemPrompt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pending; pending = null
        if (result.resultCode != Activity.RESULT_OK || action == null) {
            busy = false; message = "Системная аутентификация отменена; доступ не предоставлен."
        } else scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Не удалось открыть защищённую сессию. Повторите учебный вход." }
            finally { busy = false }
        }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy || biometricBusy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val authRequired = generateSequence<Throwable>(error) { it.cause }.any { it is UserNotAuthenticatedException }
                if (authRequired && keyguard.isDeviceSecure) {
                    @Suppress("DEPRECATION")
                    val intent = keyguard.createConfirmDeviceCredentialIntent("Phonoteka", "Разрешите доступ к ключу учебной сессии")
                    if (intent != null) { pending = action; systemPrompt.launch(intent) }
                    else message = "Системное подтверждение недоступно. Доступ закрыт."
                } else message = "Нужна защищённая блокировка Android; либо ключ/хранилище недоступны. Доступ закрыт."
            } finally { if (pending == null) busy = false }
        }
    }
    LaunchedEffect(repository) {
        if (biometricSaved && auth.isConfigured) page = "resume"
        else perform {
            val token = withContext(Dispatchers.IO) { repository.accessToken() }
            page = if (token == null) "mock" else if (auth.isConfigured) "login" else "setup"
        }
    }
    LaunchedEffect(lockUntil) {
        while (true) {
            seconds = ((lockUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
            if (seconds == 0L) break
            delay(250)
        }
    }
    fun showLoginResult(result: MockLoginResult, after: () -> Unit) {
        when (result) {
            is MockLoginResult.Success -> { message = null; after() }
            is MockLoginResult.Locked -> {
                lockUntil = android.os.SystemClock.elapsedRealtime() + result.retryAfterMillis
                seconds = (result.retryAfterMillis + 999) / 1000
                message = "Учебный вход заблокирован на пять минут после четырёх ошибок."
            }
            MockLoginResult.InvalidCredentials -> message = "Неверный учебный пароль."
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Phonoteka · учебный вход", style = MaterialTheme.typography.headlineMedium)
        Text("Только локальная фикстура. Не вводите реальные учётные данные.")
        when {
            receipt != null -> {
                Text("Сохраните одноразовый recovery-код; повторного показа не будет.")
                Text(receipt!!)
                Button(enabled = !busy && !biometricBusy, onClick = { receipt = null; onUnlock() }) { Text("Я сохранил код") }
            }
            page == "resume" -> {
                Text("Сохранённая учебная сессия: биометрия расшифрует refresh token и проверит его в локальном mock endpoint.")
                if (biometricAvailable && biometricSaved) Button(enabled = !busy && !biometricBusy, onClick = onBiometric) { Text("Восстановить биометрией") }
                OutlinedButton(enabled = !busy && !biometricBusy, onClick = {
                    perform {
                        val token = withContext(Dispatchers.IO) { repository.accessToken() }
                        page = if (token == null) "mock" else "login"
                    }
                }) { Text("Системная аутентификация + PIN") }
                TextButton(enabled = !busy && !biometricBusy, onClick = { page = "mock" }) { Text("Новый учебный вход") }
            }
            page == "mock" -> {
                Text("Первый шаг — учебный пароль. Android отдельно защищает ключ refresh token.")
                OutlinedTextField(password, { password = it }, label = { Text("Учебный пароль") }, visualTransformation = PasswordVisualTransformation())
                Button(enabled = !busy && !biometricBusy && seconds == 0L, onClick = {
                    val submitted = password; password = ""
                    perform {
                        val result = withContext(Dispatchers.IO) { repository.login(LocalMockAuthEndpoint.DEMO_USER, submitted) }
                        showLoginResult(result) { page = if (auth.isConfigured) "login" else "setup" }
                    }
                }) { Text("Войти в mock-сессию") }
            }
            page == "setup" -> {
                PinField("Новый PIN", pin) { pin = it }; PinField("Повтор PIN", confirmation) { confirmation = it }
                Button(enabled = !busy && !biometricBusy && pin.length in 4..6 && pin == confirmation, onClick = {
                    val submitted = pin; pin = ""; confirmation = ""
                    perform { receipt = withContext(Dispatchers.IO) { auth.setup(submitted) } }
                }) { Text("Установить PIN") }
            }
            page == "login" -> {
                PinField("PIN", pin) { pin = it }
                Button(enabled = !busy && !biometricBusy && pin.length in 4..6, onClick = {
                    val submitted = pin; pin = ""
                    perform {
                        when (withContext(Dispatchers.IO) { auth.verifyPin(submitted) }) {
                            PinResult.SUCCESS -> onUnlock()
                            PinResult.LOCKED -> message = "PIN заблокирован; дождитесь окончания блокировки."
                            PinResult.INVALID -> message = "Неверный PIN"
                            PinResult.NOT_CONFIGURED -> page = "setup"
                        }
                    }
                }) { Text("Разблокировать") }
                if (biometricAvailable && biometricSaved) OutlinedButton(enabled = !busy && !biometricBusy, onClick = onBiometric) { Text("Биометрия") }
                TextButton(onClick = { page = "recovery" }) { Text("Recovery + учебный пароль") }
            }
            else -> {
                OutlinedTextField(recovery, { recovery = it.take(12) }, label = { Text("Recovery-код") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(password, { password = it }, label = { Text("Учебный пароль") }, visualTransformation = PasswordVisualTransformation())
                PinField("Новый PIN", pin) { pin = it }
                Button(enabled = !busy && !biometricBusy && seconds == 0L && pin.length in 4..6, onClick = {
                    val code = recovery; val submitted = password; val newPin = pin
                    recovery = ""; password = ""; pin = ""
                    perform {
                        val result = withContext(Dispatchers.IO) { repository.login(LocalMockAuthEndpoint.DEMO_USER, submitted) }
                        if (result is MockLoginResult.Success) {
                            val recovered = withContext(Dispatchers.IO) { auth.recover(code, newPin) }
                            if (recovered == null) message = "Recovery-код неверен или использован"
                            else {
                                receipt = recovered
                                val sentTo = auth.lastRecoveryNotices.joinToString(", ") { it.maskedDestination }
                                message = "PIN заменён; старый recovery-код недействителен." +
                                    if (sentTo.isEmpty()) " Уведомление не отправлено." else " Уведомление об использовании кода отправлено: $sentTo"
                            }
                        } else showLoginResult(result) {}
                    }
                }) { Text("Восстановить") }
                TextButton(onClick = { page = "login" }) { Text("Назад") }
            }
        }
        if (seconds > 0) Text("Повтор через $seconds с.")
        if (busy || biometricBusy) CircularProgressIndicator()
        biometricMessage?.let { Text(it) }
        message?.let { Text(it) }
    }
}
@Composable private fun PinField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 6 && it.all(Char::isDigit)) onValue(it) }, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true)
}
