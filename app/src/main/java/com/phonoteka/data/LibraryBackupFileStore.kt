package com.phonoteka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.util.AtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LibraryBackupFileStore(
    context: Context,
    // Test-only fault seam, after synced ciphertext and before AtomicFile.finishWrite.
    private val beforeCommit: () -> Unit = {}
) {
    private val file = File(File(context.filesDir, "backups"), "phonoteka-library.enc")
    private val keyAlias = "phonoteka.library-backup.v1"
    private val atomic = AtomicFile(file)

    suspend fun write(json: String): File = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            LibraryBackupCodec.decode(json)
            val ciphertext = encrypt(json.toByteArray(Charsets.UTF_8))
            if (ciphertext.size > MAX_FILE_BYTES) throw IOException("Резервная копия больше 25 МБ")
            if (file.parentFile?.mkdirs() == false && file.parentFile?.isDirectory != true) {
                throw IOException("Не удалось создать каталог резервных копий")
            }
            recoverLegacyRename()
            val stream = atomic.startWrite()
            var finished = false
            try {
                stream.write(ciphertext)
                stream.fd.sync()
                beforeCommit()
                finished = true // Never rollback once finishWrite has been invoked.
                atomic.finishWrite(stream)
                // AtomicFile can log rename failures instead of throwing on some API levels.
                if (!atomic.openRead().use { it.readBytes().contentEquals(ciphertext) }) {
                    throw IOException("Не удалось подтвердить запись резервной копии")
                }
                File(file.parentFile, file.name + ".old").delete()
                File(file.parentFile, file.name + ".tmp").delete()
                file
            } catch (error: Exception) {
                // failWrite after finishWrite can delete the committed base on older Android versions.
                if (!finished) {
                    try { atomic.failWrite(stream) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                }
                throw error
            }
        }
    }

    suspend fun read(): String = withContext(Dispatchers.IO) {
        backupMutex.withLock {
            recoverLegacyRename()
            // openRead restores AtomicFile's .bak or discards an uncommitted .new before reading.
            val bytes = atomic.openRead().use { stream ->
                if (stream.channel.size() > MAX_FILE_BYTES) throw IOException("Резервная копия больше 25 МБ")
                stream.readBytes()
            }
            decrypt(bytes).toString(Charsets.UTF_8).also { LibraryBackupCodec.decode(it) }
        }
    }

    private fun recoverLegacyRename() {
        val previous = File(file.parentFile, file.name + ".old")
        val atomicPrevious = File(file.parentFile, file.name + ".bak")
        if (!file.exists() && !atomicPrevious.exists() && previous.exists() && !previous.renameTo(file)) {
            throw IOException("Не удалось восстановить предыдущую резервную копию")
        }
    }

    companion object {
        // AtomicFile itself provides no cross-instance synchronization. One app process owns this file.
        private val backupMutex = Mutex()
        private const val MAX_FILE_BYTES = 25 * 1024 * 1024
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        val header = "PHBK1".toByteArray(Charsets.US_ASCII)
        return header + cipher.iv + encrypted
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size < 5 + 12 + 16 || !payload.copyOfRange(0, 5).contentEquals("PHBK1".toByteArray(Charsets.US_ASCII))) {
            throw IOException("Внутренняя резервная копия повреждена или имеет неизвестный формат")
        }
        val iv = payload.copyOfRange(5, 17)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return try { cipher.doFinal(payload.copyOfRange(17, payload.size)) }
        catch (e: Exception) { throw IOException("Не удалось расшифровать копию: ключ недоступен или файл повреждён", e) }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
}
