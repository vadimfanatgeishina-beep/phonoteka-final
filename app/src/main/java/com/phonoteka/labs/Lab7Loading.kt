package com.phonoteka.labs

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

// ============================ Модель и источник данных ============================

/** ЛР7: три типа ошибок с разными сообщениями (иконки — в UI). */
enum class Lab7ErrorType(val message: String) {
    NO_NETWORK("Нет сети. Проверьте подключение к интернету."),
    TIMEOUT("Таймаут: сервер не ответил вовремя."),
    SERVER("Серверная ошибка (500). Попробуйте позже.")
}

class Lab7Exception(val type: Lab7ErrorType) : Exception(type.message)

data class Lab7Item(val id: Int, val title: String)

fun interface Lab7Source {
    suspend fun fetch(attempt: Int): List<Lab7Item>
}

/** Имитация сети: delay(800), 20% случайных ошибок трёх типов, иногда пустой ответ. */
class FakeNetworkSource(
    private val random: Random = Random.Default,
    private val delayMs: Long = 800L,
    private val errorRate: Double = 0.2,
    private val emptyRate: Double = 0.05
) : Lab7Source {
    override suspend fun fetch(attempt: Int): List<Lab7Item> {
        delay(delayMs)
        val r = random.nextDouble()
        if (r < errorRate) throw Lab7Exception(Lab7ErrorType.entries[random.nextInt(Lab7ErrorType.entries.size)])
        if (r < errorRate + emptyRate) return emptyList()
        return (1..30).map { Lab7Item(it, "Трек №$it · запрос #$attempt") }
    }
}

// ============================ Кэш (память + DataStore) ============================

data class Lab7Cached(val items: List<Lab7Item>, val cachedAt: Long)

interface Lab7Store {
    suspend fun read(): Lab7Cached?
    suspend fun write(value: Lab7Cached)
}

class InMemoryLab7Store(@Volatile private var value: Lab7Cached? = null) : Lab7Store {
    override suspend fun read() = value
    override suspend fun write(value: Lab7Cached) { this.value = value }
}

private val Context.lab7DataStore: DataStore<Preferences> by preferencesDataStore(name = "lab7_cache")

/** Усложнение 3: кэш в DataStore — данные доступны без интернета и после перезапуска. */
class DataStoreLab7Store(context: Context) : Lab7Store {
    private val store = context.applicationContext.lab7DataStore
    private val key = stringPreferencesKey("items_json")
    private val gson = Gson()
    private val memory = InMemoryLab7Store()
    override suspend fun read(): Lab7Cached? = memory.read() ?: runCatching {
        store.data.first()[key]?.let { gson.fromJson(it, Lab7Cached::class.java) }
    }.getOrNull()?.also { memory.write(it) }
    override suspend fun write(value: Lab7Cached) {
        memory.write(value)
        store.edit { it[key] = gson.toJson(value) }
    }
}

// ============================ Состояния UI ============================

sealed interface ListUiState {
    data object Loading : ListUiState
    /** Отдельное состояние обновления: список виден, поверх — полупрозрачный индикатор. */
    data class Refreshing(val items: List<Lab7Item>, val cachedAt: Long) : ListUiState
    data class Success(
        val items: List<Lab7Item>,
        val cachedAt: Long,
        val fromCache: Boolean = false,
        /** Ошибка фонового обновления: список из кэша остаётся, сообщение — в Snackbar. */
        val refreshError: Lab7ErrorType? = null
    ) : ListUiState
    data object Empty : ListUiState
    data class Error(
        val errorType: Lab7ErrorType,
        val retryCount: Int,
        val nextRetryDelayMs: Long,
        val retryAvailableAt: Long
    ) : ListUiState
}

private fun ListUiState.itemsOrNull(): List<Lab7Item>? = when (this) {
    is ListUiState.Success -> items
    is ListUiState.Refreshing -> items
    else -> null
}

// ============================ ViewModel ============================

/**
 * ЛР7: вся логика загрузки — здесь, UI только отображает [uiState].
 *  - один запрос одновременно ([isRequestActive]);
 *  - backoff: 1-й повтор сразу, 2-й через 1 с, 3-й — 2 с, 4-й — 4 с (максимум 8 с);
 *  - кэш с cachedAt: показывается сразу, обновление идёт в фоне; ошибка не стирает кэш;
 *  - поворот не повторяет запрос: загрузка запускается в init, а VM переживает пересоздание Activity;
 *  - аналитика: тип ошибки и число попыток пишутся в Logcat (тег Lab7List).
 */
class Lab7ListViewModel(
    private val source: Lab7Source,
    private val store: Lab7Store,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { runCatching { Log.d(TAG, it) } }
) : ViewModel() {
    private val _uiState = MutableStateFlow<ListUiState>(ListUiState.Loading)
    val uiState: StateFlow<ListUiState> = _uiState.asStateFlow()

    var requestCount = 0
        private set
    var isRequestActive = false
        private set
    private var retryCount = 0
    private var pendingRetry: Job? = null

    init {
        isRequestActive = true
        viewModelScope.launch {
            val cached = store.read()
            if (cached != null && cached.items.isNotEmpty()) {
                log("cache hit: ${cached.items.size} items, cachedAt=${cached.cachedAt}")
                _uiState.value = ListUiState.Success(cached.items, cached.cachedAt, fromCache = true)
            }
            isRequestActive = false
            loadItems()
        }
    }

    /** Запускает запрос. Возвращает false, если другой запрос уже выполняется. */
    fun loadItems(): Boolean {
        if (isRequestActive) { log("loadItems ignored: request already active"); return false }
        isRequestActive = true
        val shown = _uiState.value.itemsOrNull()
        val shownAt = (_uiState.value as? ListUiState.Success)?.cachedAt
            ?: (_uiState.value as? ListUiState.Refreshing)?.cachedAt ?: 0L
        _uiState.value = if (!shown.isNullOrEmpty()) ListUiState.Refreshing(shown, shownAt) else ListUiState.Loading
        requestCount++
        val attempt = requestCount
        log("request #$attempt started (refresh=${!shown.isNullOrEmpty()})")
        viewModelScope.launch {
            try {
                val items = source.fetch(attempt)
                retryCount = 0
                if (items.isEmpty()) {
                    _uiState.value = ListUiState.Empty
                } else {
                    val now = clock()
                    store.write(Lab7Cached(items, now))
                    _uiState.value = ListUiState.Success(items, now)
                }
                log("request #$attempt success: ${items.size} items")
            } catch (e: Lab7Exception) {
                retryCount++
                val delayMs = backoffDelayMs(retryCount)
                log("request #$attempt error type=${e.type} attempts=$retryCount nextRetryDelayMs=$delayMs")
                _uiState.value = if (!shown.isNullOrEmpty()) {
                    ListUiState.Success(shown, shownAt, fromCache = true, refreshError = e.type)
                } else {
                    ListUiState.Error(e.type, retryCount, delayMs, clock() + delayMs)
                }
            } finally {
                isRequestActive = false
            }
        }
        return true
    }

    /** Повтор с учётом backoff: если пауза ещё не истекла — запрос уйдёт после неё (ровно один). */
    fun retry(): Boolean {
        if (isRequestActive || pendingRetry?.isActive == true) { log("retry ignored: busy"); return false }
        val err = _uiState.value as? ListUiState.Error
        val wait = if (err == null) 0L else (err.retryAvailableAt - clock()).coerceAtLeast(0L)
        if (wait == 0L) return loadItems()
        log("retry scheduled in ${wait}ms")
        pendingRetry = viewModelScope.launch { delay(wait); loadItems() }
        return true
    }

    /** Pull-to-refresh и FAB «Обновить». */
    fun refresh(): Boolean = retry()

    companion object {
        const val TAG = "Lab7List"
        /** Задержка перед следующим повтором после [failures] неудач подряд: 0, 1000, 2000, 4000, 8000… */
        fun backoffDelayMs(failures: Int): Long =
            if (failures <= 1) 0L else minOf(8_000L, 1_000L shl (failures - 2))

        fun factory(context: Context, source: Lab7Source = FakeNetworkSource()) = viewModelFactory {
            initializer { Lab7ListViewModel(source, DataStoreLab7Store(context)) }
        }
    }
}

// ============================ UI ============================

private fun Lab7ErrorType.icon(): ImageVector = when (this) {
    Lab7ErrorType.NO_NETWORK -> Icons.Filled.CloudOff
    Lab7ErrorType.TIMEOUT -> Icons.Filled.HourglassEmpty
    Lab7ErrorType.SERVER -> Icons.Filled.Error
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Lab7ListScreen(viewModel: Lab7ListViewModel, modifier: Modifier = Modifier, now: () -> Long = System::currentTimeMillis) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val refreshError = (state as? ListUiState.Success)?.refreshError
    LaunchedEffect(refreshError, state) {
        if (refreshError != null) snackbar.showSnackbar(refreshError.message + " Показаны данные из кэша.")
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state is ListUiState.Success) {
                FloatingActionButton(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("lab7Refresh")) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ListUiState.Loading -> Box(Modifier.fillMaxSize().testTag("lab7Loading"), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ListUiState.Empty -> Column(Modifier.fillMaxSize().testTag("lab7Empty"),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp))
                    Text("Ничего не найдено")
                    Button(onClick = { viewModel.retry() }) { Text("Обновить") }
                }
                is ListUiState.Error -> Lab7ErrorContent(s, now) { viewModel.retry() }
                is ListUiState.Success, is ListUiState.Refreshing -> {
                    val items = if (s is ListUiState.Success) s.items else (s as ListUiState.Refreshing).items
                    val cachedAt = if (s is ListUiState.Success) s.cachedAt else (s as ListUiState.Refreshing).cachedAt
                    PullToRefreshBox(
                        isRefreshing = false,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(Modifier.fillMaxSize().testTag("lab7List")) {
                            item(key = "cached") {
                                val label = if (s is ListUiState.Success && s.fromCache) "из кэша" else "свежие"
                                Text("Данные $label · cachedAt=${java.text.DateFormat.getTimeInstance().format(java.util.Date(cachedAt))}",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                            }
                            items(items, key = { it.id }) { item ->
                                ListItem(headlineContent = { Text(item.title) }, modifier = Modifier.testTag("lab7Item_${item.id}"))
                                HorizontalDivider()
                            }
                        }
                    }
                    if (s is ListUiState.Refreshing) {
                        Box(
                            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).testTag("lab7Overlay"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text("Обновление…", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Lab7ErrorContent(s: ListUiState.Error, now: () -> Long, onRetry: () -> Unit) {
    var remaining by remember(s) { mutableLongStateOf((s.retryAvailableAt - now()).coerceAtLeast(0L)) }
    LaunchedEffect(s) {
        while (remaining > 0) {
            delay(200)
            remaining = (s.retryAvailableAt - now()).coerceAtLeast(0L)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("lab7Error_${s.errorType.name}"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(s.errorType.icon(), contentDescription = s.errorType.name, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(s.errorType.message, modifier = Modifier.testTag("lab7ErrorMessage"))
        Text("Попыток подряд: ${s.retryCount}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, enabled = remaining == 0L, modifier = Modifier.testTag("lab7Retry")) {
            Text(if (remaining == 0L) "Повторить" else "Повторить через ${(remaining + 999) / 1000} с")
        }
    }
}
