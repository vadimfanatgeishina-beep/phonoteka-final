package com.phonoteka.identity

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.CertificatePinner
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import javax.net.ssl.SSLSocket

class IdentityTlsServerTest {
    private class Store : IdentitySnapshotStore {
        private var data: ByteArray? = null
        override fun load() = data?.copyOf()
        override fun save(snapshot: ByteArray) { data = snapshot.copyOf() }
    }
    private fun fixture(clock: IdentityClock = IdentityClock(System::currentTimeMillis),
                        block: (PhoneIdentityAuthority, IdentityTlsServer, HandshakeCertificates, OkHttpClient) -> Unit) {
        val authority = PhoneIdentityAuthority(Store(), clock)
        authority.bootstrapOwner("owner", "owner-test-password")
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager).build()
        val server = IdentityTlsServer(serverTls.sslContext().serverSocketFactory, IdentityApiRouter(authority, clock), InetAddress.getLoopbackAddress())
        try { block(authority, server, trusted, client) }
        finally { server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun realHttpsLoginRoleEnforcementAndLogout() = fixture { _, server, _, client ->
        fun call(path: String, method: String = "GET", body: String? = null, access: String? = null): Pair<Int, String> {
            val request = Request.Builder().url("https://localhost:${server.port}$path")
                .method(method, body?.toRequestBody("application/json".toMediaType()))
            access?.let { request.header("Authorization", "Bearer $it") }
            return client.newCall(request.build()).execute().use { it.code to it.body!!.string() }
        }
        val login = call("/auth/login", "POST", """{"login":"owner","password":"owner-test-password"}""")
        assertEquals(200, login.first)
        val owner = JSONObject(login.second).getString("access_token")
        assertEquals(201, call("/admin/users", "POST", """{"login":"employee","password":"employee-test-pass","role":"EMPLOYEE"}""", owner).first)
        val employee = JSONObject(call("/auth/login", "POST", """{"login":"employee","password":"employee-test-pass"}""").second).getString("access_token")
        assertEquals(200, call("/me", access = employee).first)
        assertEquals(403, call("/admin/users", "POST", """{"login":"intruder","password":"intruder-test-pass","role":"ADMIN"}""", employee).first)
        assertEquals(204, call("/auth/logout", "POST", "", employee).first)
        assertEquals(401, call("/me", access = employee).first)
    }

    @Test fun ambiguousHttpFramingIsRejectedBeforeRouting() = fixture { _, server, tls, _ ->
        fun raw(headers: String): String {
            (tls.sslSocketFactory().createSocket("localhost", server.port) as SSLSocket).use { socket ->
                socket.soTimeout = 5000; socket.startHandshake()
                socket.outputStream.apply { write(("POST /auth/login HTTP/1.1\r\nHost: localhost\r\n$headers\r\n").toByteArray()); flush() }
                return socket.inputStream.bufferedReader().readLine()
            }
        }
        assertTrue(raw("Content-Length: 0\r\ncontent-length: 0\r\n").startsWith("HTTP/1.1 400 "))
        assertTrue(raw("Transfer-Encoding: chunked\r\n\r\n0\r\n").startsWith("HTTP/1.1 400 "))
        assertTrue(raw("Content-Length: 9000\r\n").startsWith("HTTP/1.1 413 "))
    }

    @Test fun untrustedCertificateCannotEstablishClientConnection() = fixture { _, server, _, _ ->
        val untrusted = OkHttpClient()
        try {
            assertTrue(runCatching { untrusted.newCall(Request.Builder().url("https://localhost:${server.port}/me").build())
                .execute().close() }.isFailure)
        } finally { untrusted.dispatcher.executorService.shutdown(); untrusted.connectionPool.evictAll() }
    }

    @Test fun clientRefreshesExpiredAccessOnceAndReceivesNewServerSession() {
        val now = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        fixture(IdentityClock { now.get() }) { _, server, tls, _ ->
            val endpoint = IdentityTlsEndpoint("127.0.0.1", server.port, CertificatePinner.pin(tls.trustManager.acceptedIssuers.single()))
            PhoneIdentityClient(endpoint).use { client ->
                assertEquals(IdentityRole.ADMIN, client.login("owner", "owner-test-password", "owned client").role)
                val previousId = client.sessions().single().id
                now.addAndGet(PhoneIdentityAuthority.ACCESS_TTL_MS + 1)
                assertEquals("owner", client.currentUser().login)
                assertNotEquals(previousId, client.sessions().single().id)
                client.logout()
                assertTrue(runCatching { client.currentUser() }.isFailure)
            }
        }
    }
}
