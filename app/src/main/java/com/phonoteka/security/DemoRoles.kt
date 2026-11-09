package com.phonoteka.security

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class QuickAccessGrantInfo(val role: DemoRole, val actorId: String, val expiresAtElapsed: Long)

class DemoRoleSession(context: Context, val authority: MockRoleAuthority = MockRoleAuthority()) {
    private val prefs = context.getSharedPreferences("phonoteka_demo_role", Context.MODE_PRIVATE)
    @Volatile private var selected = DemoRole.EMPLOYEE
    /**
     * Учебная идентичность пользователя. Меняется при смене роли или при явной
     * смене пользователя — это делает смену пользователя НАБЛЮДАЕМОЙ:
     * идентификатор попадает в канал WSS и в журнал, поэтому смена видна.
     */
    @Volatile private var actorId: String = DEFAULT_ACTOR
    private var quickAccessExpiresAtElapsed = 0L
    @Volatile var onQuickAccessExpired: (() -> Unit)? = null
    private var issued = authority.issue(selected, actorId)
    private val mutableRole = kotlinx.coroutines.flow.MutableStateFlow(selected)
    private val mutableQuickAccess = MutableStateFlow<QuickAccessGrantInfo?>(null)
    val changes: kotlinx.coroutines.flow.StateFlow<DemoRole> = mutableRole
    val quickAccess: StateFlow<QuickAccessGrantInfo?> = mutableQuickAccess
    val role: DemoRole get() = selected
    val currentActorId: String get() = actorId
    @get:Synchronized val activeQuickAccess: QuickAccessGrantInfo? get() {
        session // Enforce expiry before returning the visible grant.
        return mutableQuickAccess.value
    }
    @get:Synchronized val session: DemoSession get() {
        if (quickAccessExpiresAtElapsed > 0L && SystemClock.elapsedRealtime() >= quickAccessExpiresAtElapsed) {
            resetToEmployee()
            onQuickAccessExpired?.invoke()
        } else if (!authority.valid(issued)) {
            if (quickAccessExpiresAtElapsed > 0L) {
                resetToEmployee()
                onQuickAccessExpired?.invoke()
            } else issued = authority.issue(selected, actorId)
        }
        return issued
    }
    @Synchronized fun select(role: DemoRole) {
        check(com.phonoteka.BuildConfig.DEBUG) { "Role selection is debug-only" }
        quickAccessExpiresAtElapsed = 0L; mutableQuickAccess.value = null
        selected = role; actorId = actorFor(role); issued = authority.issue(role, actorId); mutableRole.value = role
    }
    /**
     * ЛР15: явная смена пользователя. Новый пользователь получает новую идентичность,
     * поэтому привязанное к прежнему пользователю соединение обязано быть закрыто.
     */
    @Synchronized fun switchUser(nextActorId: String): Boolean {
        check(com.phonoteka.BuildConfig.DEBUG) { "User switching is debug-only" }
        if (!nextActorId.matches(Regex("[A-Za-z0-9:._-]{1,64}"))) return false
        if (nextActorId == actorId) return false
        quickAccessExpiresAtElapsed = 0L; mutableQuickAccess.value = null
        actorId = nextActorId
        issued = authority.issue(selected, actorId)
        return true
    }
    @Synchronized fun resetToEmployee() {
        quickAccessExpiresAtElapsed = 0L; mutableQuickAccess.value = null
        selected = DemoRole.EMPLOYEE; actorId = DEFAULT_ACTOR; issued = authority.issue(selected, actorId); mutableRole.value = selected
    }
    @Synchronized fun acceptQuickAccess(role: DemoRole, recipientActorId: String, ttlMillis: Long) {
        require(ttlMillis in 1..(30 * 60_000L))
        require(recipientActorId.matches(Regex("qr:[A-Za-z0-9-]{36}")))
        selected = role; actorId = recipientActorId
        quickAccessExpiresAtElapsed = SystemClock.elapsedRealtime() + ttlMillis
        issued = authority.issue(role, actorId, ttlMillis)
        mutableRole.value = role
        mutableQuickAccess.value = QuickAccessGrantInfo(role, actorId, quickAccessExpiresAtElapsed)
    }
    fun canAccess(scope: DemoScope) = authority.authorize(session, scope)
    fun recordPermissionPrompted() { prefs.edit().putBoolean("notifications_asked", true).apply() }
    fun hasPromptedNotifications() = prefs.getBoolean("notifications_asked", false)
    fun requireAccess(scope: DemoScope, resource: String, audit: AccessAuditRepository): DemoSession {
        val actor = session
        val granted = authority.authorize(actor, scope)
        audit.record(actor, resource, granted)
        if (!granted) throw SecurityException("Недостаточно прав для операции")
        return actor
    }

    companion object {
        const val DEFAULT_ACTOR = "mock:student"
        /** Учебные идентичности пользователей стенда. Никаких реальных учётных записей. */
        val FIXTURE_ACTORS = listOf("mock:student", "mock:employee-2", "mock:security-3")
        fun actorFor(role: DemoRole): String = when (role) {
            DemoRole.EMPLOYEE -> "mock:student"
            DemoRole.SECURITY -> "mock:security-3"
            DemoRole.ADMIN -> "mock:employee-2"
        }
    }
}

/** Mock server decision is deliberately independent from the UI's visibility check. */
class DemoAccessService(private val audit: AccessAuditRepository, private val authority: MockRoleAuthority) {
    fun authorize(session: DemoSession, scope: DemoScope, resource: String): Boolean {
        val allowed = authority.authorize(session, scope)
        audit.record(session, resource, allowed)
        return allowed
    }
}

class AccessAuditRepository(context: Context, private val authority: MockRoleAuthority,
    private val readGate: () -> Unit = {}, private val exportGate: () -> Unit = {},
    fixtureNamespace: String? = null
) {
    init { require(fixtureNamespace == null || fixtureNamespace.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) }
    private val app = context.applicationContext
    private val root = if (fixtureNamespace == null) app.filesDir else java.io.File(app.filesDir, "audit.fixture.$fixtureNamespace")
    private val files = AndroidAuditJournalFiles(root)
    private val journal = SignedAuditJournal(files, AndroidAuditChainMac(files::hasData, fixtureNamespace))
    private val signer = AndroidKeystoreAuditExport(fixtureNamespace)
    private var legacyChecked = false
    @Synchronized private fun ensureLegacyEvidence() {
        if (legacyChecked) return
        val current = journal.read()
        val legacy = if (fixtureNamespaceAbsent()) app.getSharedPreferences("phonoteka_access_audit", Context.MODE_PRIVATE)
            .getString("authenticated_events_v2", null) else null
        if (current.isEmpty() && legacy != null && fixtureNamespaceAbsent()) {
            val bytes = legacy.toByteArray(Charsets.UTF_8)
            check(signer.verify(bytes)) { "Legacy audit integrity failed; no reset" }
            val archive = android.util.AtomicFile(java.io.File(root, "audit.legacy.snapshot.json"))
            if (archive.baseFile.exists()) {
                val existing = archive.openRead().use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val n = input.read(buffer); if (n < 0) break
                        check(output.size() + n <= 8 * 1024 * 1024) { "Legacy archive too large" }; output.write(buffer, 0, n) }
                    output.toByteArray()
                }
                check(java.security.MessageDigest.isEqual(existing, bytes)) { "Legacy archive mismatch" }
            } else {
                var output: java.io.FileOutputStream? = null
                try { output = archive.startWrite(); output.write(bytes); output.fd.sync(); archive.finishWrite(output); output = null }
                catch (error: Exception) { output?.let { archive.failWrite(it) }; throw error }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            journal.append(AuditDecision(System.currentTimeMillis(), AuditActor("system", "SYSTEM"), "audit.legacy.snapshot", "sha256:$digest", true))
        }
        // Old prefs/unsigned events are NOT removed or relabelled as complete trusted JSONL history.
        legacyChecked = true
    }
    private fun fixtureNamespaceAbsent() = root == app.filesDir
    @Synchronized fun record(actor: DemoSession, resource: String, allowed: Boolean, subject: String = AuditSubjects.collectionFor(resource)) {
        ensureLegacyEvidence()
        journal.append(AuditDecision(System.currentTimeMillis(), authority.auditActor(actor), resource, subject, allowed))
    }
    @Synchronized fun recordUnauthenticated(resource: String) {
        ensureLegacyEvidence()
        journal.append(AuditDecision(System.currentTimeMillis(), AuditActor("unverified", "UNVERIFIED"), resource, AuditSubjects.collectionFor(resource), false))
    }
    private fun accessPolicy() = AuditAccessGate(authority,
        { actor, action, allowed, subject -> record(actor, action, allowed, subject) }, readGate, exportGate)
    @Synchronized fun readAll(session: DemoSession): List<String> = accessPolicy().withAccess(session) {
        journal.read().map { row ->
            val d = row.decision
            "#${row.sequence} | ${d.timestamp} | ${d.actor.id}/${d.actor.role} | ${d.action} | ${d.subject} | ${if (d.allowed) "ALLOWED" else "DENIED"}"
        }
    }
    @Synchronized fun writeSignedExport(session: DemoSession, sink: (ByteArray) -> Unit) = accessPolicy().withAccess(session, true) {
        sink(signer.encode(journal.jsonLines())) // SAF writing stays inside the verified grant transaction.
    }
    @Synchronized fun signedExport(session: DemoSession): ByteArray = accessPolicy().withAccess(session, true) {
        // Exact complete canonical JSONL rows, NOT the formatted display or last200 subset.
        signer.encode(journal.jsonLines())
    }
}
