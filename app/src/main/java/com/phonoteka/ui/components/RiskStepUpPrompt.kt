package com.phonoteka.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.PhonotekaApp
import com.phonoteka.security.PinResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PIN lives only in composition; it is never saved to SavedState, logs, or a queued operation. */
@Composable fun RiskStepUpPrompt(app: PhonotekaApp) {
    val pending by app.riskStepUp.pending.collectAsStateWithLifecycle()
    val challenge = pending ?: return
    var pin by remember(challenge.id) { mutableStateOf("") }
    var busy by remember(challenge.id) { mutableStateOf(false) }
    var message by remember(challenge.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { app.riskStepUp.cancel(challenge.id) },
        title = { Text("Повторное подтверждение") },
        text = { androidx.compose.foundation.layout.Column {
            Text("Операция ${challenge.operation.name} требует отдельного подтверждения. После успеха повторите действие; оно не выполняется автоматически.")
            OutlinedTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                label = { Text("PIN приложения") }, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            message?.let { Text(it) }
        } },
        confirmButton = { TextButton(enabled = !busy && pin.length in 4..6, onClick = {
            val submitted = pin; pin = ""; busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { app.localAuth.verifyPin(submitted) }
                    if (result == PinResult.SUCCESS) {
                        val confirmed = app.riskStepUp.confirm(challenge.id, app.localAuth.authenticationSnapshot())
                        if (!confirmed) message = "Подтверждение истекло или отменено. Повторите исходное действие."
                    } else message = if (result == PinResult.LOCKED) "PIN временно заблокирован" else "Подтверждение не прошло"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Подтверждение недоступно; операция закрыта." }
                finally { busy = false }
            }
        }) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = { app.riskStepUp.cancel(challenge.id) }) { Text("Отмена") } })
}
