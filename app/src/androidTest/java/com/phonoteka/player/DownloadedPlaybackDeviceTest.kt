package com.phonoteka.player

import android.app.Application
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.phonoteka.data.Track
import com.phonoteka.data.TrackStatus
import com.phonoteka.network.AudioDownloader
import com.phonoteka.network.validateDownloadedAudio
import com.phonoteka.viewmodel.PlayerViewModel
import com.phonoteka.viewmodel.isLocallyPlayable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Exercises actual TLS download and Media3 decoding/service IPC, without external music services. */
class DownloadedPlaybackDeviceTest {
    @get:Rule val ui = createComposeRule()

    @Test fun downloadedAudioPlaysPausesAndMovesThroughQueue() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(app.cacheDir, "playback-${UUID.randomUUID()}").apply { mkdirs() }
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        var vm: PlayerViewModel? = null
        try {
            val wav = silentWav()
            val tracks = (1L..2L).map { id ->
                server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)))
                val result = runBlocking(Dispatchers.IO) {
                    AudioDownloader(http, validateFile = ::validateDownloadedAudio)
                        .download(server.url("/$id.wav").toString(), File(dir, "$id.wav"))
                }
                assertEquals(wav.size.toLong(), result.bytes)
                assertTrue(wav.contentEquals(result.file.readBytes()))
                Track(id, "Тестовый сигнал", "Трек $id", TrackStatus.DONE,
                    filePath = result.file.absolutePath, source = "owned-fixture")
            }
            // A successful HTTP response containing HTML must not overwrite already downloaded audio.
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>provider error</html>"))
            val rejected = runCatching { runBlocking(Dispatchers.IO) {
                AudioDownloader(http, validateFile = ::validateDownloadedAudio)
                    .download(server.url("/invalid").toString(), File(tracks[0].filePath!!))
            } }.isFailure
            assertTrue(rejected)
            assertTrue(wav.contentEquals(File(tracks[0].filePath!!).readBytes()))
            assertFalse(File(tracks[0].filePath!! + ".part").exists())
            ui.setContent { Text("Проверка скачанного аудио") }
            ui.runOnIdle { vm = PlayerViewModel(app) }
            ui.waitUntil(15_000) { vm!!.uiState.value.isConnected }
            ui.runOnIdle { vm!!.play(tracks[0], tracks) }
            ui.waitUntil(15_000) { vm!!.uiState.value.isPlaying && vm!!.uiState.value.positionMs > 0 }
            assertTrue(vm!!.uiState.value.track!!.isLocallyPlayable())
            assertTrue(vm!!.uiState.value.durationMs >= 29_000)
            ui.runOnIdle { vm!!.togglePlayPause() }
            ui.waitUntil(5_000) { !vm!!.uiState.value.isPlaying }
            ui.runOnIdle { vm!!.togglePlayPause(); vm!!.next() }
            ui.waitUntil(10_000) { vm!!.uiState.value.track?.id == 2L && vm!!.uiState.value.isPlaying }
            ui.runOnIdle { vm!!.previous() }
            ui.waitUntil(10_000) { vm!!.uiState.value.track?.id == 1L && vm!!.uiState.value.isPlaying }
            assertNull(vm!!.uiState.value.error)
        } finally {
            ui.runOnIdle { vm?.clear(); app.stopService(Intent(app, PlaybackService::class.java)) }
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.shutdown()
            dir.deleteRecursively()
        }
    }

    private fun silentWav(): ByteArray {
        val size = 8_000 * 2 * 30
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
            put(ByteArray(size))
        }.array()
    }
}
