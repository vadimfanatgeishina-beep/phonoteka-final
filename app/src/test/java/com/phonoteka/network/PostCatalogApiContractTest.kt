package com.phonoteka.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PostCatalogApiContractTest {
    @Test fun retrofitUsesExpectedJsonPlaceholderRouteAndDtoShape() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""[{"userId":7,"id":42,"title":"fixture title","body":"fixture body"}]"""))
        server.start()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/").toString())
                .addConverterFactory(GsonConverterFactory.create()).build().create(PostCatalogApi::class.java)
            val posts = api.getPosts(start = 0, limit = 1)
            assertEquals(42, posts.single().id)
            assertEquals(7, posts.single().userId)
            assertEquals("fixture title", posts.single().title)
            assertEquals("/posts?_start=0&_limit=1", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
