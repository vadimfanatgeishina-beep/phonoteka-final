package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import com.phonoteka.viewmodel.PostCatalogViewModel
import com.phonoteka.ui.components.MusicCover
import com.phonoteka.ui.components.albumArtworkFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Lab10PostDetailScreen(postId: Int, viewModel: PostCatalogViewModel, onBack: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    LaunchedEffect(postId) { viewModel.loadDetail(postId) }
    Scaffold(topBar = { TopAppBar(title = { Text("Музыкальная история") }, navigationIcon = {
        com.phonoteka.ui.components.AppBackTextButton(onBack)
    }) }) { padding ->
        if (detail.loading && detail.post == null) Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        } else LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            detail.post?.let { post ->
                item { MusicCover(post.id, Modifier.size(112.dp)) }
                item {
                    val album = albumArtworkFor(post.id)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(post.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("На обложке: ${album.artist} — ${album.album}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Text(post.body, style = MaterialTheme.typography.bodyLarge) }
            }
            if (detail.offline) item { Text("Офлайн · показан кэш") }
            detail.message?.let { item { Text(it) } }
            item { Text("Обсуждение · ${detail.comments.size}", style = MaterialTheme.typography.titleMedium) }
            items(detail.comments, key = { it.id }) { comment ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text(comment.name.takeLast(2).trim(), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(comment.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(comment.body, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
