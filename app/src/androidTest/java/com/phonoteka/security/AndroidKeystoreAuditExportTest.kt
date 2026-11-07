package com.phonoteka.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreAuditExportTest {
    @Test fun signedAuditExportVerifiesAndDetectsEditedEntries() {
        val exporter = AndroidKeystoreAuditExport()
        val document = exporter.encode(listOf("100|ADMIN|library.read|allowed", "101|ADMIN|audit.read|allowed"))
        assertTrue(exporter.verify(document))
        val tampered = String(document, Charsets.UTF_8).replace("library.read", "library.write").toByteArray()
        assertFalse(exporter.verify(tampered))
        assertFalse(exporter.verify("{}".toByteArray()))
    }
}
