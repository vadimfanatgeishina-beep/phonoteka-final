package com.phonoteka.ui.permissions
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
    else -> null
}
/** On-demand reusable flow. No permission request at app startup. */
@Composable fun PermissionRequestButton(permission: String, label: String, explanation: String,
    enabled: Boolean = true, onGranted: () -> Unit, onDenied: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context.activity()
    val owner = LocalLifecycleOwner.current
    val prefs = remember(context) { context.getSharedPreferences("phonoteka_permission_requests", Context.MODE_PRIVATE) }
    val grantedCallback by rememberUpdatedState(onGranted)
    val deniedCallback by rememberUpdatedState(onDenied)
    var next by remember(permission) { mutableStateOf<PermissionNext?>(null) }
    var returningFromSettings by remember(permission) { mutableStateOf(false) }
    var failure by remember(permission) { mutableStateOf<String?>(null) }
    fun granted() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        prefs.edit().putBoolean(permission, true).apply()
        if (allowed) grantedCallback() else { failure = "Разрешение отклонено. Функция недоступна; остальные экраны работают."; deniedCallback() }
    }
    DisposableEffect(owner, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && returningFromSettings) {
                returningFromSettings = false
                if (granted()) grantedCallback() else { failure = "Разрешение не выдано в настройках"; deniedCallback() }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Button(enabled = enabled, onClick = {
        failure = null
        next = PermissionFlowPolicy.next(granted(), prefs.getBoolean(permission, false),
            activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } == true)
        if (next == PermissionNext.GRANTED) { next = null; grantedCallback() }
    }) { Text(label) }
    failure?.let { Text(it) }
    if (next == PermissionNext.RATIONALE) AlertDialog(onDismissRequest = { next = null },
        title = { Text("Зачем нужно разрешение") }, text = { Text(explanation) },
        confirmButton = { TextButton(onClick = { next = null; launcher.launch(permission) }) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { next = null; deniedCallback() }) { Text("Не сейчас") } })
    if (next == PermissionNext.SETTINGS) AlertDialog(onDismissRequest = { next = null },
        title = { Text("Разрешение ранее отклонено") }, text = { Text("Повторный системный запрос может быть недоступен. Вы можете выдать разрешение в настройках приложения или отменить действие.") },
        confirmButton = { TextButton(onClick = {
            next = null; returningFromSettings = true
            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
            catch (_: Exception) { returningFromSettings = false; failure = "Настройки недоступны" }
        }) { Text("Открыть настройки") } },
        dismissButton = { TextButton(onClick = { next = null; deniedCallback() }) { Text("Отмена") } })
}
