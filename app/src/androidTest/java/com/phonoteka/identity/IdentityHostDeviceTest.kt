package com.phonoteka.identity

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

/** Actual Android KeyStore + TLS + HTTP router + core. All accounts are isolated fixtures. */
class IdentityHostDeviceTest {
    @Test fun androidHostAuthenticatesPersistsAndRejectsWrongPin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "tls-test-${UUID.randomUUID()}"
        val alias = "phonoteka.identity.$name.aes.v1"
        val ownerAuthenticated = java.util.concurrent.atomic.AtomicBoolean(true)
        val gate = { check(ownerAuthenticated.get()) { "Local owner authentication required" } }
        lateinit var token: String
        try {
            AndroidIdentityHost.open(context, gate, name).use { host ->
                assertTrue(host.needsOwner)
                assertTrue(runCatching { host.start("127.0.0.1") }.isFailure)
                ownerAuthenticated.set(false)
                assertTrue(runCatching { host.provisionOwner("owner", "owner-device-test-password") }.isFailure)
                ownerAuthenticated.set(true)
                host.provisionOwner("owner", "owner-device-test-password")
                ownerAuthenticated.set(false)
                assertTrue(runCatching { host.start("127.0.0.1") }.isFailure)
                ownerAuthenticated.set(true)
                val endpoint = host.start("127.0.0.1")
                run {
                    val client = endpoint.client()
                    try {
                        val payload = """{"login":"owner","password":"owner-device-test-password"}"""
                        val request = Request.Builder().url(endpoint.baseUrl + "/auth/login")
                            .post(payload.toRequestBody("application/json".toMediaType())).build()
                        client.newCall(request).execute().use {
                            assertEquals(200, it.code)
                            token = JSONObject(it.body!!.string()).getString("access_token")
                        }
                        client.newCall(Request.Builder().url(endpoint.baseUrl + "/me")
                            .header("Authorization", "Bearer $token").build()).execute().use {
                            assertEquals(200, it.code); assertEquals("ADMIN", JSONObject(it.body!!.string()).getString("role"))
                        }
                        ownerAuthenticated.set(false)
                        client.newCall(Request.Builder().url(endpoint.baseUrl + "/me")
                            .header("Authorization", "Bearer $token").build()).execute().use { assertEquals(503, it.code) }
                        ownerAuthenticated.set(true)
                    } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
                    PhoneIdentityClient(endpoint).use { account ->
                        assertEquals(IdentityRole.ADMIN, account.login("owner", "owner-device-test-password", "Android API client").role)
                        assertTrue(account.sessions().any { it.current })
                        account.createUser("employee", "employee-device-test-password", IdentityRole.EMPLOYEE)
                        PhoneIdentityClient(endpoint).use { employee ->
                            assertEquals(IdentityRole.EMPLOYEE, employee.login("employee", "employee-device-test-password", "Employee client").role)
                            try {
                                employee.createUser("intruder", "intruder-test-password", IdentityRole.ADMIN)
                                fail("Server must reject employee admin operation")
                            } catch (failure: IdentityClientException) { assertEquals(403, failure.status) }
                            employee.logout()
                            assertTrue(runCatching { employee.currentUser() }.isFailure)
                        }
                        account.logout()
                    }
                    val wrong = endpoint.copy(pin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32))).client()
                    try {
                        assertTrue(runCatching { wrong.newCall(Request.Builder().url(endpoint.baseUrl + "/me").build()).execute().close() }.isFailure)
                    } finally { wrong.dispatcher.executorService.shutdown(); wrong.connectionPool.evictAll() }
                }
            }
            AndroidIdentityHost.open(context, gate, name).use { host ->
                assertFalse(host.needsOwner)
                val endpoint = host.start("127.0.0.1")
                run {
                    val client = endpoint.client()
                    try {
                        client.newCall(Request.Builder().url(endpoint.baseUrl + "/sessions")
                            .header("Authorization", "Bearer $token").build()).execute().use {
                            assertEquals(200, it.code)
                            assertEquals(1, JSONObject(it.body!!.string()).getJSONArray("sessions").length())
                        }
                    } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
                }
            }
        } finally {
            File(context.noBackupFilesDir, "identity/$name").deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
