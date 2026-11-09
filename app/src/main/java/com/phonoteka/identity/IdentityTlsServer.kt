package com.phonoteka.identity

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket

/** Bounded HTTPS/1.1 listener for the owned phone-host. One request per connection; no plaintext listener. */
class IdentityTlsServer(
    factory: SSLServerSocketFactory,
    private val router: IdentityApiRouter,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0")
) : AutoCloseable {
    private val listener = (factory.createServerSocket(0, 8, bindAddress) as SSLServerSocket).apply {
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        needClientAuth = false
    }
    val port: Int get() = listener.localPort
    private val closed = AtomicBoolean()
    private val active = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "identity-request").apply { isDaemon = true } })
    private val acceptor = Executors.newSingleThreadExecutor { task -> Thread(task, "identity-accept").apply { isDaemon = true } }

    init {
        acceptor.execute {
            while (!closed.get()) {
                val socket = try { listener.accept() as SSLSocket } catch (_: Exception) { break }
                active.add(socket)
                try { workers.execute { handle(socket) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { active.remove(socket); runCatching { socket.close() } }
            }
        }
    }

    private class BadRequest(val status: Int = 400) : Exception()
    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun line(input: BufferedInputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        while (out.size() <= max) {
            val byte = input.read()
            if (byte < 0) throw BadRequest()
            if (byte == 13) {
                if (input.read() != 10) throw BadRequest()
                return out.toString(Charsets.US_ASCII.name())
            }
            if (byte == 10 || byte > 126 || byte < 32 && byte != 9) throw BadRequest()
            out.write(byte)
        }
        throw BadRequest(431)
    }

    private fun request(input: BufferedInputStream): Request {
        val request = line(input, 280).split(' ')
        if (request.size != 3 || request[2] != "HTTP/1.1" || !request[0].matches(Regex("[A-Z]{1,16}")) ||
            !request[1].startsWith('/') || request[1].length > 256) throw BadRequest()
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val row = line(input, 2115)
            total += row.length + 2
            if (total > 8192) throw BadRequest(431)
            if (row.isEmpty()) break
            if (headers.size >= 32) throw BadRequest(431)
            val colon = row.indexOf(':')
            if (colon !in 1..64) throw BadRequest()
            val name = row.substring(0, colon)
            if (!name.matches(Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+"))) throw BadRequest()
            val key = name.lowercase(Locale.ROOT)
            if (headers.put(key, row.substring(colon + 1).trim(' ', '\t')) != null) throw BadRequest()
        }
        if (headers["host"].isNullOrBlank() || "transfer-encoding" in headers || "expect" in headers) throw BadRequest()
        val length = headers["content-length"]?.let {
            if (!it.matches(Regex("0|[1-9][0-9]{0,9}"))) throw BadRequest()
            it.toLongOrNull() ?: throw BadRequest()
        } ?: 0L
        if (length > IdentityApiRouter.MAX_BODY_BYTES) throw BadRequest(413)
        val body = ByteArray(length.toInt())
        var position = 0
        while (position < body.size) {
            val count = input.read(body, position, body.size - position)
            if (count < 0) throw BadRequest()
            position += count
        }
        return Request(request[0], request[1], headers, body)
    }

    private fun handle(socket: SSLSocket) {
        try {
            socket.use {
                socket.soTimeout = 5_000
                socket.startHandshake()
                val response = try {
                    val req = request(BufferedInputStream(socket.inputStream))
                    try { router.handle(req.method, req.path, req.headers, req.body) }
                    finally { req.body.fill(0) }
                } catch (bad: BadRequest) {
                    IdentityApiResponse(bad.status, mapOf("Cache-Control" to "no-store", "Content-Type" to "application/json"),
                        "{\"error\":\"invalid_http_request\"}".toByteArray())
                }
                val header = buildString {
                    append("HTTP/1.1 ${response.status} Response\r\n")
                    response.headers.forEach { (name, value) ->
                        check('\r' !in name && '\n' !in name && '\r' !in value && '\n' !in value)
                        if (!name.equals("Content-Length", true) && !name.equals("Connection", true)) append("$name: $value\r\n")
                    }
                    append("Content-Length: ${response.body.size}\r\nConnection: close\r\n\r\n")
                }
                socket.outputStream.apply { write(header.toByteArray(Charsets.US_ASCII)); write(response.body); flush() }
                response.body.fill(0)
            }
        } catch (_: Exception) {
            // Timeout, rejected TLS or disconnected client: close only this connection; never log credentials.
        } finally { active.remove(socket); runCatching { socket.close() } }
    }

    /** Stop transport before closing the authority's snapshot store. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        active.forEach { runCatching { it.close() } }
        workers.shutdownNow(); acceptor.shutdownNow()
        check(workers.awaitTermination(10, TimeUnit.SECONDS)) { "Identity requests are still stopping" }
    }
}
