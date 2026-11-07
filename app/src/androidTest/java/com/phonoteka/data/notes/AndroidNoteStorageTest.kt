package com.phonoteka.data.notes

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses only isolated files/aliases, never the installed user's note store. */
class AndroidNoteStorageTest {
    @Test fun emptyManifestThenFirstSaveCreatesBothKeys() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "note-test-${UUID.randomUUID()}")
        val indexFile = File(root, "index.json")
        val crypto = KeystoreNoteCrypto({ indexFile.exists() || File(root, "index.json.bak").exists() },
            "phonoteka.instrumentation.notes.${UUID.randomUUID()}")
        try {
            val repository = FileTrackNotesRepository(root, File(root, "drafts"), crypto,
                AndroidAtomicNoteIndex(indexFile), { it == 42L })
            repository.purgeTrash() // Can commit an empty authenticated index on first launch.
            repository.save(TrackNote(42, "Local test", "Owned fixture"))
            assertEquals("Owned fixture", repository.list().single().note.text)
        } finally { crypto.resetKeys(); root.deleteRecursively() }
    }
    @Test fun firstDraftBeforeAnyManifestCreatesBothKeysAndCanBeCommittedLater() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "draft-first-test-${UUID.randomUUID()}")
        val drafts = File(root, "drafts")
        val indexFile = File(root, "index.json")
        val crypto = KeystoreNoteCrypto({ indexFile.exists() || drafts.listFiles()?.any { it.name.endsWith(".enc") } == true },
            "phonoteka.instrumentation.draft-first.${UUID.randomUUID()}")
        try {
            val repo = FileTrackNotesRepository(root, drafts, crypto, AndroidAtomicNoteIndex(indexFile), { it == 42L })
            val note = TrackNote(42, "Local fixture", "Draft first")
            repo.saveDraft(note) // No purgeTrash/index bootstrap before this operation.
            assertEquals(note, repo.draft(42))
            repo.purgeTrash(); repo.save(note)
            assertEquals(note, repo.list().single().note.copy(updatedAt = 0))
            assertNull(repo.draft(42))
        } finally { crypto.resetKeys(); root.deleteRecursively() }
    }
    @Test fun freshNoteKeyIs256BitsNonAuthBoundAndUsesDifferentIvs() {
        val namespace = "phonoteka.instrumentation.key-policy.${UUID.randomUUID()}"
        val crypto = KeystoreNoteCrypto(namespace = namespace)
        try {
            val first = crypto.encrypt("owned-fixture".toByteArray())
            val second = crypto.encrypt("owned-fixture".toByteArray())
            assertFalse(first.copyOfRange(0, 12).contentEquals(second.copyOfRange(0, 12)))
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = store.getKey("$namespace.aes.v1", null) as javax.crypto.SecretKey
            val info = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
            assertEquals(256, info.keySize); assertFalse(info.isUserAuthenticationRequired)
        } finally { crypto.resetKeys() }
    }
    @Test fun committedIndexSurvivesUnfinishedAtomicWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "index-test-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(root, "index.json")
        try {
            val index = AndroidAtomicNoteIndex(file)
            val original = "committed local fixture".toByteArray()
            index.write(original)
            val interrupted = AtomicFile(file).startWrite()
            interrupted.use { it.write("partial".toByteArray()); it.fd.sync() }
            assertArrayEquals(original, AndroidAtomicNoteIndex(file).read())
            index.write("next local fixture".toByteArray())
            assertArrayEquals("next local fixture".toByteArray(), index.read())
        } finally { root.deleteRecursively() }
    }
}
