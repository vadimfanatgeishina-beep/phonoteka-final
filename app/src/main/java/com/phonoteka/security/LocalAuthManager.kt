package com.phonoteka.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.Cipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PinResult { SUCCESS, INVALID, LOCKED, NOT_CONFIGURED }

/** Local-only authentication for the student demo. PIN and recovery code are never stored as plaintext. */
class LocalAuthManager(context: Context, private val recoveryNotifier: RecoveryNotifier = MockRecoveryOutbox.shared,
    /** Сводный журнал ЛР13–16: только факт события, без PIN/OTP/кода восстановления. */
    private val unifiedAudit: UnifiedAuditLog? = null) {
    @Volatile var lastRecoveryNotices: List<RecoveryNotice> = emptyList(); private set
    private val prefs = context.getSharedPreferences("phonoteka_auth", Context.MODE_PRIVATE)
    private val keyAlias = "phonoteka.local-auth.hmac.v1"
    private var lastAuthenticationElapsed: Long? = null
    private var authenticationVersion = 0L
    @Synchronized fun authenticationSnapshot() = AuthenticationSnapshot(authenticationVersion, lastAuthenticationElapsed)
    @Synchronized private fun markAuthenticated() {
        authenticationVersion = Math.addExact(authenticationVersion, 1L)
        lastAuthenticationElapsed = android.os.SystemClock.elapsedRealtime()
        _authState.value = AuthenticationSnapshot(authenticationVersion, lastAuthenticationElapsed)
    }
    private val auditSigner = AndroidKeystoreAuditExport()
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /**
     * Наблюдаемое состояние локальной аутентификации.
     * Профиль и журнал показывают, активна ли сессия, без запроса PIN или кода.
     */
    private val _authState = MutableStateFlow(AuthenticationSnapshot(0L, null))
    val authenticationSnapshotFlow: StateFlow<AuthenticationSnapshot> = _authState.asStateFlow()

    val isConfigured: Boolean get() = prefs.contains("pin_mac")
    val lockoutUntil: Long get() = prefs.getLong("lockout_until", 0L)

    @Synchronized
    fun setup(pin: String): String {
        check(!isConfigured || isFreshAuthentication()) { "Fresh authentication required to replace PIN" }
        require(pin.matches(Regex("\\d{4,6}"))) { "PIN должен содержать от 4 до 6 цифр" }
        val recovery = newRecoveryCode()
        prefs.edit()
            .putString("pin_mac", mac("pin:$pin"))
            .putString("recovery_mac", mac("recovery:$recovery"))
            .putInt("failures", 0).putLong("lockout_until", 0L)
            .putLong("lockout_count", 0L).commit().also { check(it) }
        recordAudit("pin_setup")
        markAuthenticated()
        return recovery
    }

    @Synchronized
    fun verifyPin(pin: String, now: Long = System.currentTimeMillis()): PinResult {
        if (!isConfigured) return PinResult.NOT_CONFIGURED
        if (now < lockoutUntil) { recordAudit("pin_locked_attempt"); return PinResult.LOCKED }
        val expected = prefs.getString("pin_mac", null) ?: return PinResult.NOT_CONFIGURED
        val valid = runCatching { MessageDigest.isEqual(expected.toByteArray(), mac("pin:$pin").toByteArray()) }.getOrDefault(false)
        if (valid) {
            prefs.edit().putInt("failures", 0).putLong("lockout_until", 0L).commit().also { check(it) }
            recordAudit("pin_success")
            markAuthenticated()
            return PinResult.SUCCESS
        }
        val failures = prefs.getInt("failures", 0) + 1
        val editor = prefs.edit().putInt("failures", failures)
        if (failures >= 5) {
            val count = prefs.getLong("lockout_count", 0L) + 1L
            val duration = (30_000L shl count.coerceAtMost(4).toInt()).coerceAtMost(10 * 60_000L)
            editor.putLong("lockout_until", now + duration).putLong("lockout_count", count).putInt("failures", 0)
        }
        editor.commit().also { check(it) }
        recordAudit(if (failures >= 5) "pin_lockout_started" else "pin_failed")
        return if (failures >= 5) PinResult.LOCKED else PinResult.INVALID
    }

    @Synchronized
    fun recover(recoveryCode: String, newPin: String): String? {
        require(newPin.matches(Regex("\\d{4,6}"))) { "PIN должен содержать от 4 до 6 цифр" }
        val expected = prefs.getString("recovery_mac", null) ?: return null
        val normalized = recoveryCode.uppercase().replace("-", "").trim()
        val actual = runCatching { mac("recovery:$normalized") }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())) {
            recordAudit("recovery_failed")
            return null
        }
        // ЛР13: одноразовость кода восстановления. В защищённой сборке использованный
        // код помечается и повторно не принимается. В учебной уязвимой сборке код
        // можно применить повторно — это и есть демонстрируемая ошибка.
        if (SecurityDecisions.otpSingleUse()) {
            val used = prefs.getString("used_recovery_macs", "") ?: ""
            if (used.split(';').any { it.isNotEmpty() && MessageDigest.isEqual(it.toByteArray(), actual.toByteArray()) }) {
                recordAudit("recovery_replay_denied")
                return null
            }
            val updated = (used.split(';').filter { it.isNotEmpty() } + actual).takeLast(10).joinToString(";")
            prefs.edit().putString("used_recovery_macs", updated).commit().also { check(it) }
        }
        val newRecovery = newRecoveryCode()
        prefs.edit().putString("pin_mac", mac("pin:$newPin"))
            .putString("recovery_mac", mac("recovery:$newRecovery"))
            .putInt("failures", 0).putLong("lockout_until", 0L).commit().also { check(it) }
        recordAudit("recovery_success_pin_rotated")
        // ЛР13: владелец узнаёт об использовании recovery-кода (мок e-mail/SMS, без кода в тексте).
        val notices = runCatching { recoveryNotifier.recoveryCodeUsed(System.currentTimeMillis()) }.getOrDefault(emptyList())
        lastRecoveryNotices = notices
        recordAudit(if (notices.isEmpty()) "recovery_notification_failed" else "recovery_notification_sent:" + notices.joinToString(",") { it.channel })
        markAuthenticated()
        return newRecovery
    }

    /** Called only after the actual authenticated refresh has produced a durable rotated session. */
    @Synchronized internal fun markRestoredBiometricSession() {
        // свежесть входа хранится только в памяти (fail-closed после process death)
        recordAudit("biometric_refresh_restored")
        markAuthenticated()
    }

    @Synchronized fun lock() {
        lastAuthenticationElapsed = null
        _authState.value = AuthenticationSnapshot(authenticationVersion, null)
        runCatching { recordAudit("local_session_locked") }
    }
    @Synchronized fun remainingFreshMillis(now: Long = android.os.SystemClock.elapsedRealtime()): Long =
        lastAuthenticationElapsed?.let { (5 * 60_000L - (now - it)).coerceIn(0, 5 * 60_000L) } ?: 0L
    @Synchronized fun isFreshAuthentication(now: Long = android.os.SystemClock.elapsedRealtime()): Boolean =
        lastAuthenticationElapsed?.let { now - it in 0 until (5 * 60_000L) } ?: false
    /** UI session remains open; sensitive library operations still use the shorter fresh-auth window. */
    @Synchronized fun remainingSessionMillis(now: Long = android.os.SystemClock.elapsedRealtime()): Long =
        lastAuthenticationElapsed?.let { (30 * 60_000L - (now - it)).coerceIn(0, 30 * 60_000L) } ?: 0L
    @Synchronized fun isSessionActive(now: Long = android.os.SystemClock.elapsedRealtime()): Boolean =
        remainingSessionMillis(now) > 0L

    @Synchronized fun recordAudit(event: String) {
        val rows = auditEntries().takeLast(99)
        val safe = event.take(80).replace(Regex("[|\\r\\n]"), "/")
        val authenticated = String(auditSigner.encode(rows + "${System.currentTimeMillis()}|$safe"), Charsets.UTF_8)
        check(prefs.edit().putString("audit_authenticated_v2", authenticated).remove("audit").commit())
        writeUnified(event)
    }

    /**
     * Дублирует событие в единый журнал ЛР13–16.
     * В журнал уходит ТОЛЬКО имя события: PIN, OTP, код восстановления и токены не пишутся.
     */
    private fun writeUnified(event: String) {
        val log = unifiedAudit ?: return
        val type = when {
            event.startsWith("pin_success") || event.startsWith("biometric_refresh_restored") -> AuditEventType.LOGIN
            event.startsWith("pin_failed") || event.startsWith("biometric_not_recognized") -> AuditEventType.LOGIN_FAILED
            event.startsWith("pin_lockout") -> AuditEventType.LOCK
            event.startsWith("pin_setup") -> AuditEventType.PIN_SET
            event.startsWith("recovery_success") -> AuditEventType.RECOVERY
            event.startsWith("recovery_failed") -> AuditEventType.LOGIN_FAILED
            event.startsWith("local_session_locked") -> AuditEventType.LOCK
            event.startsWith("biometric_cancel") -> AuditEventType.LOGIN_FAILED
            else -> AuditEventType.SESSION
        }
        val source = if (event.startsWith("recovery")) AuditSource.RECOVERY else AuditSource.AUTH
        val result = if (event.contains("failed") || event.contains("not_recognized")) "DENIED" else "OK"
        log.record(
            type = type,
            source = source,
            userId = "local-user",
            role = "EMPLOYEE",
            action = event.take(80),
            subject = "local_auth",
            result = result
        )
    }
    @Synchronized fun auditEntries(): List<String> {
        val document = prefs.getString("audit_authenticated_v2", null) ?: return emptyList()
        check(auditSigner.verify(document.toByteArray(Charsets.UTF_8))) { "Authentication audit integrity failed" }
        val array = org.json.JSONObject(document).getJSONArray("events")
        return List(array.length()) { array.getString(it) }
    }

    private fun newRecoveryCode(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.map { alphabet[(it.toInt() and 0xff) % alphabet.length] }.joinToString("")
    }

    private fun mac(value: String): String {
        val key = getOrCreateKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        return generator.generateKey()
    }
}
