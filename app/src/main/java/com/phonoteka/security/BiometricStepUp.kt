package com.phonoteka.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** ЛР14: операции, для которых обязательно подтверждение биометрией класса Strong. */
enum class BiometricStepUpOperation(val resource: String) {
    AUDIT_READ("audit.read"), AUDIT_EXPORT("audit.export");
    companion object {
        fun fromResource(resource: String): BiometricStepUpOperation? = entries.firstOrNull { it.resource == resource }
    }
}

class BiometricStepUpRequired(reason: String) : SecurityException("Biometric step-up required: $reason")

/**
 * Чистая (JVM-тестируемая) логика разрешений:
 * - begin() выдаёт одноразовый nonce под конкретную операцию;
 * - confirm() принимает только тот же nonce + доказательство использования auth-per-use ключа;
 * - consume() срабатывает один раз, в течение TTL, для той же операции и той же версии локального входа.
 */
class BiometricStepUpGate(
    private val clock: () -> Long,
    private val ttlMillis: Long = 60_000L,
    private val nonces: () -> Long = { SecureRandom().nextLong() }
) {
    private data class Pending(val nonce: Long, val operation: BiometricStepUpOperation, val startedAt: Long)
    private data class Grant(val operation: BiometricStepUpOperation, val authVersion: Long, val issuedAt: Long)
    private var pending: Pending? = null
    private var grant: Grant? = null

    @Synchronized fun begin(operation: BiometricStepUpOperation): Long {
        grant = null // Новая попытка аннулирует неиспользованное старое разрешение.
        val nonce = nonces()
        pending = Pending(nonce, operation, clock())
        return nonce
    }

    @Synchronized fun confirm(nonce: Long, operation: BiometricStepUpOperation, authVersion: Long, cryptoProof: Boolean): Boolean {
        val p = pending ?: return false
        pending = null // nonce одноразовый даже при неудаче
        val now = clock()
        if (!cryptoProof || p.nonce != nonce || p.operation != operation || now - p.startedAt !in 0 until ttlMillis) return false
        grant = Grant(operation, authVersion, now)
        return true
    }

    @Synchronized fun consume(operation: BiometricStepUpOperation, authVersion: Long) {
        val g = grant ?: throw BiometricStepUpRequired("no grant")
        grant = null // одноразовое: любая попытка сжигает разрешение
        if (g.operation != operation) throw BiometricStepUpRequired("wrong operation")
        if (g.authVersion != authVersion) throw BiometricStepUpRequired("local session changed")
        if (clock() - g.issuedAt !in 0 until ttlMillis) throw BiometricStepUpRequired("expired")
    }

    @Synchronized fun invalidate() { pending = null; grant = null }
}

/** Android-часть: ключ AndroidKeyStore, который можно использовать только сразу после BIOMETRIC_STRONG. */
class AndroidAuditBiometricStepUp(val gate: BiometricStepUpGate) {
    class Prepared internal constructor(val operation: BiometricStepUpOperation, internal val nonce: Long, val cipher: Cipher)
    private val alias = "phonoteka.lab14.audit-stepup.per-use.v1"

    /** Делает новый nonce и шифр, который передаётся в BiometricPrompt.CryptoObject. */
    fun prepare(operation: BiometricStepUpOperation): Prepared {
        val cipher = try { initCipher() } catch (failure: Exception) {
            if (KeyLossPolicy.classify(failure) != KeyStoreFailure.KEY_INVALIDATED) throw failure
            // Ключ не хранит данных: после смены биометрии безопасно пересоздать.
            keyStore().deleteEntry(alias)
            initCipher()
        }
        return Prepared(operation, gate.begin(operation), cipher)
    }

    /** cipher должен быть именно тем, что разблокировал BiometricPrompt; doFinal без биометрии упадёт. */
    fun complete(prepared: Prepared, unlocked: Cipher?, authVersion: Long): Boolean {
        val proof = unlocked != null && unlocked === prepared.cipher &&
            runCatching { unlocked.doFinal(java.nio.ByteBuffer.allocate(8).putLong(prepared.nonce).array()).isNotEmpty() }.getOrDefault(false)
        return gate.confirm(prepared.nonce, prepared.operation, authVersion, proof)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun initCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(-1)
            }.build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }
}
