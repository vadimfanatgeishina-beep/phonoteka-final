package com.phonoteka.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.phonoteka.data.notes.AttachmentLimits
import com.phonoteka.ui.screens.prepareNoteImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoteImageImportTest {
    @Test fun galleryImageIsConvertedToReadableBoundedAttachment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "note-photo-test.png")
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(74, 102, 168))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        try {
            val result = prepareNoteImage(context, Uri.fromFile(source))
            assertTrue(result.bytes.size in 1..AttachmentLimits.MAX_BYTES)
            assertNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size))
            assertEquals(".jpg", result.name.takeLast(4))
        } finally { source.delete(); bitmap.recycle() }
    }
}
