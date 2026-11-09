package com.phonoteka.data
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.phonoteka.network.PostCatalogApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class LocalRoomCatalogContractTest {
    @Test fun ownedHttpsApiRefreshesRealRoomAndOfflineKeepsCache() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = PhonotekaDatabase.getInMemory(context)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false); server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS).readTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
        val api = Retrofit.Builder().baseUrl(server.url("/")).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(PostCatalogApi::class.java)
        val repo = RoomPostCatalogRepository(api, database.catalogPostDao())
        try {
            server.enqueue(MockResponse().setBody("""[{"id":1,"userId":1,"title":"fixture","body":"body"}]"""))
            assertNull(repo.refresh())
            assertEquals(1, repo.observePosts("").first().single().id)
            server.enqueue(MockResponse().setBody("""{"id":1,"userId":1,"title":"updated","body":"body"}"""))
            server.enqueue(MockResponse().setBody("""[{"postId":1,"id":1,"name":"local","email":"","body":"comment"}]"""))
            assertNull(repo.refreshDetails(1))
            assertEquals("updated", database.catalogPostDao().observePost(1).first()?.title)
            assertEquals(MusicEditorial.post(CatalogPost(1, 1, "updated", "body")).title,
                repo.observePost(1).first()?.title)
            assertEquals(1, repo.observeComments(1).first().size)
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(CatalogApiError.Http(503), repo.refresh())
            assertEquals("updated", database.catalogPostDao().observePost(1).first()?.title)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown(); database.close() }
    }
}
