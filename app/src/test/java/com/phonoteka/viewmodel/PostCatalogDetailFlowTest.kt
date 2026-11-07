package com.phonoteka.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.phonoteka.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PostCatalogDetailFlowTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun emptyCacheReceivesPostAndCommentsFromRefreshAndLaterRoomUpdates() = runTest {
        val repo = FakeDetails()
        repo.refreshAction = { id -> repo.publish(id); null }
        val vm = PostCatalogViewModel(repo)
        val owner = ViewModelStore().apply { put("vm", vm) }
        try {
            vm.loadDetail(1)
            runCurrent()
            assertEquals(1, vm.detail.value.post?.id)
            assertEquals(1, vm.detail.value.comments.single().postId)
            assertFalse(vm.detail.value.loading)
            repo.posts(1).value = CatalogPost(1, 1, "updated in Room", "body")
            repo.comments(1).value = emptyList()
            runCurrent()
            assertEquals("updated in Room", vm.detail.value.post?.title)
            assertTrue(vm.detail.value.comments.isEmpty())
        } finally { owner.clear() }
    }

    @Test fun oldNonCooperativeRefreshCannotReplaceNewDetailOrItsErrorFlags() = runTest {
        val repo = FakeDetails()
        val oldRelease = CompletableDeferred<Unit>()
        repo.refreshAction = { id ->
            if (id == 1) {
                withContext(NonCancellable) { oldRelease.await() }
                repo.publish(1)
                CatalogApiError.Network
            } else { repo.publish(id); null }
        }
        val vm = PostCatalogViewModel(repo)
        val owner = ViewModelStore().apply { put("vm", vm) }
        try {
            vm.loadDetail(1)
            runCurrent()
            vm.loadDetail(2)
            runCurrent()
            oldRelease.complete(Unit)
            runCurrent()
            assertEquals(2, vm.detail.value.post?.id)
            assertEquals(2, vm.detail.value.comments.single().postId)
            assertFalse(vm.detail.value.offline)
            assertNull(vm.detail.value.message)
        } finally { oldRelease.complete(Unit); owner.clear() }
    }

    @Test fun networkErrorPreservesCacheAndMarksDetailOffline() = runTest {
        val repo = FakeDetails().apply {
            publish(1)
            refreshAction = { CatalogApiError.Network }
        }
        val vm = PostCatalogViewModel(repo)
        val owner = ViewModelStore().apply { put("vm", vm) }
        try {
            vm.loadDetail(1)
            runCurrent()
            assertEquals(1, vm.detail.value.post?.id)
            assertTrue(vm.detail.value.offline)
            assertFalse(vm.detail.value.loading)
            assertNotNull(vm.detail.value.message)
        } finally { owner.clear() }
    }

    @Test fun savedDetailIdRestoresObservationWithoutDuplicateScreenRefresh() = runTest {
        val repo = FakeDetails().apply { refreshAction = { id -> publish(id); null } }
        val vm = PostCatalogViewModel(repo, SavedStateHandle(mapOf("lab10.catalog.detailId" to 7)))
        val owner = ViewModelStore().apply { put("vm", vm) }
        try {
            runCurrent()
            vm.loadDetail(7) // LaunchedEffect from the restored screen must not duplicate the request.
            runCurrent()
            assertEquals(listOf(7), repo.requested)
            assertEquals(7, vm.detail.value.post?.id)
        } finally { owner.clear() }
    }

    private class FakeDetails : PostCatalogRepository {
        private val postFlows = mutableMapOf<Int, MutableStateFlow<CatalogPost?>>()
        private val commentFlows = mutableMapOf<Int, MutableStateFlow<List<CatalogComment>>>()
        val requested = mutableListOf<Int>()
        var refreshAction: suspend (Int) -> CatalogApiError? = { null }
        fun posts(id: Int) = postFlows.getOrPut(id) { MutableStateFlow(null) }
        fun comments(id: Int) = commentFlows.getOrPut(id) { MutableStateFlow(emptyList()) }
        fun publish(id: Int) {
            posts(id).value = CatalogPost(1, id, "post $id", "body")
            comments(id).value = listOf(CatalogComment(id, id, "name", "", "comment"))
        }
        override fun observePosts(query: String): Flow<List<CatalogPost>> = MutableStateFlow(emptyList())
        override fun observePost(id: Int): Flow<CatalogPost?> = posts(id)
        override fun observeComments(postId: Int): Flow<List<CatalogComment>> = comments(postId)
        override suspend fun refresh(): CatalogApiError? = null
        override suspend fun loadNextPage(): CatalogApiError? = null
        override suspend fun isEndReached() = false
        override suspend fun refreshDetails(id: Int): CatalogApiError? {
            requested += id
            return refreshAction(id)
        }
    }
}
