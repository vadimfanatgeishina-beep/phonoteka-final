package com.phonoteka.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException

class NoNetworkException : IOException("Нет подключения к интернету. Проверьте сеть и повторите поиск.")

/** A transport is present; actual service failures are still reported by the request. */
fun requireNetworkTransport(context: Context) {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: throw NoNetworkException()
    val network = manager.activeNetwork ?: throw NoNetworkException()
    val capabilities = manager.getNetworkCapabilities(network) ?: throw NoNetworkException()
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) throw NoNetworkException()
}
