package com.phonoteka

import android.app.Application
import com.phonoteka.data.PhonotekaDatabase
import com.phonoteka.data.PhonotekaRepository
import com.phonoteka.data.RoomPostCatalogRepository
import com.phonoteka.network.PostCatalogApi
import com.phonoteka.network.createLab10HttpClient
import com.phonoteka.security.AndroidKeystoreRefreshTokenStore
import com.phonoteka.security.LocalMockAuthEndpoint
import com.phonoteka.security.MockSessionRepository
import com.phonoteka.security.PreferencesMockAuthStateStore
import com.phonoteka.security.SecurityDecisions
import com.phonoteka.data.notes.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Application class — инициализирует базу данных и репозиторий.
 * Соответствует Лабе 4 (Application + DI вручную).
 */
class PhonotekaApp : Application() {

    val database: PhonotekaDatabase by lazy { PhonotekaDatabase.get(this) }
    val localAuth by lazy { com.phonoteka.security.LocalAuthManager(this, com.phonoteka.security.MockRecoveryOutbox.shared, unifiedAudit) }
    val totpAuthenticator by lazy { com.phonoteka.security.TotpAuthenticatorManager(this, localAuth) }
    val riskStepUp by lazy { com.phonoteka.security.RiskStepUpController(android.os.SystemClock::elapsedRealtime) }
    @Volatile private var elevatedAfterBackground = false
    fun markRiskyResume() { elevatedAfterBackground = true; riskStepUp.invalidate() }
    /** ЛР14: обязательное подтверждение BIOMETRIC_STRONG для чтения и экспорта журнала. */
    val auditStepUp by lazy { com.phonoteka.security.AndroidAuditBiometricStepUp(com.phonoteka.security.BiometricStepUpGate(android.os.SystemClock::elapsedRealtime)) }
    fun clearStepUpSession() { riskStepUp.invalidate(); auditStepUp.gate.invalidate(); elevatedAfterBackground = false }
    /** ЛР15: блокировка/выход закрывают живой сокет, привязанный к прежнему пользователю. */
    val principalChanges = com.phonoteka.security.PrincipalChangeSignal()
    fun invalidateProtectedSession() {
        clearStepUpSession(); inviteAuthority.revokeAll(); principalChanges.signal()
        if (phoneAccountsLazy.isInitialized()) phoneAccounts.invalidate()
    }
    private val identityScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val phoneAccountsLazy = lazy {
        com.phonoteka.identity.PhoneAccountsController(this,
            requireLocalOwner = {
                if (!localAuth.isSessionActive() || roleSession.activeQuickAccess != null)
                    throw SecurityException("Local owner PIN/biometric session required")
            },
            principalChanged = { clearStepUpSession(); principalChanges.signal() },
            audit = { event ->
                val denied = event.type == com.phonoteka.identity.IdentityEventType.LOGIN_DENIED ||
                    event.type == com.phonoteka.identity.IdentityEventType.REFRESH_REPLAY
                val type = when (event.type) {
                    com.phonoteka.identity.IdentityEventType.LOGIN_SUCCESS -> com.phonoteka.security.AuditEventType.LOGIN
                    com.phonoteka.identity.IdentityEventType.LOGIN_DENIED -> com.phonoteka.security.AuditEventType.LOGIN_FAILED
                    com.phonoteka.identity.IdentityEventType.LOGOUT -> com.phonoteka.security.AuditEventType.LOGOUT
                    else -> com.phonoteka.security.AuditEventType.SESSION
                }
                unifiedAudit.record(type = type,
                    source = com.phonoteka.security.AuditSource.AUTH,
                    userId = event.actorId?.let { "server:$it" } ?: "unverified", role = "SERVER",
                    action = "identity.${event.type.name.lowercase()}", subject = event.subjectId ?: "account",
                    result = if (denied) "DENIED" else "OK")
            }).also { accounts ->
                identityScope.launch {
                    localAuth.authenticationSnapshotFlow.collect { if (it.authenticatedAt == null) accounts.invalidate() }
                }
            }
    }
    val phoneAccounts get() = phoneAccountsLazy.value

    /**
     * Единый журнал событий ЛР13/14/15/16.
     * Пишутся в него входы и отказы (ЛР13), QR и решения доступа (ЛР14),
     * сетевые события и WSS (ЛР15), результаты проверок hardening (ЛР16).
     */
    val unifiedAudit by lazy { com.phonoteka.security.UnifiedAuditLog(this) }

    /** Идентификатор текущей сессии пользователя — общий для всех источников событий. */
    fun currentSessionId(): String {
        val existing = unifiedAudit.sessionId
        if (existing != null) return existing
        val fresh = "sess-" + java.util.UUID.randomUUID().toString().take(8)
        unifiedAudit.sessionId = fresh
        return fresh
    }

    fun auditEvent(
        type: com.phonoteka.security.AuditEventType,
        source: com.phonoteka.security.AuditSource,
        action: String,
        obj: String,
        result: String,
        reason: String? = null,
        includeIp: Boolean = false
    ) {
        val actor = runCatching { roleSession.authority.auditActor(roleSession.session) }.getOrNull()
        unifiedAudit.record(
            type = type,
            source = source,
            userId = actor?.id ?: "unverified",
            role = actor?.role ?: "UNVERIFIED",
            action = action,
            subject = obj,
            result = result,
            reason = reason,
            includeIp = includeIp
        )
    }

    fun requireAuthority(scope: com.phonoteka.security.DemoScope, resource: String) {
        // ЛР13/ЛР15: в уязвимой сборке эти проверки ослаблены намеренно (см. SecurityDecisions),
        // поэтому различие между сборками видно в ОДНОМ и том же сценарии.
        if (SecurityDecisions.requireFreshAuthForLibrary() && !localAuth.isFreshAuthentication()) {
            accessAudit.recordUnauthenticated("$resource.authentication")
            auditEvent(com.phonoteka.security.AuditEventType.ACCESS_DENIED, com.phonoteka.security.AuditSource.AUTH,
                resource, "authentication", "DENIED", reason = "Нет свежей аутентификации")
            throw SecurityException("Fresh local authentication required")
        }
        val actor = roleSession.requireAccess(scope, "$resource.role", accessAudit)
        if (SecurityDecisions.requireStepUp()) {
            com.phonoteka.security.BiometricStepUpOperation.fromResource(resource)?.let { operation ->
                try { auditStepUp.gate.consume(operation, localAuth.authenticationSnapshot().version) }
                catch (denied: SecurityException) { accessAudit.record(actor, "$resource.biometric", false); throw denied }
            }
            val risk = com.phonoteka.security.StepUpRisk(android.os.Debug.isDebuggerConnected(), elevatedAfterBackground)
            try {
                riskStepUp.require(com.phonoteka.security.ProtectedOperation.fromResource(resource), localAuth.authenticationSnapshot(), risk)
            } catch (denied: SecurityException) {
                accessAudit.record(actor, "$resource.stepup", false)
                throw denied
            }
        }
        accessAudit.record(actor, resource, true)
        auditEvent(com.phonoteka.security.AuditEventType.ACCESS_ALLOWED, com.phonoteka.security.AuditSource.AUDIT,
            resource, scope.wire, "ALLOWED")
    }
    val roleSession by lazy { com.phonoteka.security.DemoRoleSession(this) }
    val quickAccessServer by lazy {
        com.phonoteka.security.LocalQrAccessServer(this,
            onRedeemed = { role, actorId ->
                runCatching { auditEvent(com.phonoteka.security.AuditEventType.ACCESS_ALLOWED,
                    com.phonoteka.security.AuditSource.QR, "qr2.redeemed", actorId, "ALLOWED",
                    reason = "role:${role.name}", includeIp = true) }
            },
            onDenied = { reason ->
                runCatching { auditEvent(com.phonoteka.security.AuditEventType.ACCESS_DENIED,
                    com.phonoteka.security.AuditSource.QR, "qr2.rejected", "ticket", "DENIED",
                    reason = reason, includeIp = true) }
            })
    }
    val accessAudit by lazy { com.phonoteka.security.AccessAuditRepository(this, roleSession.authority,
        readGate = { requireAuthority(com.phonoteka.security.DemoScope.AUDIT_READ, "audit.read") },
        exportGate = { requireAuthority(com.phonoteka.security.DemoScope.AUDIT_READ, "audit.export") }) }
    val repository: PhonotekaRepository by lazy {
        PhonotekaRepository(database.trackDao(), this) { scope, resource -> requireAuthority(scope, resource) }
    }
    val keyLossJournal: com.phonoteka.security.KeyLossJournal by lazy { com.phonoteka.security.AndroidKeyLossJournal(this) }
    val biometricRefresh by lazy { com.phonoteka.security.AndroidBiometricRefreshVault(this, keyLoss = keyLossJournal) }
    val lab13MockSession by lazy {
        MockSessionRepository(
            endpoint = LocalMockAuthEndpoint(PreferencesMockAuthStateStore(this)),
            refreshStore = AndroidKeystoreRefreshTokenStore(this, keyLoss = keyLossJournal),
            invalidateBiometric = { biometricRefresh.clear() }
        )
    }
    val inviteAuthority by lazy {
        com.phonoteka.security.MockInviteAuthority(roleSession.authority, com.phonoteka.security.AndroidInviteMac(), android.os.SystemClock::elapsedRealtime)
    }
    val trackInvites by lazy {
        com.phonoteka.data.AuthorizedTrackInvites(database.trackDao(), inviteAuthority, { roleSession.session }, ::requireAuthority,
            { resource, allowed, subject -> accessAudit.record(roleSession.session, resource, allowed, subject) })
    }
    val trackNotes by lazy {
        val root = File(filesDir, "track-notes")
        val drafts = File(root, "drafts") // filesDir: not an evictable Android cache.
        val legacyDrafts = File(cacheDir, "track-note-drafts")
        val raw = FileTrackNotesRepository(root, drafts, KeystoreNoteCrypto(hasExistingData = {
            File(root, "index.json").exists() || File(root, "index.json.bak").exists() ||
                drafts.listFiles()?.any { it.name.endsWith(".enc") } == true ||
                legacyDrafts.listFiles()?.any { it.name.endsWith(".enc") } == true
        }), AndroidAtomicNoteIndex(File(root, "index.json")),
            { id -> database.trackDao().getTrackById(id).first() != null }, legacyDraftDirectory = legacyDrafts)
        com.phonoteka.data.notes.AuthorizedTrackNotes(raw) { scope, resource -> requireAuthority(scope, resource) }
    }
    val catalogRepository by lazy {
        val api = Retrofit.Builder().baseUrl("https://jsonplaceholder.typicode.com/")
            .client(createLab10HttpClient())
            .addConverterFactory(GsonConverterFactory.create()).build().create(PostCatalogApi::class.java)
        RoomPostCatalogRepository(api, database.catalogPostDao())
    }

    override fun onCreate() {
        super.onCreate()
        com.phonoteka.security.SecurityProfile.initialize(this)
        // Импорт musik.txt выполнится в TrackListViewModel.init
    }
}
