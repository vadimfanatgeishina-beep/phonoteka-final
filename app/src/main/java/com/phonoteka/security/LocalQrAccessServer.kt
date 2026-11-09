package com.phonoteka.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Temporary one-use role grant endpoint on the owner's hotspot. APK download uses GitHub Releases. */
class LocalQrAccessServer(
    private val context: Context,
    private val onRedeemed: (role: DemoRole, actorId: String) -> Unit = { _, _ -> },
    private val onDenied: (reason: String) -> Unit = { }
) : AutoCloseable {
    data class Endpoint(val host: String, val port: Int)
    data class Grant(val role: DemoRole, val actorId: String, val token: String, val expiresAtElapsed: Long)

    private data class PendingGrant(val role: DemoRole, val actorId: String, val expiresAtElapsed: Long)
    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, PendingGrant>()
    /** Погашенные билеты: нужны, чтобы отличать повторное использование от неизвестного билета. */
    private val consumedLock = Any()
    private val consumed = ArrayDeque<String>()
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var endpoint: Endpoint? = null

    @Synchronized fun start(): Endpoint {
        val cached = endpoint
        if (cached != null && server?.isClosed == false && !lanAddressChanged(cached.host)) return cached
        // Адрес в локальной сети сменился (переподключение к другой точке доступа, включение Wi-Fi
        // после открытия экрана): старый QR указывает в пустоту. Сокет и адрес пересоздаются,
        // но выданные билеты НЕ сбрасываются — иначе уже показанный QR2 перестал бы работать.
        if (cached != null) {
            runCatching { server?.close() }
            server = null
            endpoint = null
        }
        val socket = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
        server = socket
        val host = findLanAddress(context)?.hostAddress ?: run { socket.close(); server = null; error("Не найден IP телефона в локальной сети") }
        return Endpoint(host, socket.localPort).also { info ->
            endpoint = info
            workers.execute {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        workers.execute { handle(client) }
                    }
                    catch (_: Exception) { if (!socket.isClosed) close() }
                }
            }
        }
    }

    /** true только если адрес достоверно другой; «не смогли определить» не считается сменой сети. */
    private fun lanAddressChanged(host: String): Boolean {
        val current = findLanAddress(context)?.hostAddress ?: return false
        return current != host
    }

    fun issue(role: DemoRole): Grant {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val actorId = "qr:" + java.util.UUID.randomUUID().toString()
        val expires = SystemClock.elapsedRealtime() + GRANT_TTL_MS
        pending[token] = PendingGrant(role, actorId, expires)
        return Grant(role, actorId, token, expires)
    }

    fun grantUri(grant: Grant, info: Endpoint = start()): String = Uri.Builder()
        .scheme("phonoteka").authority("quick-access")
        .appendQueryParameter("host", info.host)
        .appendQueryParameter("port", info.port.toString())
        .appendQueryParameter("ticket", grant.token)
        .build().toString()

    private fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 8_000
            val input = BufferedInputStream(s.getInputStream())
            val request = readLine(input, 4096) ?: return
            val match = Regex("GRANT ([A-Za-z0-9_-]{40,48})").matchEntire(request)
            val token = match?.groupValues?.get(1)
            val grant = token?.let { pending.remove(it) }
            if (grant == null) {
                // Билет уже погашен — это повторное использование; билет, которого не выдавали, — неизвестный.
                onDenied(if (token != null && wasConsumed(token)) "already_used" else "bad_ticket")
                writeLine(s, "DENIED")
                return
            }
            if (SystemClock.elapsedRealtime() >= grant.expiresAtElapsed) {
                onDenied("expired")
                writeLine(s, "DENIED")
                return
            }
            markConsumed(token)
            onRedeemed(grant.role, grant.actorId)
            writeLine(s, "GRANTED|${grant.role.name}|${grant.actorId}|${SESSION_TTL_MS}")
        }
    }

    private fun markConsumed(token: String) = synchronized(consumedLock) {
        consumed.addLast(token)
        while (consumed.size > 128) consumed.removeFirst()
    }

    private fun wasConsumed(token: String): Boolean = synchronized(consumedLock) { consumed.contains(token) }

    private fun writeLine(socket: Socket, line: String) {
        socket.getOutputStream().apply { write((line + "\n").toByteArray(Charsets.US_ASCII)); flush() }
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < limit) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            if (b == 10) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
            bytes.write(b)
        }
        return null
    }

    @Synchronized override fun close() {
        endpoint = null
        pending.clear()
        synchronized(consumedLock) { consumed.clear() }
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    companion object {
        const val GRANT_TTL_MS = 120_000L
        const val SESSION_TTL_MS = 30 * 60_000L

        fun redeem(host: String, port: Int, ticket: String): Pair<DemoRole, String> {
            require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")))
            require(port in 1024..65535 && ticket.matches(Regex("[A-Za-z0-9_-]{40,48}")))
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                socket.soTimeout = 5000
                socket.getOutputStream().apply { write("GRANT $ticket\n".toByteArray(Charsets.US_ASCII)); flush() }
                val line = readLine(BufferedInputStream(socket.getInputStream()), 256) ?: error("Пустой ответ телефона 1")
                val fields = line.split('|')
                check(fields.size == 4 && fields[0] == "GRANTED") { "Приглашение истекло или уже использовано" }
                val role = DemoRole.entries.firstOrNull { it.name == fields[1] } ?: error("Неизвестная роль приглашения")
                val actor = fields[2]
                require(actor.matches(Regex("qr:[A-Za-z0-9-]{36}")))
                require(fields[3].toLongOrNull() == SESSION_TTL_MS)
                return role to actor
            }
        }

        private fun readLine(input: BufferedInputStream, limit: Int): String? {
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < limit) {
                val b = input.read()
                if (b < 0 || b == 10) break
                out.write(b)
            }
            return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
        }

        private fun findLanAddress(context: Context): Inet4Address? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val capabilities = network?.let(cm::getNetworkCapabilities)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                cm.getLinkProperties(network)?.linkAddresses?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.let { return it }
            }
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val ranked = interfaces.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .sortedBy { if (it.name.contains(Regex("wlan|wifi|ap", RegexOption.IGNORE_CASE))) 0 else 1 }
            return ranked.asSequence().flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
        }
    }
}
