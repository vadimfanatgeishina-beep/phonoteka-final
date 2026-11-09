package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.phonoteka.PhonotekaApp
import com.phonoteka.security.AuditEventType
import com.phonoteka.security.AuditSource
import com.phonoteka.security.RuntimeSecurityControl
import com.phonoteka.security.SecurityDecisions
import com.phonoteka.security.SecurityProfile

object BuildModeReport {
    data class Row(val scenario: String, val vulnerable: String, val hardened: String, val evidence: String)
    val rows: List<Row> = listOf(
        Row("Свежая аутентификация для операций с библиотекой", "Не проверяется", "Вход не старше 5 минут", "PhonotekaApp.requireAuthority; ACCESS_DENIED"),
        Row("Подтверждение чувствительного действия", "Отключено", "Требуется PIN/биометрия", "RiskStepUp; ACCESS_DENIED"),
        Row("Повтор кода Authenticator/восстановления", "При выключенной защите повтор принимается", "Повтор отклоняется", "LocalAuthManager / TotpAuthenticatorManager"),
        Row("Повтор QR-билета", "При выключенной защите повтор принимается", "Повтор отклоняется", "MockInviteAuthority.scan()"),
        Row("TLS pinning и доверие CA", "Политика сборки допускает стендовый MITM", "Pinning/fail-closed по конфигурации сборки", "network_security_config.xml / LocalLiveSocket.kt"),
        Row("Cleartext и backup", "Разрешения заданы манифестом сборки", "Запрещены в hardened", "Merged AndroidManifest.xml"),
        Row("Refresh-токен ЛР13", "Тестовый локальный сервер вращает refresh-токен", "Сохранённый refresh-токен зашифрован Android Keystore", "MockAuthSession / AndroidKeystoreRefreshTokenStore"),
        Row("Журнал событий", "Локальная учебная запись", "Локальная запись с проверкой цепочки SHA-256", "UnifiedAuditLog.readVerified()")
    )
}

@Composable
fun BuildModeScreen(onBack: () -> Unit) {
    val controls by SecurityProfile.controls.collectAsState()
    val app = LocalContext.current.applicationContext as? PhonotekaApp
    val canEdit = SecurityProfile.canEditRuntimeControls()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.phonoteka.ui.components.AppBackTextButton(onBack)
        com.phonoteka.ui.components.BuildModeBadge()
        Text("Режим безопасности", style = MaterialTheme.typography.headlineSmall)
        Text(SecurityProfile.summary(), style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth().testTag("runtime-security-controls")) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Защиты лабораторного стенда", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (canEdit) "Переключатели меняют проверки сразу. Изменения сохраняются и попадают в журнал."
                    else "Политика этой сборки зафиксирована: защиты нельзя ослабить из приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SecuritySwitch("Защита одноразового кода", "Запоминает использованный TOTP-интервал и recovery-код. Повторный ввод отклоняется.", controls.otpSingleUse, canEdit, "security-otp-switch") {
                    change(app, RuntimeSecurityControl.OTP_SINGLE_USE, it)
                }
                SecuritySwitch("Одноразовый QR-билет", "После первого успешного сканирования этот билет больше не выдаёт доступ.", controls.qrTicketSingleUse, canEdit, "security-qr-switch") {
                    change(app, RuntimeSecurityControl.QR_SINGLE_USE, it)
                }
                SecuritySwitch("Подтверждение опасных действий", "Для чувствительных операций требует step-up PIN/биометрией.", controls.stepUp, canEdit, "security-stepup-switch") {
                    change(app, RuntimeSecurityControl.STEP_UP, it)
                }
                SecuritySwitch("Свежий вход для библиотеки", "Отклоняет операции, если локальная аутентификация старше пяти минут.", controls.freshAuthForLibrary, canEdit, "security-fresh-auth-switch") {
                    change(app, RuntimeSecurityControl.FRESH_LIBRARY_AUTH, it)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Настройки, задаваемые при сборке", style = MaterialTheme.typography.titleMedium)
                Text("TLS pinning: ${if (SecurityProfile.enforceCertificatePinning) "включён" else "ослаблен"}")
                Text("Доверие пользовательским CA: ${if (SecurityProfile.allowUserCa) "разрешено для локального MITM-стенда" else "запрещено"}")
                Text("Cleartext: ${if (SecurityProfile.allowCleartext) "разрешён" else "запрещён"}")
                Text("Backup: ${if (com.phonoteka.BuildConfig.ALLOW_BACKUP) "разрешён" else "запрещён"}")
                Text("Это системные manifest/network-политики Android: переключить их на лету нельзя.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Пакет: ${com.phonoteka.BuildConfig.APPLICATION_ID} · ${com.phonoteka.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }

        Text("Что меняется в одинаковом тесте", style = MaterialTheme.typography.titleMedium)
        BuildModeReport.rows.forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.scenario, style = MaterialTheme.typography.titleSmall)
                    Text("Защита выключена: ${row.vulnerable}", style = MaterialTheme.typography.bodySmall)
                    Text("Защита включена: ${row.hardened}", style = MaterialTheme.typography.bodySmall)
                    Text("Где проверяется: ${row.evidence}", style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(SecurityDecisions.stepUpExplanation())
        Text(SecurityDecisions.freshAuthExplanation())
        Text(SecurityDecisions.qrExplanation())
        Text(SecurityDecisions.otpExplanation())
    }
}

@Composable
private fun SecuritySwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().testTag(testTag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun change(app: PhonotekaApp?, control: RuntimeSecurityControl, enabled: Boolean) {
    if (!SecurityProfile.setEnabled(control, enabled)) return
    val name = when (control) {
        RuntimeSecurityControl.OTP_SINGLE_USE -> "otp_single_use"
        RuntimeSecurityControl.QR_SINGLE_USE -> "qr_ticket_single_use"
        RuntimeSecurityControl.STEP_UP -> "step_up"
        RuntimeSecurityControl.FRESH_LIBRARY_AUTH -> "fresh_library_auth"
    }
    app?.auditEvent(
        type = AuditEventType.AUDIT_CHECK,
        source = AuditSource.AUDIT,
        action = "security_control_changed",
        obj = name,
        result = if (enabled) "ENABLED" else "DISABLED"
    )
}
