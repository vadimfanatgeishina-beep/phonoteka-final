package com.phonoteka.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogPostDao {
    @Query("SELECT * FROM catalog_posts WHERE title LIKE '%' || :query || '%' ORDER BY id")
    fun observePosts(query: String): Flow<List<CatalogPostEntity>>
    @Query("SELECT * FROM catalog_posts WHERE id = :id LIMIT 1")
    fun observePost(id: Int): Flow<CatalogPostEntity?>
    @Query("SELECT COUNT(*) FROM catalog_posts")
    suspend fun countPosts(): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosts(posts: List<CatalogPostEntity>)
    @Query("SELECT * FROM catalog_comments WHERE postId = :postId ORDER BY id")
    fun observeComments(postId: Int): Flow<List<CatalogCommentEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertComments(comments: List<CatalogCommentEntity>)
}
