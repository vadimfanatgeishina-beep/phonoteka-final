package com.phonoteka.ui.screens
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.phonoteka.PhonotekaApp
import com.phonoteka.security.DemoScope
import com.phonoteka.ui.components.OwnedQrScanner
import com.phonoteka.ui.permissions.PermissionRequestButton
import com.phonoteka.viewmodel.TrackInviteViewModel

@Composable fun TrackInviteScreen(viewModel: TrackInviteViewModel, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhonotekaApp
    val role by app.roleSession.changes.collectAsStateWithLifecycle()
    val scopes = com.phonoteka.security.DemoRolePolicy.scopes(role)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var id by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf(false) }
    var cameraMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val qrExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val wire = pendingExport; pendingExport = null
        if (uri == null || wire == null) cameraMessage = "Экспорт QR отменён" else coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.requireAuthority(DemoScope.INVITE_CREATE, "invite.export")
                    val matrix = QRCodeWriter().encode(wire, BarcodeFormat.QR_CODE, 512, 512)
                    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    try {
                        val pixels = IntArray(512 * 512) { p -> if (matrix[p % 512, p / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
                        app.contentResolver.openOutputStream(uri, "wt")?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            ?: error("Owned document unavailable")
                    } finally { bitmap.recycle() }
                }
                cameraMessage = "QR экспортирован для показа на своём втором экране. Это не ключ; приглашение истекает и принимается только этим процессом."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { cameraMessage = "Экспорт не выполнен; подтвердите доступ и повторите действие" }
        }
    }
    LaunchedEffect(role) { scan = false; pendingExport = null; viewModel.clear() }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Приглашение к треку · ЛР14", style = MaterialTheme.typography.titleLarge)
        Text("Только собственный локальный стенд. QR подписан AndroidKeyStore, одноразовый и живёт не более пяти минут. После перезапуска процесса не принимается.")
        TextButton(onClick = onBack) { Text("Назад") }
        if (DemoScope.INVITE_CREATE in scopes) {
            OutlinedTextField(id, { id = it.filter(Char::isDigit).take(18) }, label = { Text("ID существующего трека") }, modifier = Modifier.testTag("invite-track-id"))
            Button(enabled = !state.busy && id.toLongOrNull()?.let { it > 0 } == true,
                onClick = { viewModel.issue(id.toLong()) }, modifier = Modifier.testTag("invite-create")) { Text("Создать одноразовый QR") }
            state.wire?.let { wire ->
                val bitmap = remember(wire) {
                    val matrix = QRCodeWriter().encode(wire, BarcodeFormat.QR_CODE, 512, 512)
                    Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
                        val pixels = IntArray(512 * 512) { pos -> if (matrix[pos % 512, pos / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
                    }.asImageBitmap()
                }
                Image(bitmap, "Одноразовое локальное приглашение", modifier = Modifier.size(280.dp).testTag("invite-qr"))
                TextButton(onClick = { pendingExport = wire; qrExporter.launch("owned-track-invite.png") }) { Text("Экспорт QR на свой второй экран") }
            }
        } else Text("Создание приглашения разрешено только mock ADMIN")
        if (DemoScope.SCAN_TRACK in scopes) {
            PermissionRequestButton(Manifest.permission.CAMERA, "Сканировать собственный QR",
                "Камера нужна только для локального чтения QR приглашения к треку. Кадры не сохраняются и не отправляются в сеть.",
                enabled = !state.busy, onGranted = { cameraMessage = null; scan = true }, onDenied = { scan = false })
            if (scan) {
                OwnedQrScanner(Modifier.fillMaxWidth().height(280.dp), onCode = { scan = false; viewModel.scan(it) },
                    onError = { scan = false; cameraMessage = "Камера недоступна; кадры не сохранены" })
                TextButton(onClick = { scan = false }) { Text("Остановить камеру") }
            }
        } else Text("Сканирование разрешено mock SECURITY/ADMIN")
        if (state.busy) CircularProgressIndicator()
        state.message?.let { Text(it, Modifier.testTag("invite-result")) }
        state.scannedTrackId?.let { Text("Проверен трек ID $it") }
        cameraMessage?.let { Text(it) }
    }
}
