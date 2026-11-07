package com.phonoteka.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import java.io.IOException
import java.util.UUID
import org.junit.Before
import org.junit.After
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryBackupFileStoreTest {
    private lateinit var isolatedContext: Context
    private lateinit var root: File
    private val emptyJson get() = LibraryBackupCodec.encode(emptyList(), emptyList())
    private val changedJson get() = LibraryBackupCodec.encode(
        listOf(TrackEntity(id = 1, artist = "fixture", title = "changed")), emptyList())
    private val backup get() = File(File(root, "backups"), "phonoteka-library.enc")

    @Before fun isolateFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "backup-regression-${UUID.randomUUID()}").apply { mkdirs() }
        isolatedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
        }
    }
    @After fun removeOnlyTestFiles() { root.deleteRecursively() }

    @Test fun failedWritePreservesPreviousCommittedCopy() = runTest {
        LibraryBackupFileStore(isolatedContext).write(emptyJson)
        val failing = LibraryBackupFileStore(isolatedContext) { throw IOException("injected before commit") }
        var rejected = false
        try { failing.write(changedJson) } catch (_: IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals(emptyJson, LibraryBackupFileStore(isolatedContext).read())
    }

    @Test fun uncommittedAtomicWriteIsRecoveredByNewStoreInstance() = runTest {
        LibraryBackupFileStore(isolatedContext).write(emptyJson)
        // Model a process exit: close the descriptor, but neither finish nor fail the write.
        AtomicFile(backup).startWrite().use { stream ->
            stream.write(byteArrayOf(1, 2, 3))
            stream.fd.sync()
        }
        assertEquals(emptyJson, LibraryBackupFileStore(isolatedContext).read())
    }

    @Test fun legacyOldFileRecoversWhenBaseIsMissingAndTmpIsIncomplete() = runTest {
        LibraryBackupFileStore(isolatedContext).write(emptyJson)
        val previous = File(backup.parentFile, backup.name + ".old")
        assertTrue(backup.renameTo(previous))
        File(backup.parentFile, backup.name + ".tmp").writeBytes(byteArrayOf(1))
        assertEquals(emptyJson, LibraryBackupFileStore(isolatedContext).read())
        LibraryBackupFileStore(isolatedContext).write(changedJson)
        assertEquals(changedJson, LibraryBackupFileStore(isolatedContext).read())
        assertFalse(previous.exists())
    }

    @Test fun invalidJsonCannotOverwriteCommittedBackup() = runTest {
        val store = LibraryBackupFileStore(isolatedContext)
        store.write(emptyJson)
        var rejected = false
        try { store.write("not-json") } catch (_: IOException) { rejected = true }
        assertTrue(rejected)
        assertEquals(emptyJson, store.read())
    }

    @Test fun internalBackupIsEncryptedAndAuthenticatesItsContents() = runTest {
        val context = isolatedContext
        val file = File(File(context.filesDir, "backups"), "phonoteka-library.enc")
        val store = LibraryBackupFileStore(context)
        val json = LibraryBackupCodec.encode(emptyList(), emptyList())
        try {
            store.write(json)
            assertFalse(file.readText().contains("schemaVersion"))
            assertEquals(json, store.read())
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            file.writeBytes(bytes)
            val rejected = try { store.read(); false } catch (_: Exception) { true }
            assertTrue(rejected)
        } finally {
            file.delete()
        }
    }
}
