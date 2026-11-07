package com.phonoteka.ui.screens

import com.phonoteka.security.scopeClaim
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.phonoteka.security.BiometricStepUpOperation
import com.phonoteka.security.AccessAuditRepository
import com.phonoteka.security.DemoRole
import com.phonoteka.security.DemoRoleSession
import com.phonoteka.security.DemoScope
import com.phonoteka.security.DemoAccessService
import com.phonoteka.security.RuntimeSignalProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun Lab14AccessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val app = context.applicationContext as com.phonoteka.PhonotekaApp
    val roleSession = remember { app.roleSession }
    val auditRepository = remember { app.accessAudit }
    val accessService = remember { DemoAccessService(auditRepository, roleSession.authority) }
    var role by remember { mutableStateOf(roleSession.role) }
    var entries by remember { mutableStateOf(emptyList<String>()) }
    var result by remember { mutableStateOf<String?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    var pendingAuditSession by remember { mutableStateOf<com.phonoteka.security.DemoSession?>(null) }
    var generation by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var passVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun <T> perform(action: () -> T, apply: (T) -> Unit) {
        if (busy) return
        val epoch = generation
        val actor = roleSession.session
        busy = true
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { action() }
                if (epoch == generation && roleSession.authority.valid(actor)) apply(value)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (epoch == generation) { entries = emptyList(); result = "Действие запрещено либо журнал/ключ недоступны. Доступ закрыт." } }
            finally { if (epoch == generation) busy = false }
        }
    }
    /** ЛР14: без успешного BIOMETRIC_STRONG с CryptoObject журнал не читается и не экспортируется. */
    fun withBiometricStepUp(operation: BiometricStepUpOperation, onGranted: () -> Unit) {
        val host = activity
        if (busy || host == null) { if (host == null) result = "Подтверждение биометрией недоступно в этом окне"; return }
        if (BiometricManager.from(host).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            result = "Для журнала нужна настроенная биометрия класса Strong. PIN здесь не принимается."
            return
        }
        val prepared = try { app.auditStepUp.prepare(operation) } catch (_: Exception) {
            result = "Ключ подтверждения недоступен. Проверьте блокировку экрана и биометрию."; return
        }
        val epoch = generation
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (operation == BiometricStepUpOperation.AUDIT_EXPORT) "Экспорт журнала аудита" else "Открыть журнал аудита")
            .setSubtitle("Подтвердите биометрией (одноразово, 60 с)")
            .setNegativeButtonText("Отмена")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(true)
            .build()
        BiometricPrompt(host, ContextCompat.getMainExecutor(host), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(authResult: BiometricPrompt.AuthenticationResult) {
                if (epoch != generation) { app.auditStepUp.gate.invalidate(); return }
                val ok = app.auditStepUp.complete(prepared, authResult.cryptoObject?.cipher, app.localAuth.authenticationSnapshot().version)
                if (ok) onGranted() else result = "Биометрическое подтверждение не принято. Доступ закрыт."
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                app.auditStepUp.gate.invalidate()
                if (epoch == generation) result = "Подтверждение не выполнено: $errString"
            }
        }).authenticate(info, BiometricPrompt.CryptoObject(prepared.cipher))
    }
    val auditExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val actor = pendingAuditSession
        pendingAuditSession = null
        if (uri == null || actor == null) result = "Экспорт аудита отменён"
        else withBiometricStepUp(BiometricStepUpOperation.AUDIT_EXPORT) { perform({
            auditRepository.writeSignedExport(actor) { payload ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload) }
                    ?: error("Selected document unavailable")
            }
            "Подписанный полный журнал сохранён через SAF"
        }, { result = it }) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        roleSession.recordPermissionPrompted()
        result = if (granted) "Разрешение на уведомления выдано" else "Разрешение отклонено; воспроизведение продолжит работать без него"
        val actor = roleSession.session
        perform({ auditRepository.record(actor, "permission.POST_NOTIFICATIONS", granted) }, {})
    }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT < 33) { result = "Разрешение не требуется на этой версии Android"; return }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) { result = "Разрешение уже выдано"; return }
        val shouldExplain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        val asked = roleSession.hasPromptedNotifications()
        if (asked && !shouldExplain) {
            result = "Разрешение ранее отклонено. Откройте настройки приложения, чтобы изменить его."
        } else if (shouldExplain) showRationale = true
        else showRationale = true
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Доступ и аудит · ЛР14") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Назад") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Демонстрационная локальная сессия с ролью и scopes. Токен учебный, не является серверной авторизацией.")
            if (com.phonoteka.BuildConfig.DEBUG) DemoRole.entries.forEach { option ->
                androidx.compose.foundation.layout.Row {
                    RadioButton(enabled = !busy, selected = role == option, onClick = {
                        generation++; pendingAuditSession = null
                        app.clearStepUpSession(); passVisible = false; role = option; roleSession.select(option); entries = emptyList(); result = "Роль переключена на ${option.name}"
                        val actor = roleSession.session
                        perform({ auditRepository.record(actor, "role.select", true, "role:${option.name}") }, {})
                    })
                    Text(when (option) {
                        DemoRole.EMPLOYEE -> "Сотрудник · чтение/изменение библиотеки"
                        DemoRole.SECURITY -> "Служба безопасности · чтение/сканирование треков"
                        DemoRole.ADMIN -> "Администратор · полный учебный scope"
                    })
                }
            }
            Text("Учебный grant: ${role.name}; значение токена не выводится", style = MaterialTheme.typography.bodySmall)
            val runtimeSignals = remember { RuntimeSignalProbe.collect() }
            Text(
                "RASP-сигналы · debugger=${runtimeSignals.debuggerAttached}, emulator=${runtimeSignals.emulator}. " +
                    "Это диагностические признаки, они не блокируют приложение.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(enabled = !busy, onClick = ::requestPermission) { Text("Запросить разрешение уведомлений") }
            Button(enabled = !busy, onClick = {
                perform({ app.requireAuthority(DemoScope.LIBRARY_READ, "invite.open") }, {
                    context.startActivity(Intent(context, com.phonoteka.TrackInviteActivity::class.java))
                    result = "Открыт локальный экран приглашения"
                })
            }) { Text("Приглашение и QR-камера") }
            Button(enabled = !busy, onClick = {
                val actor = roleSession.session
                // ЛР14: пропуск рисуется только после ответа мок-сервера по scope pass:view, а не по локальному флагу.
                perform({ accessService.authorize(actor, DemoScope.PASS_VIEW, "pass.view") }, { allowed ->
                    passVisible = allowed
                    result = if (allowed) "Пропуск показан (scope ${DemoScope.PASS_VIEW.wire})" else "Нет scope ${DemoScope.PASS_VIEW.wire}"
                })
            }) { Text("Показать пропуск") }
            if (passVisible) {
                val session = roleSession.session
                com.phonoteka.ui.components.EmployeePassCard(session.actorId, session.role,
                    remember(session.role) { System.currentTimeMillis() / 1000 + 8 * 3600 })
            }
            Text("Текущие scopes: " + roleSession.session.scopeClaim)
            Button(enabled = !busy, onClick = {
                val actor = roleSession.session
                perform({ accessService.authorize(actor, DemoScope.SCAN_TRACK, "catalog.scan") }, { allowed ->
                    result = if (allowed) "Проверка каталога разрешена mock endpoint" else "Mock endpoint запретил сканирование"
                })
            }) { Text("Проверить доступ к сканированию") }
            Button(enabled = !busy, onClick = {
                val actor = roleSession.session
                perform({ accessService.authorize(actor, DemoScope.LIBRARY_WRITE, "library.write") }, { allowed ->
                    result = if (allowed) "Изменение разрешено" else "Изменение запрещено"
                })
            }) { Text("Проверить доступ к изменению библиотеки") }
            if (busy) androidx.compose.material3.CircularProgressIndicator()
            if (result != null) Text(result!!)
            if (roleSession.canAccess(DemoScope.AUDIT_READ)) {
                Button(enabled = !busy, onClick = {
                    val actor = roleSession.session
                    withBiometricStepUp(BiometricStepUpOperation.AUDIT_READ) { perform({ auditRepository.readAll(actor) }, { entries = it }) }
                }) { Text("Открыть журнал аудита") }
                Button(enabled = !busy, onClick = {
                    val actor = roleSession.session
                    // Роль проверяется сейчас; биометрия и полная проверка прав — уже после выбора файла, непосредственно перед записью.
                    if (roleSession.authority.valid(actor) && roleSession.canAccess(DemoScope.AUDIT_READ)) {
                        pendingAuditSession = actor
                        auditExportPicker.launch("phonoteka-audit.json")
                    } else result = "Экспорт доступен только администратору"
                }) { Text("Экспортировать HMAC-защищённый журнал") }
                Text("Показаны последние ${minOf(entries.size, 200)} из ${entries.size} проверенных событий. Экспорт включает полный журнал.")
                entries.takeLast(200).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            } else {
                Text("Журнал доступен роли администратора.")
            }
        }
    }
    if (showRationale) AlertDialog(
        onDismissRequest = { showRationale = false },
        title = { Text("Разрешение для уведомлений") },
        text = { Text("Phonoteka использует уведомление только для управления активным воспроизведением. Разрешение можно отклонить; библиотека останется доступна.") },
        confirmButton = { TextButton(onClick = { showRationale = false; permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Не сейчас") } }
    )
    if (result?.contains("ранее отклонено") == true) AlertDialog(
        onDismissRequest = { result = null }, title = { Text("Настройки разрешения") },
        text = { Text(result!!) },
        confirmButton = { TextButton(onClick = {
            result = null
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }) { Text("Открыть настройки") } },
        dismissButton = { TextButton(onClick = { result = null }) { Text("Закрыть") } }
    )
}
