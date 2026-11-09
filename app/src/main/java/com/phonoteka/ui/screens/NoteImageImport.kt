package com.phonoteka.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.phonoteka.data.notes.AttachmentLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

internal data class ImportedNoteImage(val name: String, val bytes: ByteArray)

/** Reads a gallery photo and fits it into the encrypted note attachment budget. */
internal suspend fun prepareNoteImage(context: Context, uri: Uri): ImportedNoteImage = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }

    val maxSide = 1600
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    var bitmap = requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }) {
        "Unsupported image"
    }
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull()
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees != 0f) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
        Matrix().apply { postRotate(degrees) }, true)

    var result = ByteArray(0)
    for (quality in listOf(88, 76, 64, 52, 40)) {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
        result = output.toByteArray()
        if (result.size <= AttachmentLimits.MAX_BYTES) break
    }
    if (result.size > AttachmentLimits.MAX_BYTES) {
        val scale = kotlin.math.sqrt(AttachmentLimits.MAX_BYTES.toDouble() / result.size).coerceAtMost(0.85)
        bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output))
        result = output.toByteArray()
    }
    require(result.isNotEmpty() && result.size <= AttachmentLimits.MAX_BYTES) { "Image too large" }
    ImportedNoteImage("photo_${System.currentTimeMillis()}.jpg", result)
}
