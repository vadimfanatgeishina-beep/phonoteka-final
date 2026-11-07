package com.phonoteka.data

data class CatalogPost(val userId: Int, val id: Int, val title: String, val body: String)
data class CatalogComment(val postId: Int, val id: Int, val name: String, val email: String, val body: String)
