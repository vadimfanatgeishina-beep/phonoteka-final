package com.phonoteka.security

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteIdentityClientTest {
    private val server = MockWebServer()
    private lateinit var client: RemoteIdentityClient

    @Before fun setUp() { server.start(); client = RemoteIdentityClient("http://127.0.0.1:${server.port}/", OkHttpClient()) }
    @After fun tearDown() = server.shutdown()

    private val tokens = """{"access_token":"AAAA","refresh_token":"RRRR","token_type":"Bearer","expires_in":300,"role":"EMPLOYEE","scope":"pass:view library:read"}"""

    @Test fun loginUsesServerScopesAndBearer() {
        server.enqueue(MockResponse().setBody(tokens))
        server.enqueue(MockResponse().setBody("""{"sub":"student","role":"EMPLOYEE","scope":"pass:view"}"""))
        val r = client.login("student", "pw") as RemoteIdentityClient.Result.Ok
        assertEquals(setOf(DemoScope.PASS_VIEW, DemoScope.LIBRARY_READ), r.value.scopes)
        assertTrue(client.get("me") is RemoteIdentityClient.Result.Ok)
        assertEquals("/auth/login", server.takeRequest().path)
        assertEquals("Bearer AAAA", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun typedErrors() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(RemoteIdentityClient.Result.InvalidCredentials, client.login("a", "b"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "42"))
        assertEquals(RemoteIdentityClient.Result.Locked(42), client.login("a", "b"))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(RemoteIdentityClient.Result.SessionRevoked, client.refresh("old"))
        assertEquals(RemoteIdentityClient.Result.SessionRevoked, client.get("me"))
    }

    @Test fun forbiddenScope() {
        server.enqueue(MockResponse().setBody(tokens))
        server.enqueue(MockResponse().setResponseCode(403))
        client.login("student", "pw")
        assertEquals(RemoteIdentityClient.Result.Forbidden, client.get("audit"))
    }

    @Test(expected = IllegalArgumentException::class) fun cleartextRemoteRejected() {
        RemoteIdentityClient("http://example.com/", OkHttpClient())
    }
}
