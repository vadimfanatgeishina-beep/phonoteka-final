package com.phonoteka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.phonoteka.data.CatalogApiError
import com.phonoteka.data.CatalogComment
import com.phonoteka.data.CatalogPost
import com.phonoteka.data.PostCatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CatalogUiState {
    data object Loading : CatalogUiState
    data class Success(
        val posts: List<CatalogPost>,
        val query: String = "",
        val offline: Boolean = false,
        val refreshing: Boolean = false,
        val loadingMore: Boolean = false,
        val endReached: Boolean = false,
        val message: String? = null
    ) : CatalogUiState
    data class Error(val message: String) : CatalogUiState
}

data class CatalogDetailState(
    val post: CatalogPost? = null,
    val comments: List<CatalogComment> = emptyList(),
    val loading: Boolean = true,
    val offline: Boolean = false,
    val message: String? = null
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class PostCatalogViewModel(
    private val repository: PostCatalogRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {
    private val mutableState = MutableStateFlow<CatalogUiState>(CatalogUiState.Loading)
    val state: StateFlow<CatalogUiState> = mutableState.asStateFlow()
    private val mutableQuery = MutableStateFlow(savedStateHandle[QUERY_KEY] ?: "")
    val query: StateFlow<String> = mutableQuery.asStateFlow()
    private val mutableRetry = MutableStateFlow<String?>(null)
    val retryMessage = mutableRetry.asStateFlow()
    private var refreshJob: Job? = null
    private val mutableDetail = MutableStateFlow(CatalogDetailState())
    val detail: StateFlow<CatalogDetailState> = mutableDetail.asStateFlow()
    private var detailJob: Job? = null
    private var detailId: Int? = null
    private var detailGeneration = 0L

    init {
        viewModelScope.launch {
            mutableQuery.flatMapLatest { query ->
                flow {
                    if (query.isNotEmpty()) kotlinx.coroutines.delay(300)
                    emitAll(repository.observePosts(query))
                }
            }.collect { posts ->
                mutableState.update { old ->
                    val prior = old as? CatalogUiState.Success
                    val previousError = old as? CatalogUiState.Error
                    CatalogUiState.Success(posts, mutableQuery.value, prior?.offline ?: (previousError != null), prior?.refreshing ?: false,
                        prior?.loadingMore ?: false, prior?.endReached ?: false, prior?.message ?: previousError?.message)
                }
            }
        }
        refresh()
        savedStateHandle.get<Int>(DETAIL_ID_KEY)?.let { loadDetail(it) }
    }

    fun search(query: String) {
        savedStateHandle[QUERY_KEY] = query
        mutableQuery.value = query
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.update { old -> if (old is CatalogUiState.Success) old.copy(refreshing = true, message = null, endReached = false) else CatalogUiState.Loading }
            val failure = retryNetwork { repository.refresh() }
            val endReached = repository.isEndReached()
            if (failure != null) {
                mutableState.update { old ->
                    if (old is CatalogUiState.Success && old.posts.isNotEmpty()) old.copy(offline = true, refreshing = false, message = message(failure))
                    else CatalogUiState.Error(message(failure))
                }
            } else mutableState.update { old -> (old as? CatalogUiState.Success)?.copy(offline = false, refreshing = false, endReached = endReached, message = null) ?: old }
        }
    }
    private suspend fun retryNetwork(operation: suspend () -> CatalogApiError?): CatalogApiError? {
        try {
            for (attempt in 0..3) {
                val failure = operation()
                if (failure != CatalogApiError.Network || attempt == 3) return failure
                val seconds = 1L shl attempt
                mutableRetry.value = "Сетевая ошибка: повтор ${attempt + 1}/3 через $seconds с."
                kotlinx.coroutines.delay(seconds * 1000)
            }
            return CatalogApiError.Unknown
        } finally { mutableRetry.value = null }
    }

    fun loadNextPage() = viewModelScope.launch {
        val current = mutableState.value as? CatalogUiState.Success ?: return@launch
        if (current.loadingMore || current.endReached || current.refreshing) return@launch
        mutableState.update { (it as? CatalogUiState.Success)?.copy(loadingMore = true) ?: it }
        val failure = repository.loadNextPage()
        val endReached = repository.isEndReached()
        mutableState.update { old -> (old as? CatalogUiState.Success)?.copy(loadingMore = false,
            offline = failure != null, endReached = endReached,
            message = failure?.let(::message)) ?: old }
    }

    fun loadDetail(id: Int): Job {
        require(id > 0)
        detailJob?.takeIf { detailId == id && it.isActive }?.let { return it }
        detailJob?.cancel()
        detailId = id
        savedStateHandle[DETAIL_ID_KEY] = id
        val generation = ++detailGeneration
        mutableDetail.value = CatalogDetailState(loading = true)
        return viewModelScope.launch {
            try {
                coroutineScope {
                    // Keep observing Room after the network refresh, rather than taking one snapshot.
                    launch {
                        combine(repository.observePost(id), repository.observeComments(id)) { post, comments ->
                            post to comments
                        }.collect { (post, comments) ->
                            if (generation == detailGeneration) mutableDetail.update {
                                it.copy(post = post, comments = comments, loading = it.loading && post == null)
                            }
                        }
                    }
                    val failure = repository.refreshDetails(id)
                    ensureActive() // A non-cooperative old refresh may return after navigation changed.
                    if (generation == detailGeneration) mutableDetail.update {
                        it.copy(loading = false, offline = failure != null, message = failure?.let(::message))
                    }
                    // coroutineScope remains alive while its Room observer is active.
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == detailGeneration) mutableDetail.update {
                    it.copy(loading = false, offline = true, message = message(CatalogApiError.Unknown))
                }
            }
        }.also { detailJob = it }
    }

    private fun message(error: CatalogApiError): String = when (error) {
        CatalogApiError.Network -> "Нет сети. Показан сохранённый каталог, если он есть."
        is CatalogApiError.Http -> if (error.code >= 500) "Сервер временно недоступен." else "Ошибка HTTP ${error.code}."
        CatalogApiError.Parsing -> "Не удалось прочитать ответ сервера. Локальный кэш сохранён."
        CatalogApiError.Unknown -> "Не удалось загрузить каталог."
    }

    class Factory(private val repository: PostCatalogRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PostCatalogViewModel(repository) as T

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            PostCatalogViewModel(repository, runCatching { extras.createSavedStateHandle() }.getOrElse { SavedStateHandle() }) as T
    }

    private companion object {
        const val QUERY_KEY = "lab10.catalog.query"
        const val DETAIL_ID_KEY = "lab10.catalog.detailId"
    }
}
