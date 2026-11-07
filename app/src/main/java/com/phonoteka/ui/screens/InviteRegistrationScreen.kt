package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.phonoteka.security.EmployeeInviteLink
import com.phonoteka.security.InviteLinkResult
import com.phonoteka.security.InviteRegistrationResult
import com.phonoteka.security.MockInviteRegistration

/** ЛР14: регистрация по ссылке-приглашению. Невалидная ссылка — понятная ошибка, форма не показывается. */
@Composable
fun InviteRegistrationScreen(
    link: InviteLinkResult,
    onGoToLogin: () -> Unit,
    onClose: () -> Unit,
    onRegistered: () -> Unit = {},
    onRejected: (String) -> Unit = {},
    registration: MockInviteRegistration = MockInviteRegistration.shared
) {
    var name by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var email by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var outcome by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var created by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Регистрация по приглашению", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        when (link) {
            is InviteLinkResult.Invalid -> {
                Text(EmployeeInviteLink.message(link.error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("inviteError").semantics { liveRegion = LiveRegionMode.Assertive })
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
            }
            is InviteLinkResult.Valid -> {
                if (created) {
                    Text("Учётная запись создана. Войдите, чтобы продолжить.",
                        modifier = Modifier.testTag("inviteCreated").semantics { liveRegion = LiveRegionMode.Assertive })
                    Button(onClick = onGoToLogin, modifier = Modifier.fillMaxWidth()) { Text("Перейти ко входу") }
                } else {
                    Text("Приглашение принято. Укажите имя и рабочий e-mail.")
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text("Имя") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("inviteName"))
                    OutlinedTextField(email, { email = it.take(120) }, label = { Text("Рабочий e-mail") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("inviteEmail"))
                    Button(modifier = Modifier.fillMaxWidth().testTag("inviteRegister"), onClick = {
                        when (val r = registration.register(link.token, name, email)) {
                            InviteRegistrationResult.CREATED -> { created = true; outcome = null; onRegistered() }
                            else -> { outcome = registrationMessage(r); onRejected(r.name) }
                        }
                    }) { Text("Зарегистрироваться") }
                    TextButton(onClick = onClose) { Text("Отмена") }
                }
            }
        }
        outcome?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
    }
}

fun registrationMessage(result: InviteRegistrationResult): String = when (result) {
    InviteRegistrationResult.CREATED -> "Учётная запись создана"
    InviteRegistrationResult.ALREADY_USED -> "Приглашение уже использовано"
    InviteRegistrationResult.INVALID_TOKEN -> "Приглашение недействительно"
    InviteRegistrationResult.INVALID_NAME -> "Имя: от 2 до 80 символов"
    InviteRegistrationResult.INVALID_EMAIL -> "Неверный формат e-mail"
    InviteRegistrationResult.CAPACITY -> "Регистрация временно недоступна"
}
