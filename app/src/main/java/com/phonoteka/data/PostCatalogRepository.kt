package com.phonoteka.data

import com.phonoteka.network.CatalogCommentDto
import com.phonoteka.network.CatalogPostDto
import com.phonoteka.network.PostCatalogApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

sealed interface CatalogApiError {
    data object Network : CatalogApiError
    data class Http(val code: Int) : CatalogApiError
    data object Parsing : CatalogApiError
    data object Unknown : CatalogApiError
}

interface PostCatalogRepository {
    fun observePosts(query: String): Flow<List<CatalogPost>>
    fun observePost(id: Int): Flow<CatalogPost?>
    fun observeComments(postId: Int): Flow<List<CatalogComment>>
    suspend fun refresh(): CatalogApiError?
    suspend fun loadNextPage(): CatalogApiError?
    suspend fun refreshDetails(id: Int): CatalogApiError?
    suspend fun isEndReached(): Boolean
}

class RoomPostCatalogRepository(
    private val api: PostCatalogApi,
    private val dao: CatalogPostDao
) : PostCatalogRepository {
    private val pageMutex = Mutex()
    private var nextOffset = 0
    private var endReached = false
    override suspend fun isEndReached(): Boolean = pageMutex.withLock { endReached }

    override fun observePosts(query: String): Flow<List<CatalogPost>> = dao.observePosts("").map { rows ->
        rows.map { MusicEditorial.post(it.toPost()) }
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true) }
    }
    override fun observePost(id: Int): Flow<CatalogPost?> = dao.observePost(id).map { it?.toPost()?.let(MusicEditorial::post) }
    override fun observeComments(postId: Int): Flow<List<CatalogComment>> = dao.observeComments(postId).map { rows ->
        rows.map { MusicEditorial.comment(it.toComment()) }
    }

    override suspend fun refresh(): CatalogApiError? = withContext(Dispatchers.IO) {
        pageMutex.withLock {
        try {
            val posts = api.getPosts(start = 0, limit = PAGE_SIZE)
            dao.upsertPosts(posts.map { it.toEntity() })
            nextOffset = posts.size
            endReached = posts.size < PAGE_SIZE
            null
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { CatalogApiError.Network }
        catch (e: HttpException) { CatalogApiError.Http(e.code()) }
        catch (_: Exception) { CatalogApiError.Parsing }
        }
    }

    override suspend fun loadNextPage(): CatalogApiError? = withContext(Dispatchers.IO) {
        pageMutex.withLock {
        if (endReached) return@withContext null
        try {
            val posts = api.getPosts(start = nextOffset, limit = PAGE_SIZE)
            dao.upsertPosts(posts.map { it.toEntity() })
            nextOffset += posts.size
            endReached = posts.size < PAGE_SIZE
            null
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { CatalogApiError.Network }
        catch (e: HttpException) { CatalogApiError.Http(e.code()) }
        catch (_: Exception) { CatalogApiError.Parsing }
        }
    }

    override suspend fun refreshDetails(id: Int): CatalogApiError? = withContext(Dispatchers.IO) {
        try {
            dao.upsertPosts(listOf(api.getPost(id).toEntity()))
            dao.upsertComments(api.getComments(id).map { it.toEntity() })
            null
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) { CatalogApiError.Network }
        catch (e: HttpException) { CatalogApiError.Http(e.code()) }
        catch (_: Exception) { CatalogApiError.Parsing }
    }

    private fun CatalogPostDto.toEntity() = CatalogPostEntity(id, userId, title, body, System.currentTimeMillis())
    private fun CatalogPostEntity.toPost() = CatalogPost(userId, id, title, body)
    private fun CatalogCommentDto.toEntity() = CatalogCommentEntity(postId, id, name, email, body)
    private fun CatalogCommentEntity.toComment() = CatalogComment(postId, id, name, email, body)

    companion object { private const val PAGE_SIZE = 20 }
}
