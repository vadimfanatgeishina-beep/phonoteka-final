package com.phonoteka.viewmodel

import com.phonoteka.data.CatalogApiError
import com.phonoteka.data.CatalogComment
import com.phonoteka.data.CatalogPost
import com.phonoteka.data.PostCatalogRepository
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PostCatalogViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun quickSearchUsesOnlyLastDebouncedQuery() = runTest {
        val repository = FakeCatalogRepository()
        val viewModel = PostCatalogViewModel(repository)
        advanceTimeBy(300)
        runCurrent()
        viewModel.search("c")
        advanceTimeBy(100)
        viewModel.search("ca")
        advanceTimeBy(100)
        viewModel.search("catalog")
        advanceTimeBy(299)
        runCurrent()
        assertEquals(listOf(""), repository.queries)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("", "catalog"), repository.queries)
    }

    @Test fun networkFailureKeepsCachedPostsAndMarksOffline() = runTest {
        val repository = FakeCatalogRepository().apply { refreshResult = CatalogApiError.Network }
        val viewModel = PostCatalogViewModel(repository)
        runCurrent()
        advanceTimeBy(300)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertTrue(state is CatalogUiState.Success && state.offline && state.posts.isNotEmpty())
    }

    @Test fun catalogQueryIsRestoredFromSavedStateHandle() = runTest {
        val repository = FakeCatalogRepository()
        val restoredHandle = SavedStateHandle(mapOf("lab10.catalog.query" to "Muse"))
        PostCatalogViewModel(repository, restoredHandle)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(repository.queries.contains("Muse"))
    }

    private class FakeCatalogRepository : PostCatalogRepository {
        val queries = mutableListOf<String>()
        var refreshResult: CatalogApiError? = null
        private val posts = listOf(CatalogPost(1, 1, "catalog item", "body"))
        override fun observePosts(query: String): Flow<List<CatalogPost>> {
            queries += query
            return flowOf(posts.filter { it.title.contains(query, ignoreCase = true) })
        }
        override fun observePost(id: Int): Flow<CatalogPost?> = flowOf(posts.firstOrNull { it.id == id })
        override fun observeComments(postId: Int): Flow<List<CatalogComment>> = flowOf(emptyList())
        override suspend fun refresh() = refreshResult
        override suspend fun loadNextPage(): CatalogApiError? = null
        override suspend fun refreshDetails(id: Int): CatalogApiError? = null
        override suspend fun isEndReached() = false
    }
}
