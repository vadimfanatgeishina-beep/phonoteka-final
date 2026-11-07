package com.phonoteka.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.phonoteka.security.DemoRole

/** Содержимое QR пропуска: без токена сессии, только идентификатор, роль и срок. */
object EmployeePassPayload {
    fun build(actorId: String, role: DemoRole, validUntilEpochSeconds: Long): String {
        require(actorId.matches(Regex("[A-Za-z0-9:._-]{1,64}")))
        return "PHONOTEKA-PASS-1|$actorId|${role.name}|$validUntilEpochSeconds"
    }
}

fun qrImage(text: String, size: Int = 480): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val w = matrix.width; val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** ЛР14: пропуск — фото, QR, статус. Показывается только после проверки scope pass:view мок-сервером. */
@Composable
fun EmployeePassCard(actorId: String, role: DemoRole, validUntilEpochSeconds: Long, modifier: Modifier = Modifier) {
    val payload = remember(actorId, role, validUntilEpochSeconds) { EmployeePassPayload.build(actorId, role, validUntilEpochSeconds) }
    val qr = remember(payload) { qrImage(payload) }
    val active = validUntilEpochSeconds > System.currentTimeMillis() / 1000
    Card(modifier.fillMaxWidth().testTag("employeePass")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "Фото сотрудника", modifier = Modifier.size(64.dp))
                Column {
                    Text("Пропуск сотрудника", style = MaterialTheme.typography.titleMedium)
                    Text("ID: $actorId · роль ${role.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(if (active) "Статус: действует" else "Статус: истёк",
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("passStatus"))
                }
            }
            Image(qr, contentDescription = null, modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally)
                .semantics { contentDescription = "QR-код пропуска" })
        }
    }
}
