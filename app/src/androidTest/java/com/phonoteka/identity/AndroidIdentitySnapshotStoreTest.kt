package com.phonoteka.identity

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Isolated keys and noBackup directories; never opens the user's host identity database. */
class AndroidIdentitySnapshotStoreTest {
    private fun fixture(block: (android.content.Context, String, File, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, "identity/$name")
        val alias = "phonoteka.identity.$name.aes.v1"
        try { block(context, name, root, alias) }
        finally {
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun encryptedAccountsAndSessionSurviveReopeningStore() = fixture { context, name, root, _ ->
        lateinit var tokens: IdentityTokens
        AndroidIdentitySnapshotStore(context, name).use { store ->
            val authority = PhoneIdentityAuthority(store)
            authority.bootstrapOwner("owner", "owner-password-123")
            tokens = authority.login("owner", "owner-password-123", "host fixture")
        }
        val blob = File(root, "accounts.aesgcm").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(blob.contains("owner"))
        assertFalse(blob.contains("owner-password-123"))
        assertFalse(blob.contains(tokens.access))
        AndroidIdentitySnapshotStore(context, name).use { store ->
            val authority = PhoneIdentityAuthority(store)
            assertEquals(IdentityRole.ADMIN, authority.currentUser(tokens.access).role)
            val rotated = authority.refresh(tokens.refresh)
            assertEquals("owner", authority.currentUser(rotated.access).login)
        }
    }

    @Test fun tamperedSnapshotIsRejectedWithoutReplacingData() = fixture { context, name, root, _ ->
        AndroidIdentitySnapshotStore(context, name).use { store ->
            assertNull(store.load()); store.save("owned snapshot".toByteArray())
        }
        val file = File(root, "accounts.aesgcm")
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        AndroidIdentitySnapshotStore(context, name).use { store ->
            assertTrue(runCatching { PhoneIdentityAuthority(store) }.isFailure)
        }
        assertArrayEquals(damaged, file.readBytes())
    }

    @Test fun lostKeyOrSnapshotCannotSilentlyBootstrapNewOwner() = fixture { context, name, root, alias ->
        AndroidIdentitySnapshotStore(context, name).use { store ->
            assertNull(store.load()); store.save("owned snapshot".toByteArray())
        }
        val file = File(root, "accounts.aesgcm")
        val original = file.readBytes()
        assertTrue(file.delete())
        AndroidIdentitySnapshotStore(context, name).use { store -> assertTrue(runCatching { store.load() }.isFailure) }
        file.writeBytes(original)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        AndroidIdentitySnapshotStore(context, name).use { store -> assertTrue(runCatching { store.load() }.isFailure) }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun exclusiveOwnershipAndFailedSavePreservePriorSnapshot() = fixture { context, name, root, _ ->
        AndroidIdentitySnapshotStore(context, name).use { store ->
            assertTrue(runCatching { AndroidIdentitySnapshotStore(context, name).close() }.isFailure)
            assertNull(store.load()); store.save("previous snapshot".toByteArray())
            // A directory at the temporary filename makes openOutput fail before atomic replacement.
            val obstruction = File(root, "accounts.pending").apply { mkdir() }
            File(obstruction, "blocker").writeText("fixture")
            assertTrue(runCatching { store.save("next snapshot".toByteArray()) }.isFailure)
            assertArrayEquals("previous snapshot".toByteArray(), store.load())
        }
    }
}
