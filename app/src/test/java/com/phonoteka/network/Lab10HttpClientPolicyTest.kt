package com.phonoteka.network
import org.junit.Assert.assertEquals
import org.junit.Test
class Lab10HttpClientPolicyTest {
    @Test fun catalogTimeoutsAreTenAndThirtySeconds() {
        val client = createLab10HttpClient()
        assertEquals(10_000, client.connectTimeoutMillis); assertEquals(30_000, client.readTimeoutMillis)
    }
}
