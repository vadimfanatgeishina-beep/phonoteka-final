package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonoteka.viewmodel.PostCatalogViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Lab10PostDetailScreen(postId: Int, viewModel: PostCatalogViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    LaunchedEffect(postId) { viewModel.loadDetail(postId) }
    Scaffold(topBar = { TopAppBar(title = { Text("Пост $postId") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Назад") }
    }) }) { padding ->
        if (detail.loading && detail.post == null) Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        } else LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            detail.post?.let { post ->
                item { Text(post.title) }
                item { Text(post.body) }
            }
            if (detail.offline) item { Text("Офлайн · показан кэш") }
            detail.message?.let { item { Text(it) } }
            item { Text("Комментарии") }
            items(detail.comments, key = { it.id }) { comment ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(comment.name)
                    Text(comment.email)
                    Text(comment.body)
                }
            }
        }
    }
}
