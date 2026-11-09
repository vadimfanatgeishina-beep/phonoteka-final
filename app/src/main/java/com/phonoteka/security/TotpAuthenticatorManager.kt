package com.phonoteka.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local TOTP enrollment for the supplied classroom Authenticator (6 digits / 60 sec). */
class TotpAuthenticatorManager(
    context: Context,
    private val localAuth: LocalAuthManager
) {
    private val prefs = context.applicationContext.getSharedPreferences("phonoteka_totp", Context.MODE_PRIVATE)
    private val alias = "phonoteka.authenticator.totp.aes.v1"
    private val random = SecureRandom()

    fun isEnabled(): Boolean = prefs.contains(KEY_ENABLED)

    /** Creates and encrypts a random 160-bit secret; returns its Base32 form once for enrollment UI. */
    @Synchronized fun beginEnrollment(): String {
        if (isEnabled()) return decodeSecret(prefs.getString(KEY_ENABLED, null)!!)
        val pending = prefs.getString(KEY_PENDING, null)
        if (pending != null) return decodeSecret(pending)
        val raw = ByteArray(20).also(random::nextBytes)
        val encoded = base32Encode(raw)
        raw.fill(0)
        prefs.edit().putString(KEY_PENDING, encrypt(encoded)).commit().also { check(it) }
        return encoded
    }

    @Synchronized fun confirmEnrollment(code: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val pending = prefs.getString(KEY_PENDING, null) ?: return false
        val secret = decodeSecret(pending)
        val acceptedStep = matchingStep(secret, code, nowMillis)
        if (acceptedStep == null) {
            localAuth.recordAudit("authenticator_enrollment_failed")
            return false
        }
        check(prefs.edit().putString(KEY_ENABLED, encrypt(secret)).remove(KEY_PENDING)
            .putLong(KEY_LAST_STEP, if (SecurityDecisions.otpSingleUse()) acceptedStep else -1L).commit())
        localAuth.recordAudit("authenticator_enrolled")
        return true
    }

    /** Verifies a user-entered TOTP and enforces same-step replay protection in hardened mode. */
    @Synchronized fun verifyAndConsume(code: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val stored = prefs.getString(KEY_ENABLED, null) ?: return false
        val secret = try { decodeSecret(stored) } catch (_: Exception) {
            localAuth.recordAudit("authenticator_storage_invalid")
            return false
        }
        val step = matchingStep(secret, code, nowMillis)
        if (step == null) {
            localAuth.recordAudit("authenticator_code_rejected")
            return false
        }
        if (SecurityDecisions.otpSingleUse() && step <= prefs.getLong(KEY_LAST_STEP, -1L)) {
            localAuth.recordAudit("authenticator_replay_denied")
            return false
        }
        if (SecurityDecisions.otpSingleUse()) {
            check(prefs.edit().putLong(KEY_LAST_STEP, step).commit())
        }
        localAuth.recordAudit("authenticator_code_accepted")
        return true
    }

    fun provisioningUri(secret: String, account: String = "ЛР13"): String {
        val label = URLEncoder.encode("Phonoteka:$account", Charsets.UTF_8.name())
        val issuer = URLEncoder.encode("Phonoteka", Charsets.UTF_8.name())
        return "otpauth://totp/$label?secret=$secret&issuer=$issuer&algorithm=SHA1&digits=6&period=60"
    }

    private fun matchingStep(secret: String, code: String, nowMillis: Long): Long? {
        if (!code.matches(Regex("\\d{6}"))) return null
        val current = nowMillis / STEP_MILLIS
        return (current - 1..current + 1).filter { it >= 0L }
            .lastOrNull { constantTimeEquals(totp(secret, it), code) }
    }

    private fun totp(secret: String, step: Long): String {
        val key = base32Decode(secret)
        val counter = ByteBuffer.allocate(8).putLong(step).array()
        val digest = Mac.getInstance("HmacSHA1").run {
            init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA1")); doFinal(counter)
        }
        key.fill(0)
        val offset = digest.last().toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        digest.fill(0)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun constantTimeEquals(expected: String, provided: String): Boolean =
        java.security.MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), provided.toByteArray(Charsets.US_ASCII))

    private fun decodeSecret(value: String): String = String(decrypt(value), Charsets.US_ASCII)

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val sealed = Base64.decode(value, Base64.NO_WRAP)
        require(sealed.size in 29..128)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return cipher.doFinal(sealed.copyOfRange(12, sealed.size))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun base32Encode(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun base32Decode(value: String): ByteArray {
        val normalized = value.uppercase().trim().trimEnd('=')
        require(normalized.isNotEmpty() && normalized.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
        val out = ByteArray(normalized.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in normalized) {
            buffer = (buffer shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(char)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[index++] = (buffer shr bits).toByte()
            }
        }
        return out
    }

    companion object {
        private const val KEY_PENDING = "pending_secret_enc"
        private const val KEY_ENABLED = "enabled_secret_enc"
        private const val KEY_LAST_STEP = "last_accepted_step"
        private const val STEP_MILLIS = 60_000L
    }
}
