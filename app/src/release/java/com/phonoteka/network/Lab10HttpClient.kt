package com.phonoteka.network

import okhttp3.OkHttpClient

/** Release catalog client deliberately has no HTTP logging interceptor. */
fun createLab10HttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
