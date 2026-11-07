package com.phonoteka.labs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ЛР5: категории для фильтра (усложнение 1). */
enum class Lab5Category(val label: String) { ALL("Все"), ROCK("Рок"), JAZZ("Джаз"), POP("Поп") }

data class Lab5Item(val id: Int, val title: String, val category: Lab5Category)

/** Источник данных с пагинацией page/pageSize. Всего [TOTAL] элементов. */
object Lab5Source {
    const val TOTAL = 200
    private val cats = listOf(Lab5Category.ROCK, Lab5Category.JAZZ, Lab5Category.POP)
    fun item(id: Int) = Lab5Item(id, "Трек №$id", cats[(id - 1) % cats.size])
    fun page(page: Int, pageSize: Int): List<Lab5Item> {
        val from = page * pageSize + 1
        val to = minOf(TOTAL, (page + 1) * pageSize)
        return if (from > to) emptyList() else (from..to).map(::item)
    }
}

data class Lab5State(
    val items: List<Lab5Item> = emptyList(),
    val loadedPages: Int = 0,
    val isLoading: Boolean = false,
    val endReached: Boolean = false,
    val category: Lab5Category = Lab5Category.ALL,
    val query: String = "",
    val appliedQuery: String = "",
    val visible: List<Lab5Item> = emptyList()
)

internal fun Lab5State.refilter(): Lab5State {
    val q = appliedQuery.trim()
    return copy(visible = items.filter {
        (category == Lab5Category.ALL || it.category == category) &&
            (q.isEmpty() || it.title.contains(q, ignoreCase = true))
    })
}

/**
 * ЛР5, усложнение 4: вся логика списка во ViewModel.
 * Старт — 100 элементов (5 страниц по 20), «Загрузить ещё» добавляет 20, после [Lab5Source.TOTAL] — «Больше нет данных».
 * Поиск применяется с debounce 300 мс (усложнение 3).
 */
@OptIn(FlowPreview::class)
class Lab5ListViewModel(
    private val loadDelayMs: Long = 600L,
    private val debounceMs: Long = DEBOUNCE_MS
) : ViewModel() {
    private val _state = MutableStateFlow(
        Lab5State(
            items = (0 until INITIAL_PAGES).flatMap { Lab5Source.page(it, PAGE_SIZE) },
            loadedPages = INITIAL_PAGES
        ).refilter()
    )
    val state: StateFlow<Lab5State> = _state.asStateFlow()
    private val queryInput = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryInput.drop(1).debounce(debounceMs).collect { q ->
                _state.update { it.copy(appliedQuery = q).refilter() }
            }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        queryInput.value = q
    }

    fun selectCategory(c: Lab5Category) = _state.update { it.copy(category = c).refilter() }

    /** Возвращает false, если запрос уже идёт или данные закончились (защита от дублей). */
    fun loadMore(): Boolean {
        val s = _state.value
        if (s.isLoading || s.endReached) return false
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            delay(loadDelayMs)
            _state.update {
                val next = Lab5Source.page(it.loadedPages, PAGE_SIZE)
                val total = it.items.size + next.size
                it.copy(
                    items = it.items + next,
                    loadedPages = it.loadedPages + 1,
                    isLoading = false,
                    endReached = next.size < PAGE_SIZE || total >= Lab5Source.TOTAL
                ).refilter()
            }
        }
        return true
    }

    companion object {
        const val PAGE_SIZE = 20
        const val INITIAL_PAGES = 5
        const val DEBOUNCE_MS = 300L
    }
}

/**
 * ЛР5: LazyColumn — заголовок, разделители, key + animateItem, индикатор вместо кнопки во время загрузки,
 * AnimatedVisibility, фильтр категорий и поиск. Позиция скролла сохраняется (rememberLazyListState — saveable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Lab5ListScreen(modifier: Modifier = Modifier, viewModel: Lab5ListViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query, onValueChange = viewModel::onQueryChange, singleLine = true,
            label = { Text("Поиск (debounce 300 мс)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("lab5Search")
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Lab5Category.entries.forEach { c ->
                FilterChip(selected = state.category == c, onClick = { viewModel.selectCategory(c) },
                    label = { Text(c.label) }, modifier = Modifier.testTag("lab5Cat_${c.name}"))
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("lab5List")) {
            item(key = "header") {
                Text(
                    "Фонотека: показано ${state.visible.size} из ${state.items.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp).testTag("lab5Header")
                )
            }
            itemsIndexed(state.visible, key = { _, item -> item.id }) { index, item ->
                Column(Modifier.animateItem()) {
                    if (index > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text(item.category.label) },
                        modifier = Modifier.testTag("lab5Item_${item.id}")
                    )
                }
            }
            item(key = "footer") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (state.isLoading) {
                        CircularProgressIndicator(Modifier.testTag("lab5Progress"))
                    }
                    if (!state.isLoading) {
                        if (state.endReached) {
                            Text("Больше нет данных", modifier = Modifier.testTag("lab5End"))
                        } else {
                            Button(onClick = { viewModel.loadMore() }, modifier = Modifier.testTag("lab5LoadMore")) {
                                Text("Загрузить ещё")
                            }
                        }
                    }
                }
            }
        }
    }
}
