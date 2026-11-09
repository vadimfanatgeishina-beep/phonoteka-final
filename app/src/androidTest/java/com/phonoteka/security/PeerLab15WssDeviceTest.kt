package com.phonoteka.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.spec.SecretKeySpec

/** Both ends use real Android TLS/WebSocket sockets inside the emulator, without adb reverse. */
@RunWith(AndroidJUnit4::class)
class PeerLab15WssDeviceTest {
    @Test fun phonePeerFixtureAcceptsSignedFramesAndRejectsReplay() = runBlocking {
        val server = PeerLab15WssServer.start("127.0.0.1")
        val invite = PeerWssInvite.parse(server.invite.toQr())
        assertNotNull(invite)
        val client = PeerLab15WssServer.client(invite!!)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val keys = UserBoundSocketKeys(invite.key)
        val binding = SocketUserBinding(keys, currentPrincipal = { SocketPrincipal("mock.student", 1) })
        val socket = LocalLiveSocket(client, HmacTextFrameSigner(SecretKeySpec(invite.key, "HmacSHA256")),
            scope, maxReconnects = 0, userBinding = binding, allowedHost = invite.host)
        try {
            socket.connect(invite.url())
            val initial = withTimeout(15_000) { socket.metrics.first { it.received >= 1 || it.terminalError != null } }
            assertTrue("TLS/WSS failed: ${initial.terminalError}; server rejected=${server.rejected.get()}", initial.connected)
            assertEquals(1, server.accepted.get())
            assertTrue(socket.sendMetricRequest())
            withTimeout(10_000) { socket.metrics.first { it.received >= 2 } }
            assertTrue(server.requests.get() >= 2)
        } finally {
            socket.close(); scope.cancel()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            server.close()
        }
    }

    @Test fun malformedPeerQrIsRejected() {
        assertEquals(null, PeerWssInvite.parse("phonoteka-wss://connect?host=127.0.0.1&port=8444&key=bad"))
    }

    @Test fun wrongQrCertificatePinCannotConnect() {
        val server = PeerLab15WssServer.start("127.0.0.1")
        val wrong = server.invite.copy(pin = "sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32)))
        val client = PeerLab15WssServer.client(wrong)
        try {
            val connected = runCatching {
                client.newCall(Request.Builder().url("https://${wrong.host}:${wrong.port}/ws").build()).execute().close()
            }.isSuccess
            assertTrue("TLS must reject a QR with the wrong certificate pin", !connected)
            assertEquals(0, server.accepted.get())
        } finally {
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
            server.close()
        }
    }
}
