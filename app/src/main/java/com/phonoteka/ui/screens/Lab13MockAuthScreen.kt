package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.platform.testTag
import com.phonoteka.security.MockLoginResult
import com.phonoteka.security.MockSessionRepository
import kotlinx.coroutines.CancellationException
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Lab13MockAuthScreen(repository: MockSessionRepository, onBack: () -> Unit, onLogout: () -> Unit = {}) {
    var username by remember { mutableStateOf("student") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Только локальная учебная учётная запись.") }
    var signedIn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lockUntil by remember { mutableStateOf(0L) }
    var remainingSeconds by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val keyLossApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.phonoteka.PhonotekaApp
    var keyLossNotice by remember { mutableStateOf(keyLossApp.keyLossJournal.peek()) }
    androidx.compose.runtime.LaunchedEffect(signedIn, message) { keyLossNotice = keyLossApp.keyLossJournal.peek() }

    suspend fun safely(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure is com.phonoteka.security.KeyLostException) keyLossNotice = keyLossApp.keyLossJournal.peek()
            signedIn = false
            message = "Не удалось сохранить или восстановить сессию. Повторите учебный вход."
        } finally { busy = false }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { safely(action) }
    }
    LaunchedEffect(repository) {
        busy = true
        safely {
            signedIn = withContext(Dispatchers.IO) { repository.accessToken() } != null
            if (signedIn) message = "Сессия восстановлена через refresh token; значение не выводится."
        }
    }
    LaunchedEffect(lockUntil) {
        while (true) {
            val remaining = (lockUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            remainingSeconds = (remaining + 999) / 1000
            if (remaining == 0L) break
            delay(250)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ЛР13 · mock login и token lifecycle", style = MaterialTheme.typography.headlineSmall)
        Text("Только локальная фикстура. Не вводите реальные учётные данные.")
        keyLossNotice?.let { notice ->
            androidx.compose.material3.Card(androidx.compose.ui.Modifier.testTagKeyLoss()) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(12.dp)) {
                    Text(notice.message, color = MaterialTheme.colorScheme.error,
                        modifier = androidx.compose.ui.Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive })
                    TextButton(onClick = { keyLossApp.keyLossJournal.acknowledge(); keyLossNotice = null }) { Text("Понятно")  }
                }
            }
        }
        OutlinedTextField(username, { username = it.take(64) }, label = { Text("Учебный пользователь") }, singleLine = true)
        OutlinedTextField(
            password, { password = it }, label = { Text("Учебный пароль") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true
        )
        Button(
            enabled = !busy && !signedIn && remainingSeconds == 0L,
            onClick = {
                val submittedUsername = username
                val submittedPassword = password
                password = ""
                perform {
                    val result = withContext(Dispatchers.IO) { repository.login(submittedUsername, submittedPassword) }
                    message = when (result) {
                        is MockLoginResult.Success -> { signedIn = true; "Вход выполнен; access token хранится только в памяти." }
                        MockLoginResult.InvalidCredentials -> "Неверные учебные данные."
                        is MockLoginResult.Locked -> {
                            lockUntil = SystemClock.elapsedRealtime() + result.retryAfterMillis
                            remainingSeconds = (result.retryAfterMillis + 999) / 1000
                            "Четыре неверные попытки: учебный вход временно заблокирован."
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text("Войти") }
        Button(
            enabled = !busy && signedIn,
            onClick = {
                perform {
                    val token = withContext(Dispatchers.IO) { repository.accessToken() }
                    signedIn = token != null
                    message = if (signedIn) "Access token получен/обновлён; значение не показывается." else "Сессия истекла; войдите снова."
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text("Обновить access token") }
        TextButton(
            enabled = !busy && signedIn,
            onClick = {
                perform {
                    try {
                        withContext(Dispatchers.IO) { repository.logout() }
                        message = "Сессия отозвана локальной mock-фикстурой."
                    } finally {
                        signedIn = false
                        keyLossApp.principalChanges.signal() // ЛР15: закрыть сокет прежнего пользователя
                        onLogout() // A cleanup error must never keep the protected UI unlocked.
                    }
                }
            }
        ) { Text("Выйти и отозвать refresh token") }
        Text(message)
        if (remainingSeconds > 0) Text("Повторный вход через $remainingSeconds с.")
        com.phonoteka.ui.components.AppBackTextButton(onBack)
    }
}

private fun androidx.compose.ui.Modifier.testTagKeyLoss() = this.then(androidx.compose.ui.Modifier.testTag("keyLossNotice"))
