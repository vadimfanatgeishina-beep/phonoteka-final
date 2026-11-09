package com.phonoteka.security

import android.content.Intent
import android.net.Uri

data class QuickAccessLink(val host: String, val port: Int, val ticket: String)

object QuickAccessLinkParser {
    fun parse(intent: Intent?): QuickAccessLink? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val raw = intent.dataString ?: return null
        if (raw.length > 256 || raw.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = Uri.parse(raw)
        if (uri.scheme != "phonoteka" || uri.encodedAuthority != "quick-access" || uri.port != -1 ||
            !uri.encodedPath.isNullOrEmpty() || uri.encodedFragment != null) return null
        val pairs = uri.encodedQuery?.split('&') ?: return null
        if (pairs.size != 3) return null
        val keys = pairs.map { pair ->
            val index = pair.indexOf('=')
            if (index <= 0 || index == pair.lastIndex) return null
            pair.substring(0, index)
        }
        if (keys.toSet() != setOf("host", "port", "ticket")) return null
        val host = uri.getQueryParameter("host") ?: return null
        if (!validIpv4(host)) return null
        val port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
        val ticket = uri.getQueryParameter("ticket")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{40,48}")) } ?: return null
        return QuickAccessLink(host, port, ticket)
    }

    private fun validIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }
    }
}
