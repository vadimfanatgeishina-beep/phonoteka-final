package com.phonoteka.data.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real PNGs, AndroidKeyStore and document IO, with fresh repository/crypto instances on reopen. */
class NotePhotoArchiveDeviceTest {
    @Test fun fivePhotosSurviveDocumentExportReopenAndImport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "photos-${UUID.randomUUID()}")
        val alias = "phonoteka.photos.test.${UUID.randomUUID()}"
        val index = File(root, "index.json")
        fun crypto() = KeystoreNoteCrypto({ index.exists() || File(root, "drafts").listFiles()?.isNotEmpty() == true }, alias)
        fun repo() = FileTrackNotesRepository(root, File(root, "drafts"), crypto(), AndroidAtomicNoteIndex(index), { it == 42L })
        try {
            val photos = (1..5).map { n ->
                val bitmap = Bitmap.createBitmap(80 + n, 60 + n, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.rgb(n * 40, 80, 160))
                val png = ByteArrayOutputStream().use { stream ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray()
                }
                bitmap.recycle()
                NoteAttachment("photo$n", "photo$n.png", "image/png", AttachmentLimits.encodeBase64(png))
            }
            val original = TrackNote(42, "Пять фотографий", "Проверка архива", attachments = photos)
            repo().save(original)
            val documents = AndroidJsonDocuments(context.contentResolver)
            val archiveFile = File(root, "export.bin")
            documents.write(Uri.fromFile(archiveFile), repo().exportArchive())
            repo().save(original.copy(text = "Изменено после экспорта", attachments = emptyList()))
            val reopened = repo()
            val archive = documents.read(Uri.fromFile(archiveFile))
            assertEquals(1, reopened.previewArchive(archive))
            assertEquals(1, reopened.importArchive(archive))
            val restored = repo().list().single().note
            assertEquals(original.text, restored.text)
            assertEquals(photos, restored.attachments)
            restored.attachments.forEach { photo ->
                val bytes = AttachmentLimits.decodeBase64(photo.data)!!
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull("Restored photo must be decodable", image)
                image.recycle()
            }
            val damaged = archive.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertTrue(runCatching { reopened.importArchive(damaged) }.isFailure)
            assertEquals(restored, repo().list().single().note)
        } finally { crypto().resetKeys(); root.deleteRecursively() }
    }
}
