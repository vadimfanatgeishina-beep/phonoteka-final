package com.phonoteka.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.SecretKey

/** Educational local fixture only. Never use this fake account or endpoint in production. */
data class MockTokenPair(
    val accessToken: String,
    val accessExpiresAtMillis: Long,
    val refreshToken: String,
    val refreshExpiresAtMillis: Long
)

sealed interface MockLoginResult {
    data class Success(val tokens: MockTokenPair) : MockLoginResult
    data object InvalidCredentials : MockLoginResult
    data class Locked(val retryAfterMillis: Long) : MockLoginResult
}

interface MockAuthEndpoint {
    fun login(username: String, password: String): MockLoginResult
    fun refresh(refreshToken: String): MockTokenPair?
    fun revoke(refreshToken: String)
}

/** State backing the in-app mock server; separate from the encrypted client refresh-token store. */
interface MockAuthStateStore {
    fun getString(key: String): String?
    fun getLong(key: String, defaultValue: Long = 0L): Long
    fun putString(key: String, value: String)
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}

class PreferencesMockAuthStateStore(context: Context) : MockAuthStateStore {
    private val prefs = context.applicationContext.getSharedPreferences("phonoteka_lab13_mock_server", Context.MODE_PRIVATE)
    init {
        // Invalidate legacy bearer-token ledger entries in one commit; never print their names.
        if (prefs.getInt("ledger_version", 0) != 3) {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("access:") || it.startsWith("refresh:") }
                .forEach { editor.remove(it) }
            check(editor.putInt("ledger_version", 3).commit())
        }
    }
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getLong(key: String, defaultValue: Long): Long = prefs.getLong(key, defaultValue)
    override fun putString(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) }
    override fun putLong(key: String, value: Long) { check(prefs.edit().putLong(key, value).commit()) }
    override fun remove(key: String) { check(prefs.edit().remove(key).commit()) }
}

/**
 * In-app mock endpoint with persistent fixture state so a client repository can be recreated after
 * process death. The ledger is not a real server boundary; only the client's refresh token is
 * encrypted with Android Keystore. Credentials are intentionally public test data.
 */
class LocalMockAuthEndpoint(
    private val state: MockAuthStateStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val accessTtlMillis: Long = 60_000L,
    private val refreshTtlMillis: Long = 7 * 24 * 60 * 60_000L,
    private val lockoutMillis: Long = 5 * 60_000L
) : MockAuthEndpoint {
    @Synchronized
    override fun login(username: String, password: String): MockLoginResult {
        val userKey = username.take(64).replace(Regex("[^A-Za-z0-9_.@-]"), "_")
        val lockKey = "lockUntil:$userKey"
        val now = nowMillis()
        val lockedUntil = state.getLong(lockKey)
        if (now < lockedUntil) return MockLoginResult.Locked(lockedUntil - now)
        if (lockedUntil != 0L) {
            state.remove(lockKey)
            state.remove("failures:$userKey")
        }

        val accepted = username == DEMO_USER && MessageDigest.isEqual(
            password.toByteArray(Charsets.UTF_8), DEMO_PASSWORD.toByteArray(Charsets.UTF_8)
        )
        if (!accepted) {
            val failures = state.getLong("failures:$userKey").toInt() + 1
            if (failures >= MAX_FAILURES) {
                state.putLong(lockKey, now + lockoutMillis)
                state.remove("failures:$userKey")
                return MockLoginResult.Locked(lockoutMillis)
            }
            state.putLong("failures:$userKey", failures.toLong())
            return MockLoginResult.InvalidCredentials
        }

        state.remove(lockKey)
        state.remove("failures:$userKey")
        return MockLoginResult.Success(issue(username, now))
    }

    @Synchronized
    override fun refresh(refreshToken: String): MockTokenPair? {
        val key = ledgerKey("refresh", refreshToken)
        val stored = state.getString(key) ?: return null
        state.remove(key) // rotate before returning a replacement; the old value cannot be replayed.
        val parts = stored.split('|', limit = 2)
        val expiry = parts.getOrNull(1)?.toLongOrNull() ?: return null
        if (parts.size != 2 || nowMillis() >= expiry) return null
        return issue(parts[0], nowMillis())
    }

    @Synchronized
    override fun revoke(refreshToken: String) {
        state.remove(ledgerKey("refresh", refreshToken))
    }

    private fun issue(username: String, now: Long): MockTokenPair {
        val access = randomToken()
        val refresh = randomToken()
        val accessExpiry = now + accessTtlMillis
        val refreshExpiry = now + refreshTtlMillis
        state.putLong(ledgerKey("access", access), accessExpiry)
        state.putString(ledgerKey("refresh", refresh), "$username|$refreshExpiry")
        return MockTokenPair(access, accessExpiry, refresh, refreshExpiry)
    }

    private fun ledgerKey(kind: String, token: String): String = "$kind:sha256:" +
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEMO_USER = "student"
        const val DEMO_PASSWORD = "phonoteka-demo-only"
        private const val MAX_FAILURES = 4
    }
}

interface RefreshTokenStore {
    /** Check auth-bound key availability BEFORE consuming a server refresh. No-op for JVM fakes. */
    fun prepareWrite() {}
    fun read(): String?
    fun write(refreshToken: String)
    fun clear()
}

/** Stores only the refresh token; access tokens remain memory-only and are reissued by refresh. */
class AndroidKeystoreRefreshTokenStore(
    context: Context,
    private val requireSystemAuthentication: Boolean = true,
    // Owned instrumentation only; null is the actual application namespace.
    private val fixtureNamespace: String? = null,
    /** ЛР13: журнал причин потери ключа для экрана входа. */
    private val keyLoss: KeyLossJournal = KeyLossJournal.NONE
) : RefreshTokenStore {
    init {
        require(fixtureNamespace == null || fixtureNamespace.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
        if (requireSystemAuthentication && fixtureNamespace == null) {
            // Invalidate the old unbound CLIENT blob only in the application's namespace.
            check(context.applicationContext.getSharedPreferences("phonoteka_lab13_refresh_store", Context.MODE_PRIVATE)
                .edit().remove("encrypted_refresh_token").commit())
        }
    }
    private val prefs = context.applicationContext.getSharedPreferences(
        when {
            fixtureNamespace != null -> "$PREFS.fixture.$fixtureNamespace"
            requireSystemAuthentication -> PREFS
            else -> "$PREFS.instrumentation-fixture"
        }, Context.MODE_PRIVATE)
    private val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager

    @Synchronized
    override fun read(): String? {
        val encoded = prefs.getString(TOKEN_KEY, null) ?: return null
        if (requireSystemAuthentication && !keyguard.isDeviceSecure) {
            // Блокировка экрана снята: auth-bound ключи система уже инвалидировала.
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias()) }
            clear(); keyLoss.record(KeyStoreFailure.KEY_INVALIDATED, "refresh-store")
            return null
        }
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES)
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (failure: Exception) {
            // ЛР13: раньше любая ошибка молча стирала токен. Теперь стираем только невосстановимое.
            val kind = KeyLossPolicy.classify(failure)
            when (KeyLossPolicy.action(kind)) {
                KeyRecoveryAction.KEEP_AND_AUTHENTICATE, KeyRecoveryAction.KEEP_AND_RETRY -> throw failure
                KeyRecoveryAction.DELETE_KEY_AND_BLOB_RELOGIN -> {
                    runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias()) }
                    clear(); keyLoss.record(kind, "refresh-store"); null
                }
                KeyRecoveryAction.DELETE_BLOB_RELOGIN -> { clear(); keyLoss.record(kind, "refresh-store"); null }
            }
        }
    }

    @Synchronized
    override fun prepareWrite() {
        Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    }

    @Synchronized
    override fun write(refreshToken: String) {
        require(refreshToken.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val payload = cipher.iv + cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(TOKEN_KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
    }

    @Synchronized
    override fun clear() {
        check(prefs.edit().remove(TOKEN_KEY).commit())
    }

    private fun getOrCreateKey(): SecretKey {
        if (requireSystemAuthentication && !keyguard.isDeviceSecure) {
            throw SecurityException("Secure Android lockscreen is required")
        }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias(), null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias(), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(requireSystemAuthentication)
        if (requireSystemAuthentication) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                spec.setUserAuthenticationParameters(300,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(300)
            }
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    private fun alias(): String = when {
        fixtureNamespace != null -> "$KEY_ALIAS.fixture.$fixtureNamespace"
        requireSystemAuthentication -> KEY_ALIAS
        else -> "$KEY_ALIAS.instrumentation-fixture"
    }
    companion object {
        private const val PREFS = "phonoteka_lab13_refresh_store_v2"
        private const val TOKEN_KEY = "encrypted_refresh_token"
        private const val KEY_ALIAS = "phonoteka.lab13.refresh.aes.auth-bound.v2"
        private const val IV_BYTES = 12
        private val AAD = "phonoteka-lab13-refresh-v1".toByteArray(Charsets.UTF_8)
    }
}

/** Safe UI-facing failure: the old rotated token is not retried; a new login is required. */
class MockSessionPersistenceException(cause: Exception) :
    IllegalStateException("Local session persistence failed; sign in again", cause)

/** Client lifecycle policy: refresh on expiry and on a new process; never persist access tokens. */
class MockSessionRepository(
    private val endpoint: MockAuthEndpoint,
    private val refreshStore: RefreshTokenStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val invalidateBiometric: () -> Unit = {}
) {
    private var current: MockTokenPair? = null
    private var revision = 0L
    @Synchronized fun sessionRevision(): Long = revision
    @Synchronized fun forgetAccessSession() { current = null; revision = Math.addExact(revision, 1) }
    @Synchronized fun refreshForBiometricEnrollment(): String {
        val live = current ?: error("Mock login or session restoration required")
        check(live.refreshExpiresAtMillis > nowMillis()) { "Refresh expired" }
        return live.refreshToken
    }
    @Synchronized fun withUnchangedSession(expected: Long, action: () -> Unit) {
        check(revision == expected && current != null) { "Session changed" }; action()
    }
    @Synchronized fun restoreFromBiometric(refresh: String, expectedRevision: Long): String {
        check(revision == expectedRevision) { "Session changed" }
        require(refresh.matches(Regex("[a-f0-9]{64}")))
        current = null; revision = Math.addExact(revision, 1)
        // May still require system fallback on some devices. Do NOT consume endpoint token first.
        refreshStore.prepareWrite()
        val persisted = readStoredRefresh() ?: error("Credential store no longer has this session")
        check(MessageDigest.isEqual(persisted.toByteArray(Charsets.US_ASCII), refresh.toByteArray(Charsets.US_ASCII))) {
            "Biometric and credential session differ"
        }
        invalidateBiometric()
        val rotated = try { endpoint.refresh(refresh) } catch (failure: Exception) { failClosed(listOf(refresh), failure) }
        if (rotated == null) { refreshStore.clear(); error("Mock session expired or revoked") }
        try { refreshStore.write(rotated.refreshToken) }
        catch (failure: Exception) { failClosed(listOf(refresh, rotated.refreshToken), failure) }
        current = rotated
        return rotated.accessToken
    }

    @Synchronized
    fun login(username: String, password: String): MockLoginResult {
        val previousRefresh = current?.refreshToken ?: readStoredRefresh()
        refreshStore.prepareWrite()
        val result = endpoint.login(username, password)
        if (result is MockLoginResult.Success) {
            try {
                invalidateBiometric()
                refreshStore.write(result.tokens.refreshToken)
                current = result.tokens
                revision = Math.addExact(revision, 1)
                if (!previousRefresh.isNullOrBlank() && previousRefresh != result.tokens.refreshToken) endpoint.revoke(previousRefresh)
            } catch (error: Exception) {
                failClosed(listOfNotNull(result.tokens.refreshToken, previousRefresh), error)
            }
        }
        return result
    }

    @Synchronized
    fun accessToken(): String? {
        val cached = current
        if (cached != null && cached.accessExpiresAtMillis - nowMillis() > REFRESH_EARLY_MILLIS) {
            return cached.accessToken
        }
        val refresh = cached?.refreshToken ?: readStoredRefresh()
        if (refresh.isNullOrBlank()) return null
        refreshStore.prepareWrite() // Locked key must not consume an otherwise valid server refresh.
        invalidateBiometric()
        revision = Math.addExact(revision, 1)
        val rotated = try {
            endpoint.refresh(refresh)
        } catch (error: Exception) {
            failClosed(listOf(refresh), error)
        }
        if (rotated == null) {
            current = null
            refreshStore.clear()
            return null
        }
        // The old refresh was consumed by the endpoint. Do not keep a stale in-memory session.
        current = null
        try {
            refreshStore.write(rotated.refreshToken)
        } catch (error: Exception) {
            // Handles both write-before-throw and throw-before-write stores.
            failClosed(listOf(refresh, rotated.refreshToken), error)
        }
        current = rotated
        return rotated.accessToken
    }

    @Synchronized
    fun logout() {
        var failure: Exception? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        var refresh = current?.refreshToken
        current = null; revision = Math.addExact(revision, 1)
        if (refresh == null) cleanup { refresh = readStoredRefresh() }
        // Every cleanup is attempted even if blob invalidation/read/revocation fails.
        cleanup { invalidateBiometric() }
        cleanup { refresh?.takeIf { it.isNotBlank() }?.let(endpoint::revoke) }
        cleanup { refreshStore.clear() }
        failure?.let { throw MockSessionPersistenceException(it) }
    }

    /**
     * ЛР13: стор пробрасывает LOCKED/TRANSIENT, не удаляя токен. Здесь сырая ошибка Keystore
     * превращается в KeyLostException с понятным сообщением; серверный refresh не расходуется,
     * сохранённая сессия не стирается (можно повторить после разблокировки).
     */
    private fun readStoredRefresh(): String? = try {
        refreshStore.read()
    } catch (failure: Exception) {
        if (failure is KeyLostException) throw failure
        val kind = KeyLossPolicy.classify(failure)
        if (kind == KeyStoreFailure.LOCKED || kind == KeyStoreFailure.TRANSIENT) throw KeyLostException(kind).apply { initCause(failure) }
        throw failure
    }

    private fun failClosed(tokens: List<String>, error: Exception): Nothing {
        current = null
        revision = Math.addExact(revision, 1)
        try { invalidateBiometric() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        tokens.distinct().forEach { token ->
            try { endpoint.revoke(token) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        }
        try { refreshStore.clear() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        throw MockSessionPersistenceException(error)
    }

    companion object { private const val REFRESH_EARLY_MILLIS = 15_000L }
}
