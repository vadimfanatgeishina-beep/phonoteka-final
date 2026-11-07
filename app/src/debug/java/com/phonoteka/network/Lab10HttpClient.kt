package com.phonoteka.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/** HTTP method/status/timing diagnostics for the public Lab 10 demo API; bodies and headers stay out of logs. */
fun createLab10HttpClient(): OkHttpClient {
    val logging = HttpLoggingInterceptor { message -> Log.d("Lab10Http", message) }
        .apply {
            level = HttpLoggingInterceptor.Level.BASIC
            // На случай повышения уровня до HEADERS: секреты не попадают в logcat.
            redactHeader("Authorization"); redactHeader("Cookie"); redactHeader("Set-Cookie"); redactHeader("X-Lab-Ticket")
        }
    return OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).addInterceptor(logging).build()
}
