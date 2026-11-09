package com.phonoteka.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonoteka.PhonotekaApp
import com.phonoteka.security.LocalQrAccessServer
import com.phonoteka.security.DemoRole
import com.phonoteka.security.QuickAccessLink
import com.phonoteka.security.QuickAccessLinkParser
import com.phonoteka.ui.components.OwnedQrScanner
import com.phonoteka.ui.permissions.PermissionRequestButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun QuickAccessRedeemScreen(app: PhonotekaApp, link: QuickAccessLink?, onOpenApp: () -> Unit) {
    var activeLink by remember(link) { mutableStateOf(link) }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Быстрый вход Phonoteka", style = MaterialTheme.typography.headlineMedium)
        if (activeLink == null) {
            Text("Сканируйте QR2 с телефона владельца. Код действует две минуты и открывает учебную сессию без PIN.")
            PermissionRequestButton(Manifest.permission.CAMERA, "Сканировать QR2",
                "Камера нужна для чтения одноразового QR2. Кадры не сохраняются и не передаются в сеть.",
                onGranted = { scanning = true })
            if (scanning) OwnedQrScanner(Modifier.fillMaxWidth().height(280.dp),
                onCode = { raw ->
                    scanning = false
                    activeLink = QuickAccessLinkParser.parse(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
                    if (activeLink == null) message = "Этот QR не содержит приглашение быстрого входа."
                },
                onError = { scanning = false; message = "Камера недоступна. Проверьте разрешение." },
                accepts = { it.startsWith("phonoteka://quick-access?") })
        } else if (!granted) {
            Text("Телефон 1 выдал одноразовое приглашение. Подтверди его, чтобы получить учебную роль и открыть приложение без PIN.")
            Text("Проверь, что оба телефона подключены к одной точке доступа. Приглашение действует 2 минуты и используется один раз.")
            Button(enabled = !busy, onClick = {
                val invitation = activeLink ?: return@Button
                busy = true; message = null
                scope.launch {
                    try {
                        val (role, actorId) = withContext(Dispatchers.IO) {
                            LocalQrAccessServer.redeem(invitation.host, invitation.port, invitation.ticket)
                        }
                        app.roleSession.acceptQuickAccess(role, actorId, LocalQrAccessServer.SESSION_TTL_MS)
                        try {
                            app.localAuth.markQuickAccessSession(role)
                            app.accessAudit.record(app.roleSession.session, "quick_access.accepted", true, "role:${role.name}")
                            app.auditEvent(com.phonoteka.security.AuditEventType.LOGIN,
                                com.phonoteka.security.AuditSource.QR, "qr2.login", actorId,
                                "ALLOWED", reason = "role:${role.name}", includeIp = true)
                        } catch (error: Exception) {
                            app.roleSession.resetToEmployee()
                            app.localAuth.lock()
                            throw error
                        }
                        val title = when (role) {
                            DemoRole.ADMIN -> "Администратор"
                            DemoRole.EMPLOYEE -> "Сотрудник"
                            DemoRole.SECURITY -> "Служба безопасности"
                        }
                        message = "Вы вошли по QR под должностью «$title». Учебная сессия действует 30 минут."
                        granted = true
                    } catch (error: Exception) {
                        runCatching { app.auditEvent(com.phonoteka.security.AuditEventType.QR_REJECT,
                            com.phonoteka.security.AuditSource.QR, "qr2.redeem_failed", "ticket",
                            "DENIED", reason = error.javaClass.simpleName) }
                        message = error.message ?: "Телефон 1 не подтвердил приглашение. Проверь сеть и срок действия QR."
                    } finally { busy = false }
                }
            }) { Text("Подтвердить быстрый вход") }
        } else {
            Text("Доступ подтверждён. Можно открыть Phonoteka.")
            Button(onClick = onOpenApp) { Text("Открыть приложение") }
        }
        if (busy) CircularProgressIndicator()
        message?.let { Text(it) }
    }
}
