package com.phonoteka.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.phonoteka.data.PhonotekaDatabase
import com.phonoteka.data.PhonotekaRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccessBoundaryTest {
    @Test fun repositoryRejectsDirectMutationBeforeWritingRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = PhonotekaDatabase.getInMemory(context)
        val repo = PhonotekaRepository(database.trackDao(), context) { _, _ -> throw SecurityException("denied") }
        try {
            var denied = false
            try { repo.addTrack("fixture", "forbidden") } catch (_: SecurityException) { denied = true }
            assertTrue(denied); assertEquals(0, database.trackDao().countRows())
        } finally { database.close() }
    }
    @Test fun forgedAdminScopeAndTamperedStoredAuditAreRejected() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val authority = MockRoleAuthority()
        val audit = AccessAuditRepository(base, authority, fixtureNamespace = suffix)
        val employee = authority.issue(DemoRole.EMPLOYEE)
        var denied = false
        try { audit.readAll(employee.copy(role = DemoRole.ADMIN, scopes = DemoRolePolicy.scopes(DemoRole.ADMIN))) }
        catch (_: SecurityException) { denied = true }
        assertTrue(denied)
        val admin = authority.issue(DemoRole.ADMIN)
        audit.record(admin, "fixture", true)
        assertTrue(audit.readAll(admin).isNotEmpty())
        val directory = java.io.File(base.filesDir, "audit.fixture.$suffix")
        val file = java.io.File(directory, "audit.log")
        file.writeText(file.readText().replace("fixture", "edited"))
        var rejected = false
        try { audit.readAll(admin) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        directory.deleteRecursively()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("phonoteka.lab14.audit-chain.hmac.v1.fixture.$suffix")
    }
}
