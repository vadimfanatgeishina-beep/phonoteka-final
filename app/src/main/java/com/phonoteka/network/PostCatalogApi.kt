package com.phonoteka.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class CatalogPostDto(
    @SerializedName("userId") val userId: Int,
    val id: Int,
    val title: String,
    val body: String
)

interface PostCatalogApi {
    @GET("posts")
    suspend fun getPosts(@Query("_start") start: Int = 0, @Query("_limit") limit: Int = 20): List<CatalogPostDto>

    @GET("posts/{id}")
    suspend fun getPost(@Path("id") id: Int): CatalogPostDto

    @GET("posts/{id}/comments")
    suspend fun getComments(@Path("id") id: Int): List<CatalogCommentDto>
}

data class CatalogCommentDto(val postId: Int, val id: Int, val name: String, val email: String, val body: String)

