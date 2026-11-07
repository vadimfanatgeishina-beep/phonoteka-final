package com.phonoteka.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional per-operation BIOMETRIC_STRONG wrapper of the REAL owned mock refresh, not a proof string.
 * Separate from credential-capable 300s fallback store. All requests are ephemeral; no key fallback.
 */
class AndroidBiometricRefreshVault(context: Context, fixtureNamespace: String? = null, private val keyLoss: KeyLossJournal = KeyLossJournal.NONE) : BiometricRefreshVault {
    init { require(fixtureNamespace == null || fixtureNamespace.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) }
    private val app = context.applicationContext
    private val alias = "phonoteka.lab13.biometric-refresh.per-use.v1" + (fixtureNamespace?.let { ".fixture.$it" } ?: "")
    private val atomic = AtomicFile(File(app.filesDir, "biometric-refresh" + (fixtureNamespace?.let { ".$it" } ?: "") + ".enc"))
    private val aad = ("phonoteka-biometric-refresh-v1:" + app.packageName + ":" + alias).toByteArray(Charsets.UTF_8)
    @Synchronized override fun hasSavedRefresh() = atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()
    @Synchronized override fun clear() { atomic.delete(); check(!hasSavedRefresh()) { "Biometric blob removal failed" } }
    private fun key(create: Boolean): SecretKey {
        check((app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) { "Secure lockscreen required" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw KeyMissingException()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else { @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(spec.build()); return generator.generateKey()
    }
    @Synchronized override fun prepareEnrollment(refresh: String): BiometricCryptoRequest {
        require(refresh.matches(Regex("[a-f0-9]{64}")))
        fun initialize() = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(true))
        }
        val cipher = try { initialize() } catch (invalidated: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // Only explicit ENROLL after fresh local auth can replace an invalidated key.
            clear()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            initialize()
        }
        return BiometricCryptoRequest(cipher) { authorized -> synchronized(this) {
            check(authorized === cipher)
            authorized.updateAAD(aad) // Only AFTER the per-use BiometricPrompt authorization.
            val sealed = authorized.iv + authorized.doFinal(refresh.toByteArray(Charsets.US_ASCII))
            var output: java.io.FileOutputStream? = null
            try {
                output = atomic.startWrite(); output.write(sealed); output.fd.sync()
                atomic.finishWrite(output); output = null
            } catch (failure: Exception) { output?.let { atomic.failWrite(it) }; throw failure }
            null
        } }
    }
    private fun readBlob(): ByteArray = atomic.openRead().use { input ->
        val bytes = input.readBytesBounded(256)
        if (bytes.size != 12 + 64 + 16) throw InvalidBlobException(); bytes
    }
    @Synchronized override fun prepareRestore(): BiometricCryptoRequest {
        val (snapshot, cipher) = try {
            val blob = readBlob()
            if (!(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure)
                throw KeyLostException(KeyStoreFailure.KEY_INVALIDATED)
            blob to Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            }
        } catch (failure: Exception) {
            // ЛР13: ключ потерян/блоб испорчен — удалить невосстановимое, записать причину, потребовать вход по паролю.
            val kind = KeyLossPolicy.classify(failure)
            if (kind == KeyStoreFailure.LOCKED || kind == KeyStoreFailure.TRANSIENT) throw failure
            clear()
            if (kind != KeyStoreFailure.CORRUPT_BLOB) runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
            keyLoss.record(kind, "biometric-vault")
            throw (failure as? KeyLostException) ?: KeyLostException(kind)
        }
        return BiometricCryptoRequest(cipher) { authorized -> synchronized(this) {
            check(authorized === cipher && MessageDigest.isEqual(snapshot, readBlob())) { "Biometric blob changed" }
            authorized.updateAAD(aad)
            val plain = authorized.doFinal(snapshot.copyOfRange(12, snapshot.size))
            try {
                check(plain.size == 64)
                val refresh = String(plain, Charsets.US_ASCII)
                check(refresh.matches(Regex("[a-f0-9]{64}")))
                // Consume saved snapshot BEFORE refresh rotation. Later optional enrollment uses NEW token.
                clear(); refresh
            } finally { plain.fill(0) }
        } }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(256)
        while (true) { val n = read(buffer); if (n < 0) break; check(out.size() + n <= limit); out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}
