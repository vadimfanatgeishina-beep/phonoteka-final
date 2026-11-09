package com.phonoteka.security

import android.net.Uri
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** A short-lived, owned two-phone fixture. It is intentionally absent from release builds. */
data class PeerWssInvite(val host: String, val port: Int, val key: ByteArray, val pin: String, val expiresAt: Long) {
    fun url(): String = "wss://$host:$port/ws"
    fun toQr(): String = Uri.Builder().scheme("phonoteka-wss").authority("connect")
        .appendQueryParameter("host", host).appendQueryParameter("port", port.toString())
        .appendQueryParameter("key", Base64.getUrlEncoder().withoutPadding().encodeToString(key))
        .appendQueryParameter("pin", pin).appendQueryParameter("until", expiresAt.toString()).build().toString()

    companion object {
        fun parse(raw: String, now: Long = System.currentTimeMillis()): PeerWssInvite? = runCatching {
            require(raw.length <= 512 && raw.none { it.isWhitespace() || it.isISOControl() })
            val uri = Uri.parse(raw)
            require(uri.scheme == "phonoteka-wss" && uri.encodedAuthority == "connect" &&
                uri.encodedPath.isNullOrEmpty() && uri.fragment == null)
            val parts = uri.encodedQuery?.split('&') ?: error("No query")
            require(parts.size == 5 && parts.map { it.substringBefore('=') }.toSet() ==
                setOf("host", "port", "key", "pin", "until"))
            val host = uri.getQueryParameter("host") ?: error("No host")
            val octets = host.split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 })
            val port = uri.getQueryParameter("port")?.toIntOrNull() ?: error("No port")
            require(port in 1024..65535)
            val key = Base64.getUrlDecoder().decode(uri.getQueryParameter("key") ?: error("No key"))
            require(key.size == 32)
            val pin = uri.getQueryParameter("pin") ?: error("No pin")
            require(pin.startsWith("sha256/") && Base64.getDecoder().decode(pin.removePrefix("sha256/")).size == 32)
            val until = uri.getQueryParameter("until")?.toLongOrNull() ?: error("No expiry")
            require(until > now && until - now <= 5 * 60_000L)
            PeerWssInvite(host, port, key, pin, until)
        }.getOrNull()
    }
}

class PeerLab15WssServer private constructor(val invite: PeerWssInvite, private val server: MockWebServer) : AutoCloseable {
    val accepted = AtomicInteger()
    val rejected = AtomicInteger()
    val requests = AtomicInteger()

    override fun close() { server.shutdown() }

    companion object {
        fun start(host: String = localIpv4() ?: error("Не найден IP точки доступа или локальной сети")): PeerLab15WssServer {
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            val cert = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
            val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
            val server = MockWebServer()
            server.useHttps(tls.sslSocketFactory(), false)
            server.start(InetAddress.getByName("0.0.0.0"), 0)
            val invite = PeerWssInvite(host, server.port, key, CertificatePinner.pin(cert.certificate),
                System.currentTimeMillis() + 5 * 60_000L)
            val fixture = PeerLab15WssServer(invite, server)
            val keys = UserBoundSocketKeys(key)
            val tickets = SocketTicketCodec(keys)
            val ticketGuard = FrameReplayGuard()
            val requestSigner = HmacRequestSigner.forMockServer(key)
            val requestGuard = ReplayGuard()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    fun denied(): MockResponse { fixture.rejected.incrementAndGet(); return MockResponse().setResponseCode(403) }
                    if (System.currentTimeMillis() >= invite.expiresAt || request.path != "/ws") return denied()
                    val channel = request.getHeader("X-Lab-Channel") ?: return denied()
                    if (!channel.matches(Regex("/lab15/u/[a-f0-9]{16}/[a-f0-9-]{36}"))) return denied()
                    val signed = SignedRequest(request.getHeader("X-Timestamp")?.toLongOrNull() ?: return denied(),
                        request.getHeader("X-Nonce") ?: return denied(), request.getHeader("X-Signature") ?: return denied())
                    if (requestSigner.verify("GET", "/ws", byteArrayOf(), signed, requestGuard) != VerificationResult.ACCEPTED)
                        return denied()
                    val principal = tickets.verify(request.getHeader(SocketTicketCodec.HEADER) ?: return denied(),
                        channel, ticketGuard).takeIf { it.result == TicketVerification.ACCEPTED }?.principal ?: return denied()
                    fixture.accepted.incrementAndGet()
                    val signer = keys.frameSigner(principal.userId)
                    val frameGuard = FrameReplayGuard()
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = SignedTextFrame.parse(text)
                            if (frame == null || signer.verify(frame, "$channel/request", frameGuard) != FrameVerificationResult.ACCEPTED) {
                                fixture.rejected.incrementAndGet(); webSocket.close(1008, "invalid signed frame"); return
                            }
                            fixture.requests.incrementAndGet()
                            webSocket.send(signer.sign("$channel/response", "peer metric acknowledged").toWire())
                        }
                    })
                }
            }
            return fixture
        }

        private fun localIpv4(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy { if (it.name.contains(Regex("wlan|wifi|ap", RegexOption.IGNORE_CASE))) 0 else 1 }
            .asSequence().flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress

        fun client(invite: PeerWssInvite): OkHttpClient {
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                    throw java.security.cert.CertificateException("Client certificate not expected")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.size != 1 || CertificatePinner.pin(chain[0]) != invite.pin)
                        throw java.security.cert.CertificateException("QR certificate pin mismatch")
                }
            }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
                // The QR pins this ephemeral certificate; the host must still match the scanned address.
                .hostnameVerifier { hostname, _ -> hostname == invite.host }
                .addInterceptor(HmacSigningInterceptor(HmacRequestSigner.forMockServer(invite.key), invite.host))
                .build()
        }
    }
}
