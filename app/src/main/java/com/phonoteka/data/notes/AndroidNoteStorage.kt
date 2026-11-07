package com.phonoteka.data.notes

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidAtomicNoteIndex(file: File) : AtomicNoteIndex {
    private val atomic = AtomicFile(file)
    override fun read(): ByteArray? = try {
        atomic.openRead().use { stream ->
            if (stream.channel.size() > 5 * 1024 * 1024) throw NoteException(NoteError.TooLarge)
            stream.readBytes()
        }
    } catch (_: java.io.FileNotFoundException) { null }
    override fun write(bytes: ByteArray) {
        val stream = atomic.startWrite()
        var finishInvoked = false
        try {
            stream.write(bytes)
            stream.fd.sync()
            // finishWrite may log a rename failure instead of throwing on older Android.
            // Never call failWrite after finishWrite: it could erase a committed base file.
            finishInvoked = true
            atomic.finishWrite(stream)
            if (!bytes.contentEquals(read())) throw java.io.IOException("Index commit verification failed")
        } catch (e: Exception) {
            if (!finishInvoked) atomic.failWrite(stream)
            throw e
        }
    }
}
/** Non-auth-bound keys are intentional for Lab 11; this is not the Lab 13 session key. */
class KeystoreNoteCrypto(
    private val hasExistingData: () -> Boolean = { false },
    private val namespace: String = "phonoteka.track-notes"
) : NoteCrypto {
    private fun key(kind: String): SecretKey = try {
        val alias = "$namespace.$kind.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: run {
            if (hasExistingData()) throw NoteException(NoteError.KeyUnavailable)
            val algorithm = if (kind == "aes") KeyProperties.KEY_ALGORITHM_AES else KeyProperties.KEY_ALGORITHM_HMAC_SHA256
            val generator = KeyGenerator.getInstance(algorithm, "AndroidKeyStore")
            val purposes = if (kind == "aes") KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT else KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            val builder = KeyGenParameterSpec.Builder(alias, purposes).setUserAuthenticationRequired(false)
            if (kind == "aes") builder.setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            else builder.setDigests(KeyProperties.DIGEST_SHA256)
            generator.init(builder.build()); generator.generateKey()
        }
    } catch (_: Exception) { throw NoteException(NoteError.KeyUnavailable) }
    private fun <T> cryptoOperation(action: () -> T): T = try { action() }
    catch (_: javax.crypto.AEADBadTagException) { throw NoteException(NoteError.Corrupted) }
    catch (_: java.security.GeneralSecurityException) { throw NoteException(NoteError.KeyUnavailable) }
    override fun encrypt(plain: ByteArray): ByteArray = cryptoOperation {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key("aes"))
        cipher.updateAAD(AAD)
        cipher.iv + cipher.doFinal(plain)
    }
    override fun decrypt(cipher: ByteArray): ByteArray = cryptoOperation {
        if (cipher.size < 28) throw NoteException(NoteError.Corrupted)
        val decryptor = Cipher.getInstance("AES/GCM/NoPadding")
        decryptor.init(Cipher.DECRYPT_MODE, key("aes"), GCMParameterSpec(128, cipher.copyOfRange(0, 12)))
        decryptor.updateAAD(AAD)
        decryptor.doFinal(cipher.copyOfRange(12, cipher.size))
    }
    override fun resetKeys() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf("aes", "hmac").forEach { store.deleteEntry("$namespace.$it.v1") }
    }
    override fun mac(bytes: ByteArray): ByteArray = cryptoOperation {
        // Both keys must exist BEFORE committing the first (possibly empty) manifest.
        // Otherwise purgeTrash on first launch would make a later AES generation look
        // like loss of a previously existing key and prevent the first note save.
        key("aes")
        Mac.getInstance("HmacSHA256").run { init(key("hmac")); doFinal(bytes) }
    }
    companion object { private val AAD = "phonoteka-track-notes-v2".toByteArray() }
}
