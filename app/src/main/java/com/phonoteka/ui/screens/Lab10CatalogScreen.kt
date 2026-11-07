package com.phonoteka.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import com.phonoteka.viewmodel.CatalogUiState
import com.phonoteka.viewmodel.PostCatalogViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun Lab10CatalogScreen(viewModel: PostCatalogViewModel, onBack: () -> Unit, onPostClick: (Int) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchQuery by viewModel.query.collectAsStateWithLifecycle()
    val retryMessage by viewModel.retryMessage.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Каталог постов · ЛР10") }, actions = {
        IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Обновить каталог") }
    }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Назад") }
    }) }) { padding ->
        when (val current = state) {
            CatalogUiState.Loading -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) { CircularProgressIndicator(); Text(retryMessage ?: "Загрузка каталога…") }
            is CatalogUiState.Error -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::refresh) { Text("Повторить") }
            }
            is CatalogUiState.Success -> Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                val listState = rememberLazyListState()
                LaunchedEffect(listState, current.posts.size, current.loadingMore, current.endReached, current.query) {
                    if (current.query.isBlank() && !current.loadingMore && !current.endReached) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                            .distinctUntilChanged()
                            .collect { lastVisible -> if (lastVisible >= current.posts.size - 3) viewModel.loadNextPage() }
                    }
                }
                OutlinedTextField(value = searchQuery, onValueChange = viewModel::search, label = { Text("Поиск по заголовку") }, modifier = Modifier.fillMaxWidth())
                if (current.offline) Text("Офлайн · показываем сохранённые данные", color = MaterialTheme.colorScheme.tertiary)
                current.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (current.refreshing) Text(retryMessage ?: "Обновление…")
                if (current.posts.isEmpty() && current.query.isNotBlank()) Text("Ничего не найдено")
                PullToRefreshBox(isRefreshing = current.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(current.posts, key = { it.id }) { post ->
                            Card(Modifier.fillMaxWidth().clickable { onPostClick(post.id) }) {
                                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    val imageFallback = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                                    AsyncImage(model = "https://i.pravatar.cc/96?img=${post.userId}", contentDescription = "Аватар автора ${post.userId}",
                                        modifier = Modifier.padding(top = 4.dp).size(48.dp).clip(CircleShape), placeholder = imageFallback, error = imageFallback)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("#${post.id} · ${post.title}", style = MaterialTheme.typography.titleMedium)
                                        Text(post.body.lines().firstOrNull().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        if (!current.endReached && current.query.isBlank()) item {
                            TextButton(onClick = viewModel::loadNextPage, modifier = Modifier.fillMaxWidth()) {
                                Text(if (current.loadingMore) "Загрузка…" else "Загрузить следующую страницу")
                            }
                        }
                    }
                }
            }
        }
    }
}
