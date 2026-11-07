package com.phonoteka.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.phonoteka.network.PostCatalogApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * ЛР10 (доп.): E2E репозитория на НАСТОЯЩЕМ публичном API (https://jsonplaceholder.typicode.com).
 * Включается явно, чтобы обычный прогон не зависел от интернета:
 *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.realApi=true \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.data.RealPublicApiE2ETest
 * Цепочка целиком настоящая: HTTPS → Retrofit/Gson → RoomPostCatalogRepository → Room (in-memory) → Flow.
 */
class RealPublicApiE2ETest {
    private lateinit var database: PhonotekaDatabase
    private lateinit var client: OkHttpClient
    private lateinit var repo: RoomPostCatalogRepository

    @Before fun setUp() {
        assumeTrue("real API E2E disabled (pass realApi=true)",
            InstrumentationRegistry.getArguments().getString("realApi") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = PhonotekaDatabase.getInMemory(context)
        client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        val api = Retrofit.Builder().baseUrl("https://jsonplaceholder.typicode.com/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(PostCatalogApi::class.java)
        repo = RoomPostCatalogRepository(api, database.catalogPostDao())
    }

    @After fun tearDown() {
        if (::client.isInitialized) { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        if (::database.isInitialized) database.close()
    }

    @Test fun firstPageThenNextPageWithoutDuplicates() = runBlocking {
        assertNull(repo.refresh())
        val first = repo.observePosts("").first()
        assertEquals(20, first.size)
        assertEquals((1..20).toList(), first.map { it.id }.sorted())
        assertNull(repo.loadNextPage())
        val two = repo.observePosts("").first()
        assertEquals(40, two.size)
        assertEquals(two.size, two.map { it.id }.toSet().size)
    }

    @Test fun detailsAndCommentsAreCachedForOffline() = runBlocking {
        assertNull(repo.refreshDetails(1))
        val post = repo.observePost(1).first()
        assertTrue(post != null && post.title.isNotBlank())
        val comments = repo.observeComments(1).first()
        assertTrue(comments.isNotEmpty() && comments.all { it.postId == 1 })
    }

    @Test fun localSearchWorksOnRealData() = runBlocking {
        assertNull(repo.refresh())
        val all = repo.observePosts("").first()
        val word = all.first().title.split(' ').first { it.length >= 4 }
        val found = repo.observePosts(word).first()
        assertTrue(found.isNotEmpty() && found.size <= all.size)
    }

    @Test fun nonExistingPostGivesTypedHttpError() = runBlocking {
        assertEquals(CatalogApiError.Http(404), repo.refreshDetails(100_000))
    }
}
