package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.phonoteka.security.PinResult
import com.phonoteka.security.SensitiveResultsGate
import com.phonoteka.security.SensitiveResultsState

/**
 * ЛР13: «Обычные заказы» доступны сразу; «Результаты» — только после step-up (PIN), если вход старше 5 минут.
 * Данные результатов не загружаются и не хранятся в состоянии до успешного подтверждения.
 */
@Composable
fun Lab13ResultsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as com.phonoteka.PhonotekaApp
    val gate = remember {
        SensitiveResultsGate(isFresh = { app.localAuth.isFreshAuthentication() }, loader = SensitiveResultsGate::mockResults,
            audit = { runCatching { app.localAuth.recordAudit(it) } })
    }
    var results by remember { mutableStateOf<List<String>?>(null) }
    var explain by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun open() {
        when (val s = gate.open()) {
            is SensitiveResultsState.Loaded -> { results = s.rows; message = null }
            SensitiveResultsState.StepUpRequired -> { results = null; explain = true }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Назад") }
        Text("Обычные заказы", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        SensitiveResultsGate.ordinaryOrders.forEach { Text(it, modifier = Modifier.testTag("ordinaryOrder")) }
        Text("Результаты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        val loaded = results
        if (loaded == null) {
            Text("Чувствительные данные. Может понадобиться повторное подтверждение входа.")
            Button(onClick = ::open, modifier = Modifier.fillMaxWidth().testTag("openResults")) { Text("Открыть результаты") }
        } else {
            loaded.forEach { Text(it, modifier = Modifier.testTag("resultRow")) }
            TextButton(onClick = { results = null }) { Text("Скрыть") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }

    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text("Подтвердите вход") },
        text = { Text("Результаты содержат чувствительные данные. Последний вход был более 5 минут назад — введите PIN, чтобы продолжить.") },
        confirmButton = { TextButton(onClick = { explain = false; askPin = true }) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { explain = false; message = "Результаты не открыты" }) { Text("Отмена") } }
    )
    if (askPin) AlertDialog(
        onDismissRequest = { askPin = false; pin = "" },
        title = { Text("Введите PIN") },
        text = {
            OutlinedTextField(pin, { v -> pin = v.filter(Char::isDigit).take(6) }, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.testTag("stepUpPin"))
        },
        confirmButton = { TextButton(enabled = pin.length in 4..6, onClick = {
            val submitted = pin; pin = ""; askPin = false
            when (app.localAuth.verifyPin(submitted)) {
                PinResult.SUCCESS -> open()
                PinResult.LOCKED -> message = "Слишком много попыток. Повторите позже."
                PinResult.NOT_CONFIGURED -> message = "PIN не настроен"
                PinResult.INVALID -> message = "Неверный PIN"
            }
        }) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = { askPin = false; pin = "" }) { Text("Отмена") } }
    )
}
