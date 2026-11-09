package com.phonoteka.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One host authority owns this encrypted snapshot. Keep the store open for its entire lifetime. */
class AndroidIdentitySnapshotStore(
    context: Context,
    private val namespace: String = "host"
) : IdentitySnapshotStore, AutoCloseable {
    init { require(namespace.matches(Regex("[A-Za-z0-9_-]{1,80}"))) }
    private val directory = File(context.noBackupFilesDir, "identity/$namespace").apply {
        check(isDirectory || mkdirs()) { "Identity directory unavailable" }
    }
    private val snapshot = File(directory, "accounts.aesgcm")
    private val pending = File(directory, "accounts.pending")
    private val alias = "phonoteka.identity.$namespace.aes.v1"
    private val aad = "phonoteka.identity.snapshot.v1:$namespace".toByteArray(Charsets.UTF_8)
    private val lockFile = RandomAccessFile(File(directory, "authority.lock"), "rw")
    private val ownership = try {
        lockFile.channel.tryLock() ?: throw IOException("Identity authority is already open")
    } catch (failure: Exception) { lockFile.close(); throw failure }
    private var closed = false
    private var loaded = false
    private var committed = false

    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun existingKey(): SecretKey? = keys().getKey(alias, null) as? SecretKey

    @Synchronized override fun load(): ByteArray? {
        check(!closed) { "Identity store is closed" }
        if (!snapshot.exists()) {
            check(existingKey() == null) { "Identity snapshot is missing; recovery required" }
            loaded = true
            return null
        }
        val key = existingKey() ?: throw IOException("Identity key is missing; recovery required")
        if (snapshot.length() !in MIN_BLOB.toLong()..MAX_BLOB.toLong()) throw IOException("Invalid identity snapshot size")
        val blob = snapshot.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BLOB) throw IOException("Invalid identity snapshot size")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        try {
            if (blob.size !in MIN_BLOB..MAX_BLOB || ByteBuffer.wrap(blob, 0, 4).int != MAGIC)
                throw IOException("Invalid identity snapshot envelope")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(4, 16)))
            cipher.updateAAD(aad)
            return cipher.doFinal(blob, 16, blob.size - 16).also { loaded = true; committed = true }
        } finally { blob.fill(0) }
    }

    @Synchronized override fun save(snapshot: ByteArray) {
        check(!closed && loaded) { "Load identity store before saving" }
        require(snapshot.size in 1..MAX_SNAPSHOT)
        val key = existingKey() ?: run {
            check(!committed && !this.snapshot.exists()) { "Identity key is missing; recovery required" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setKeySize(256).build())
            }.generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(snapshot)
        try {
            FileOutputStream(pending).use {
                it.write(ByteBuffer.allocate(4).putInt(MAGIC).array())
                check(cipher.iv.size == 12)
                it.write(cipher.iv); it.write(ciphertext); it.fd.sync()
            }
            // POSIX rename replaces the old file atomically. Nothing fallible follows the commit.
            Os.rename(pending.absolutePath, this.snapshot.absolutePath)
            committed = true
        } catch (failure: Exception) {
            pending.delete()
            throw IOException("Identity snapshot was not saved", failure)
        } finally { ciphertext.fill(0) }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { ownership.release() } finally { lockFile.close() }
    }

    companion object {
        private const val MAGIC = 0x50484531
        private const val MAX_SNAPSHOT = 2_000_000
        private const val MIN_BLOB = 4 + 12 + 16 + 1
        private const val MAX_BLOB = 4 + 12 + 16 + MAX_SNAPSHOT
    }
}
