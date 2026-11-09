package com.phonoteka.identity

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private fun validateIdentityHost(host: String) {
    val parts = host.split('.')
    require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 })
    val address = parts.map { it.toInt() }
    require(address[0] == 10 || address[0] == 192 && address[1] == 168 ||
        address[0] == 172 && address[1] in 16..31 || host == "127.0.0.1")
}

/** Public pairing data only. A fingerprint is not a password or a role grant. */
data class IdentityTlsEndpoint(val host: String, val port: Int, val pin: String) {
    init {
        validateIdentityHost(host)
        require(port in 1024..65535)
        require(pin.startsWith("sha256/") && Base64.getDecoder().decode(pin.removePrefix("sha256/")).size == 32)
    }
    val baseUrl: String get() = "https://$host:$port"

    /** Trust only the certificate paired by the owner, and never redirect tokens to another origin. */
    fun client(): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Client certificate not expected")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.size != 1 || CertificatePinner.pin(chain[0]) != pin) throw CertificateException("Identity host pin mismatch")
                chain[0].checkValidity()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier { hostname, _ -> hostname == host }
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(15, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                require(request.url.scheme == "https" && request.url.host == host && request.url.port == port &&
                    request.url.username.isEmpty() && request.url.password.isEmpty()) { "Unpaired identity origin" }
                chain.proceed(request)
            }.build()
    }
}

/** The certificate/private key live only in this process; a restarted host requires pairing again. */
class IdentityHostTls private constructor(val endpoint: IdentityTlsEndpoint, private val transport: IdentityTlsServer) : AutoCloseable {
    override fun close() = transport.close()

    companion object {
        /** Call on an IO dispatcher, after local owner provisioning and before exposing a pairing QR. */
        fun start(authority: PhoneIdentityAuthority, host: String): IdentityHostTls {
            validateIdentityHost(host)
            val certificate = HeldCertificate.Builder().commonName("Phonoteka owned identity host")
                .addSubjectAlternativeName(host).duration(1, TimeUnit.DAYS).build()
            val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val server = IdentityTlsServer(tls.sslContext().serverSocketFactory, IdentityApiRouter(authority), InetAddress.getByName(host))
            return IdentityHostTls(IdentityTlsEndpoint(host, server.port, CertificatePinner.pin(certificate.certificate)), server)
        }
    }
}
