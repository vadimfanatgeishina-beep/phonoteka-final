package com.phonoteka.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_posts")
data class CatalogPostEntity(@PrimaryKey val id: Int, val userId: Int, val title: String, val body: String, val fetchedAt: Long)

@Entity(tableName = "catalog_comments", primaryKeys = ["postId", "id"])
data class CatalogCommentEntity(val postId: Int, val id: Int, val name: String, val email: String, val body: String)
