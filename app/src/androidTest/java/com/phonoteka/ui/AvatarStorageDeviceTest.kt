package com.phonoteka.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.phonoteka.ui.screens.copyAvatar
import com.phonoteka.ui.screens.saveAvatar
import com.phonoteka.viewmodel.DataStoreProfileStore
import com.phonoteka.viewmodel.ProfileData
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AvatarStorageDeviceTest {
    private suspend fun fixture(block: suspend (Context, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "avatar-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        try { block(context, root) } finally { root.deleteRecursively() }
    }

    @Test fun galleryPhotoIsRealJpegAndProfileSurvivesStoreReopen() = runBlocking {
        fixture { context, root ->
            val source = File(root, "gallery.png")
            val bitmap = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888)
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val name = copyAvatar(context, Uri.fromFile(source))
            val photo = File(root, "profile/$name")
            val bytes = photo.readBytes()
            assertEquals(0xff, bytes[0].toInt() and 255)
            assertEquals(0xd8, bytes[1].toInt() and 255)
            val decoded = BitmapFactory.decodeFile(photo.absolutePath)
            assertNotNull(decoded); assertTrue(maxOf(decoded.width, decoded.height) <= 1600); decoded.recycle()
            val profile = ProfileData("Слушатель", "Описание", "Меломан", name)
            val file = File(root, "profile.preferences_pb")
            suspend fun openStore(write: Boolean): ProfileData {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val store = DataStoreProfileStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
                    if (write) store.write(profile)
                    return store.read()
                } finally { scope.cancel(); scope.coroutineContext.job.join() }
            }
            assertEquals(profile, openStore(true)); assertEquals(profile, openStore(false))
            assertTrue(photo.isFile)
        }
    }

    @Test fun invalidGalleryInputDoesNotCreateAnAvatar() = runBlocking {
        fixture { context, root ->
            val fake = File(root, "not-a-photo.jpg").apply { writeText("not an image") }
            assertTrue(runCatching { copyAvatar(context, Uri.fromFile(fake)) }.isFailure)
            assertTrue(File(root, "profile").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cameraBitmapIsSavedAsDecodableJpeg() = runBlocking {
        fixture { context, root ->
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val name = try { saveAvatar(context, bitmap) } finally { bitmap.recycle() }
            val decoded = BitmapFactory.decodeFile(File(root, "profile/$name").absolutePath)
            assertNotNull(decoded); assertEquals(320, decoded.width); decoded.recycle()
        }
    }
}
