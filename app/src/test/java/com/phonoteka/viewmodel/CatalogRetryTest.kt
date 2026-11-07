package com.phonoteka.viewmodel
import com.phonoteka.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogRetryTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun onlyNetworkFailuresRetryWithOneTwoFourSecondBackoff() = runTest {
        val repo = Fake()
        val vm = PostCatalogViewModel(repo)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent(); assertEquals(1, repo.calls)
            advanceTimeBy(999); runCurrent(); assertEquals(1, repo.calls)
            advanceTimeBy(1); runCurrent(); assertEquals(2, repo.calls)
            advanceTimeBy(2000); runCurrent(); assertEquals(3, repo.calls)
            advanceTimeBy(4000); runCurrent(); assertEquals(4, repo.calls)
            assertNull(vm.retryMessage.value)
            val state = vm.state.value as CatalogUiState.Success
            assertFalse(state.offline)
        } finally { owner.clear() }
    }
    @Test fun httpFailureDoesNotRetryAndRawQueryUpdatesImmediately() = runTest {
        val repo = Fake().apply { http = true }
        val vm = PostCatalogViewModel(repo)
        val owner = androidx.lifecycle.ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent(); advanceTimeBy(10_000); runCurrent()
            assertEquals(1, repo.calls)
            vm.search("Muse"); assertEquals("Muse", vm.query.value)
        } finally { owner.clear() }
    }
    private class Fake : PostCatalogRepository {
        var calls = 0; var http = false
        override fun observePosts(query: String): Flow<List<CatalogPost>> = flowOf(listOf(CatalogPost(1, 1, "fixture", "body")))
        override fun observePost(id: Int): Flow<CatalogPost?> = flowOf(null)
        override fun observeComments(postId: Int): Flow<List<CatalogComment>> = flowOf(emptyList())
        override suspend fun refresh(): CatalogApiError? { calls++; return if (http) CatalogApiError.Http(403) else if (calls < 4) CatalogApiError.Network else null }
        override suspend fun loadNextPage(): CatalogApiError? = null
        override suspend fun refreshDetails(id: Int): CatalogApiError? = null
        override suspend fun isEndReached() = false
    }
}
