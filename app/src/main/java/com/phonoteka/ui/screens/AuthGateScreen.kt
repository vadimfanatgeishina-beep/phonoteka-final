package com.phonoteka.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.phonoteka.security.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Mock password -> system Keystore authentication -> local PIN setup/login/recovery. */
@Composable
fun AuthGateScreen(auth: LocalAuthManager, repository: MockSessionRepository,
    authenticator: TotpAuthenticatorManager, biometricAvailable: Boolean,
    onBiometric: () -> Unit, onEnrollBiometric: () -> Unit, onUnlock: () -> Unit,
    biometricSaved: Boolean = false, biometricBusy: Boolean = false, biometricMessage: String? = null,
    resumeAfterExpiry: Boolean = false) {
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
    var authenticatorSecret by remember { mutableStateOf("") }
    var authenticatorCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lockUntil by remember { mutableStateOf(0L) }
    var seconds by remember { mutableStateOf(0L) }
    var pending by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    fun navigateBack() {
        if (busy) { message = "Подожди окончания проверки."; return }
        page = when (page) {
            "authenticator_setup" -> "security_setup"
            "authenticator_code", "recovery" -> "login"
            "security_setup" -> if (auth.isConfigured) "login" else "setup"
            "setup" -> "mock"
            else -> "login"
        }
        message = null
    }
    BackHandler(enabled = page in setOf("authenticator_setup", "authenticator_code", "recovery", "security_setup", "setup")) {
        navigateBack()
    }
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
    LaunchedEffect(repository, resumeAfterExpiry) {
        if (resumeAfterExpiry && auth.isConfigured) page = if (biometricSaved) "resume" else "login"
        else if (biometricSaved && auth.isConfigured) page = "resume"
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
        if (page in setOf("authenticator_setup", "authenticator_code", "recovery", "security_setup", "setup")) {
            com.phonoteka.ui.components.AppBackIconButton(::navigateBack)
        }
        Text("Phonoteka · учебный вход", style = MaterialTheme.typography.headlineMedium)
        Text("Только локальная фикстура. Не вводите реальные учётные данные.")
        when {
            receipt != null -> {
                Text("Сохраните одноразовый recovery-код; повторного показа не будет.")
                Text(receipt!!)
                Button(enabled = !busy && !biometricBusy, onClick = { receipt = null; page = "security_setup" }) { Text("Продолжить настройку защиты") }
            }
            page == "security_setup" -> {
                Text("Добавьте второй способ входа: Authenticator, отпечаток пальца или продолжите без дополнительной настройки.",
                    style = MaterialTheme.typography.bodyLarge)
                if (authenticator.isEnabled()) {
                    Text("Authenticator уже подключён. При следующем входе по PIN потребуется шестизначный код.")
                } else {
                    Button(enabled = !busy && !biometricBusy, onClick = {
                        perform {
                            authenticatorSecret = withContext(Dispatchers.IO) { authenticator.beginEnrollment() }
                            authenticatorCode = ""
                            page = "authenticator_setup"
                        }
                    }) { Text("Подключить Authenticator") }
                }
                if (biometricAvailable && !biometricSaved) {
                    OutlinedButton(enabled = !busy && !biometricBusy, onClick = {
                        if (auth.isFreshAuthentication()) onEnrollBiometric()
                        else { page = "login"; message = "Сессия истекла. Введи PIN перед настройкой отпечатка." }
                    }) {
                        Text("Настроить отпечаток пальца")
                    }
                } else if (biometricSaved) Text("Биометрический вход уже настроен.")
                Button(enabled = !busy && !biometricBusy, onClick = {
                    if (auth.isFreshAuthentication()) onUnlock()
                    else { page = "login"; message = "Сессия истекла. Введи PIN, чтобы продолжить." }
                }) { Text("Продолжить в Фонотеку") }
            }
            page == "authenticator_setup" -> {
                Text("В Authenticator выбери добавление аккаунта по QR-коду. Если сканирование недоступно, скопируй ключ настройки ниже.")
                if (authenticatorSecret.isNotBlank()) AuthenticatorQr(authenticator.provisioningUri(authenticatorSecret))
                Text("Аккаунт: Phonoteka · ЛР13", style = MaterialTheme.typography.titleSmall)
                Text(authenticatorSecret, style = MaterialTheme.typography.bodyMedium)
                val clipboard = LocalClipboardManager.current
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(authenticatorSecret)) }) {
                    Text("Скопировать ключ настройки")
                }
                Text("После добавления аккаунта введи текущий шестизначный код из Authenticator. Код обновляется раз в 60 секунд.")
                OutlinedTextField(authenticatorCode, { authenticatorCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Шестизначный код") }, singleLine = true)
                Button(enabled = !busy && !biometricBusy && authenticatorCode.length == 6, onClick = {
                    val code = authenticatorCode; authenticatorCode = ""
                    perform {
                        val confirmed = withContext(Dispatchers.IO) { authenticator.confirmEnrollment(code) }
                        if (confirmed) {
                            authenticatorSecret = ""
                            page = "security_setup"
                            message = "Authenticator подключён. При входе по PIN приложение запросит одноразовый код."
                        } else message = "Код не подошёл. Проверь, что в Authenticator добавлен ключ с этого экрана и выбран период 60 секунд; затем дождись нового кода."
                    }
                }) { Text("Проверить и подключить") }
            }
            page == "authenticator_code" -> {
                Text("Введи шестизначный одноразовый код из Authenticator.")
                OutlinedTextField(authenticatorCode, { authenticatorCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Код Authenticator") }, singleLine = true)
                Button(enabled = !busy && !biometricBusy && authenticatorCode.length == 6, onClick = {
                    val code = authenticatorCode; authenticatorCode = ""
                    perform {
                        if (withContext(Dispatchers.IO) { authenticator.verifyAndConsume(code) }) onUnlock()
                        else message = "Код неверен, уже использован или истёк. Возьми новый код из Authenticator."
                    }
                }) { Text("Подтвердить код") }
            }
            page == "resume" -> {
                Text("Сохранённая учебная сессия: биометрия расшифрует refresh token и проверит его в локальном mock endpoint.")
                if (biometricAvailable && biometricSaved) Button(enabled = !busy && !biometricBusy, onClick = onBiometric) { Text("Восстановить биометрией") }
                OutlinedButton(enabled = !busy && !biometricBusy, onClick = {
                    perform {
                        val token = withContext(Dispatchers.IO) { repository.accessToken() }
                        page = if (token == null && !resumeAfterExpiry) "mock" else "login"
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
                            PinResult.SUCCESS -> if (authenticator.isEnabled()) page = "authenticator_code" else onUnlock()
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

@Composable private fun AuthenticatorQr(uri: String) {
    val bitmap = remember(uri) {
        val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 480, 480)
        Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 480) for (x in 0 until 480) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
    Image(bitmap.asImageBitmap(), contentDescription = "QR-код настройки Authenticator",
        modifier = Modifier.size(220.dp))
}
