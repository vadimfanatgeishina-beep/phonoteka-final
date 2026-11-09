package com.phonoteka.security

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AuditClearDeviceTest {
    private fun withLog(block: (UnifiedAuditLog, File) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "audit-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        try { block(UnifiedAuditLog(context), File(root, "audit/unified.jsonl")) }
        finally { root.deleteRecursively() }
    }

    @Test fun clearingViewPreservesOriginalBytesAndFullHistory() = withLog { log, file ->
        assertTrue(log.record(AuditEvent(AuditEventType.LOGIN, AuditSource.AUTH, 1,
            "student", "USER", "login", "session", "OK")))
        val original = file.readBytes()
        assertEquals(1, log.clearHistory())
        assertTrue(file.readBytes().copyOfRange(0, original.size).contentEquals(original))
        assertEquals(2, log.readVerified()!!.size)
        assertEquals("clear_view", log.recent().single().action)
        assertTrue(log.isIntegrityIntact())
    }

    @Test fun damagedHistoryCannotBeClearedOrHidden() = withLog { log, file ->
        file.parentFile!!.mkdirs(); file.writeText("corrupted\n")
        assertTrue(runCatching { log.clearHistory() }.isFailure)
        assertEquals("corrupted\n", file.readText())
        assertFalse(log.isIntegrityIntact())
    }
}
