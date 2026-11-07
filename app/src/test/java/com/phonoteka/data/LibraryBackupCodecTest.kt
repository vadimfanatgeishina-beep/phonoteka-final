package com.phonoteka.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryBackupCodecTest {
    @Test fun emptyLibraryRoundTripsWithVersion() {
        val decoded = LibraryBackupCodec.decode(LibraryBackupCodec.encode(emptyList(), emptyList()))
        assertEquals(1, decoded.schemaVersion)
        assertEquals(0, decoded.tracks.size)
        assertEquals(0, decoded.candidates.size)
    }

    @Test fun rejectsUnsupportedVersionAndInvalidJson() {
        assertThrows(java.io.IOException::class.java) { LibraryBackupCodec.decode("{bad") }
        assertThrows(java.io.IOException::class.java) {
            LibraryBackupCodec.decode("{\"schemaVersion\":99,\"tracks\":[],\"candidates\":[]}")
        }
    }
}
