package com.phonoteka.identity

import android.net.Uri

data class IdentityPairingCode(val endpoint: IdentityTlsEndpoint, val expiresAt: Long) {
    fun encode(): String = Uri.Builder().scheme("phonoteka-identity").authority("pair")
        .appendQueryParameter("host", endpoint.host).appendQueryParameter("port", endpoint.port.toString())
        .appendQueryParameter("pin", endpoint.pin).appendQueryParameter("until", expiresAt.toString()).build().toString()

    companion object {
        const val TTL_MS = 5 * 60_000L
        fun parse(raw: String, now: Long = System.currentTimeMillis()): IdentityPairingCode? = runCatching {
            require(raw.length <= 512 && raw.none { it.isWhitespace() || it.isISOControl() })
            val uri = Uri.parse(raw)
            require(uri.scheme == "phonoteka-identity" && uri.encodedAuthority == "pair" &&
                uri.encodedPath.isNullOrEmpty() && uri.fragment == null)
            val parts = uri.encodedQuery?.split('&') ?: error("No parameters")
            require(parts.size == 4 && parts.map { it.substringBefore('=') }.toSet() == setOf("host", "port", "pin", "until"))
            val endpoint = IdentityTlsEndpoint(uri.getQueryParameter("host") ?: error("No host"),
                uri.getQueryParameter("port")?.toIntOrNull() ?: error("No port"), uri.getQueryParameter("pin") ?: error("No pin"))
            val until = uri.getQueryParameter("until")?.toLongOrNull() ?: error("No expiry")
            require(until > now && until - now <= TTL_MS)
            IdentityPairingCode(endpoint, until)
        }.getOrNull()
    }
}
