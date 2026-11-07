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
import com.phonoteka.data.notes.*
import kotlinx.coroutines.flow.first
import java.io.File
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Application class — инициализирует базу данных и репозиторий.
 * Соответствует Лабе 4 (Application + DI вручную).
 */
class PhonotekaApp : Application() {

    val database: PhonotekaDatabase by lazy { PhonotekaDatabase.get(this) }
    val localAuth by lazy { com.phonoteka.security.LocalAuthManager(this) }
    val riskStepUp by lazy { com.phonoteka.security.RiskStepUpController(android.os.SystemClock::elapsedRealtime) }
    @Volatile private var elevatedAfterBackground = false
    fun markRiskyResume() { elevatedAfterBackground = true; riskStepUp.invalidate() }
    /** ЛР14: обязательное подтверждение BIOMETRIC_STRONG для чтения и экспорта журнала. */
    val auditStepUp by lazy { com.phonoteka.security.AndroidAuditBiometricStepUp(com.phonoteka.security.BiometricStepUpGate(android.os.SystemClock::elapsedRealtime)) }
    fun clearStepUpSession() { riskStepUp.invalidate(); auditStepUp.gate.invalidate(); elevatedAfterBackground = false }
    /** ЛР15: блокировка/выход закрывают живой сокет, привязанный к прежнему пользователю. */
    val principalChanges = com.phonoteka.security.PrincipalChangeSignal()
    fun invalidateProtectedSession() { clearStepUpSession(); inviteAuthority.revokeAll(); principalChanges.signal() }
    fun requireAuthority(scope: com.phonoteka.security.DemoScope, resource: String) {
        if (!localAuth.isFreshAuthentication()) {
            accessAudit.recordUnauthenticated("$resource.authentication")
            throw SecurityException("Fresh local authentication required")
        }
        val actor = roleSession.requireAccess(scope, "$resource.role", accessAudit)
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
        accessAudit.record(actor, resource, true)
    }
    val roleSession by lazy { com.phonoteka.security.DemoRoleSession(this) }
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
        // Импорт musik.txt выполнится в TrackListViewModel.init
    }
}
