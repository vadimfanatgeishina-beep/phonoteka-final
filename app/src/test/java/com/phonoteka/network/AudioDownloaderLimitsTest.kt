package com.phonoteka.network
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioDownloaderLimitsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun disconnectedDownloadPreservesOldFileAndCanBeRetried() = runTest {
        fixture { server, client ->
            val target = File(temporary.newFolder(), "audio.mp3").apply { writeText("original") }
            server.enqueue(MockResponse().setBody("a".repeat(16384))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertTrue(runCatching { AudioDownloader(client).download(server.url("/broken").toString(), target) }.isFailure)
            assertEquals("original", target.readText())
            assertFalse(File(target.parentFile, target.name + ".part").exists())
            server.enqueue(MockResponse().setBody("replacement"))
            AudioDownloader(client).download(server.url("/retry").toString(), target)
            assertEquals("replacement", target.readText())
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun oversizeKnownAndChunkedBodiesPreserveOldDestinationAndRemovePartial() = runTest {
        for (chunked in listOf(false, true)) {
            fixture { server, client ->
                val response = MockResponse().setHeader("Content-Type", "audio/mpeg")
                if (chunked) response.setChunkedBody("x".repeat(64), 8) else response.setBody("x".repeat(64))
                server.enqueue(response)
                val destination = File(temporary.newFolder(), "audio.mp3").apply { writeText("original") }
                var rejected = false
                try { AudioDownloader(client, byteLimit = 32).download(server.url("/audio").toString(), destination) }
                catch (_: java.io.IOException) { rejected = true }
                assertTrue(rejected); assertEquals("original", destination.readText())
                assertFalse(File(destination.parentFile, destination.name + ".part").exists())
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun oversizedPlaylistAndTlsToHttpRedirectAreRejected() = runTest {
        fixture { server, client ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n" + "x".repeat(64)))
            val target = File(temporary.newFolder(), "audio.mp3")
            var rejected = false
            try { AudioDownloader(client, playlistLimit = 32).download(server.url("/playlist").toString(), target) }
            catch (_: java.io.IOException) { rejected = true }
            assertTrue(rejected); assertFalse(target.exists())
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:1/not-contacted"))
            rejected = false
            try { AudioDownloader(client).download(server.url("/redirect").toString(), target) }
            catch (_: java.io.IOException) { rejected = true }
            assertTrue(rejected); assertFalse(target.exists())
            assertEquals(2, server.requestCount)
        }
    }
    private suspend fun fixture(block: suspend (MockWebServer, OkHttpClient) -> Unit) {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false); server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        try { block(server, client) }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }
}
