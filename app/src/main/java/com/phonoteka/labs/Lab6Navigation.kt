package com.phonoteka.labs

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ============================ Данные ЛР6 ============================

data class LabArticle(val id: Int, val title: String, val description: String, val category: String, val favorite: Boolean)

object LabArticles {
    val categories = listOf("all", "news", "reviews", "guides")
    val all: List<LabArticle> = (1..20).map { id ->
        LabArticle(
            id = id,
            title = "Статья №$id",
            description = "Подробное описание статьи №$id о музыке.",
            category = categories[1 + id % 3],
            favorite = id % 4 == 0
        )
    }
    fun find(id: Int?): LabArticle? = all.firstOrNull { it.id == id }
}

/** Сложный аргумент (усложнение «строка/JSON вместо Int»): откуда пришли на «Детали». */
data class DetailsRef(val source: String, val category: String)

object Lab6Routes {
    const val HOME = "home"
    const val LIST = "list?category={category}&favorites={favorites}"
    const val DETAILS = "details/{itemId}?ref={ref}"
    const val SETTINGS = "settings"
    const val DEEP_LINK = "app://phonoteka/details/{itemId}"
    fun list(category: String = "all", favorites: Boolean = false) = "list?category=$category&favorites=$favorites"
    fun details(id: Any, ref: DetailsRef? = null): String =
        if (ref == null) "details/$id" else "details/$id?ref=" + Uri.encode(Gson().toJson(ref))
}

// ============================ Настройки (глобальное состояние) ============================

data class LabSettingsState(val darkMode: Boolean = false, val showHints: Boolean = true, val notifications: Boolean = true)

class LabSettingsViewModel : ViewModel() {
    private val _state = MutableStateFlow(LabSettingsState())
    val state: StateFlow<LabSettingsState> = _state.asStateFlow()
    fun setDarkMode(v: Boolean) = _state.update { it.copy(darkMode = v) }
    fun setShowHints(v: Boolean) = _state.update { it.copy(showHints = v) }
    fun setNotifications(v: Boolean) = _state.update { it.copy(notifications = v) }
}

val LocalLabSettings = staticCompositionLocalOf<LabSettingsState> { error("No LabSettingsState provided") }

@Composable
fun ProvideLabSettings(viewModel: LabSettingsViewModel, content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalLabSettings provides state) { content() }
}

// ============================ ViewModel списка и деталей ============================

data class LabListUiState(
    val isLoading: Boolean = true,
    val category: String = "all",
    val favoritesOnly: Boolean = false,
    val items: List<LabArticle> = emptyList()
)

/** Читает query-параметры category/favorites из маршрута через SavedStateHandle. */
class LabListViewModel(handle: SavedStateHandle, private val loadDelayMs: Long = 300L) : ViewModel() {
    private val _state = MutableStateFlow(
        LabListUiState(
            category = handle.get<String>("category")?.takeIf { it in LabArticles.categories } ?: "all",
            favoritesOnly = handle.get<Boolean>("favorites") ?: false
        )
    )
    val state: StateFlow<LabListUiState> = _state.asStateFlow()
    var loadCount = 0
        private set

    init { load() }

    private fun load() {
        loadCount++
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            delay(loadDelayMs)
            _state.update { s -> s.copy(isLoading = false, items = filter(s.category, s.favoritesOnly)) }
        }
    }

    fun setCategory(c: String) = _state.update { it.copy(category = c, items = filter(c, it.favoritesOnly)) }
    fun setFavoritesOnly(v: Boolean) = _state.update { it.copy(favoritesOnly = v, items = filter(it.category, v)) }

    private fun filter(category: String, fav: Boolean) =
        LabArticles.all.filter { (category == "all" || it.category == category) && (!fav || it.favorite) }
}

sealed interface LabDetailsUiState {
    data object Loading : LabDetailsUiState
    data class Found(val article: LabArticle, val ref: DetailsRef?) : LabDetailsUiState
    data class NotFound(val rawId: String?) : LabDetailsUiState
}

/**
 * Извлекает itemId (строка → валидация) и JSON-аргумент ref. Загрузка выполняется один раз на экземпляр VM,
 * поэтому поворот не запускает повторный запрос; при popBackStack VM уничтожается — состояние очищается.
 */
class LabDetailsViewModel(handle: SavedStateHandle, private val loadDelayMs: Long = 300L) : ViewModel() {
    private val rawId: String? = handle.get<String>("itemId")
    private val ref: DetailsRef? = handle.get<String>("ref")?.let {
        runCatching { Gson().fromJson(it, DetailsRef::class.java) }.getOrNull()
    }
    private val _state = MutableStateFlow<LabDetailsUiState>(LabDetailsUiState.Loading)
    val state: StateFlow<LabDetailsUiState> = _state.asStateFlow()
    var loadCount = 0
        private set

    init {
        loadCount++
        viewModelScope.launch {
            delay(loadDelayMs)
            val article = LabArticles.find(rawId?.toIntOrNull())
            _state.value = if (article == null) LabDetailsUiState.NotFound(rawId) else LabDetailsUiState.Found(article, ref)
        }
    }
}

// ============================ Навигационный граф ============================

/**
 * ЛР6: 4 экрана (Главная, Список, Детали, Настройки со вложенным графом), fade+slide анимации,
 * deep link app://phonoteka/details/{itemId} с восстановлением стека Главная → Список → Детали.
 */
@Composable
fun Lab6App(
    modifier: Modifier = Modifier,
    deepLinkItemId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    onExit: () -> Unit = {},
    navController: NavHostController = rememberNavController()
) {
    val settingsViewModel: LabSettingsViewModel = viewModel()
    ProvideLabSettings(settingsViewModel) {
        val settings = LocalLabSettings.current
        MaterialTheme(colorScheme = if (settings.darkMode) darkColorScheme() else lightColorScheme()) {
            Surface(modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Lab6Routes.HOME,
                    enterTransition = { fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 4 } },
                    exitTransition = { fadeOut(tween(200)) },
                    popEnterTransition = { fadeIn(tween(250)) },
                    popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 4 } }
                ) {
                    composable(Lab6Routes.HOME) {
                        Lab6HomeScreen(
                            onList = { navController.navigate(Lab6Routes.list()) },
                            onFavorites = { navController.navigate(Lab6Routes.list(favorites = true)) },
                            onNews = { navController.navigate(Lab6Routes.list(category = "news")) },
                            onSettings = { navController.navigate(Lab6Routes.SETTINGS) },
                            onExit = onExit
                        )
                    }
                    composable(
                        Lab6Routes.LIST,
                        arguments = listOf(
                            navArgument("category") { type = NavType.StringType; defaultValue = "all" },
                            navArgument("favorites") { type = NavType.BoolType; defaultValue = false }
                        )
                    ) {
                        val vm: LabListViewModel = viewModel(factory = viewModelFactory {
                            initializer { LabListViewModel(createSavedStateHandle()) }
                        })
                        Lab6ListScreen(vm,
                            onOpen = { a, c -> navController.navigate(Lab6Routes.details(a.id, DetailsRef("list", c))) },
                            onBack = { navController.popBackStack() })
                    }
                    composable(
                        Lab6Routes.DETAILS,
                        arguments = listOf(
                            navArgument("itemId") { type = NavType.StringType },
                            navArgument("ref") { type = NavType.StringType; nullable = true; defaultValue = null }
                        ),
                        deepLinks = listOf(navDeepLink { uriPattern = Lab6Routes.DEEP_LINK })
                    ) {
                        val vm: LabDetailsViewModel = viewModel(factory = viewModelFactory {
                            initializer { LabDetailsViewModel(createSavedStateHandle()) }
                        })
                        Lab6DetailsScreen(vm, onBack = { navController.popBackStack() })
                    }
                    navigation(startDestination = "settings/main", route = Lab6Routes.SETTINGS) {
                        composable("settings/main") {
                            Lab6SettingsMain(
                                onOpen = { navController.navigate(it) },
                                onBack = { navController.popBackStack() })
                        }
                        composable("settings/general") {
                            Lab6GeneralSettings(settingsViewModel, onBack = { navController.popBackStack() })
                        }
                        composable("settings/notifications") {
                            Lab6ToggleSettings("Уведомления", "Показывать уведомления",
                                LocalLabSettings.current.notifications, settingsViewModel::setNotifications) { navController.popBackStack() }
                        }
                        composable("settings/theme") {
                            Lab6ToggleSettings("Тема", "Тёмная тема",
                                LocalLabSettings.current.darkMode, settingsViewModel::setDarkMode) { navController.popBackStack() }
                        }
                    }
                }
            }
        }
    }
    // Deep link: синтетический стек Главная → Список → Детали (стартовая точка уже Главная).
    LaunchedEffect(deepLinkItemId) {
        val id = deepLinkItemId ?: return@LaunchedEffect
        navController.popBackStack(Lab6Routes.HOME, inclusive = false)
        navController.navigate(Lab6Routes.list())
        navController.navigate(Lab6Routes.details(id, DetailsRef("deeplink", "all")))
        onDeepLinkConsumed()
    }
}

@Composable
private fun Lab6Hint(text: String) {
    if (LocalLabSettings.current.showHints) {
        Text("💡 $text", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("lab6Hint"))
    }
}

@Composable
private fun Lab6Header(title: String, onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.testTag("lab6Back")) { Text("← Назад") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp).testTag("lab6Title"))
    }
}

@Composable
fun Lab6HomeScreen(onList: () -> Unit, onFavorites: () -> Unit, onNews: () -> Unit, onSettings: () -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Lab6Header("Главная", null)
        Lab6Hint("Выберите раздел. Подсказки отключаются в Настройках.")
        Button(onClick = onList, modifier = Modifier.fillMaxWidth().testTag("lab6ToList")) { Text("Перейти к списку") }
        OutlinedButton(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("Избранные (list?favorites=true)") }
        OutlinedButton(onClick = onNews, modifier = Modifier.fillMaxWidth()) { Text("Новости (list?category=news)") }
        Button(onClick = onSettings, modifier = Modifier.fillMaxWidth().testTag("lab6ToSettings")) { Text("Настройки") }
        TextButton(onClick = onExit) { Text("Выйти из ЛР6") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Lab6ListScreen(vm: LabListViewModel, onOpen: (LabArticle, String) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Lab6Header("Список", onBack)
        Column(Modifier.padding(horizontal = 16.dp)) { Lab6Hint("Нажмите на статью, чтобы открыть детали.") }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LabArticles.categories.forEach { c ->
                FilterChip(selected = state.category == c, onClick = { vm.setCategory(c) }, label = { Text(c) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Только избранные", Modifier.weight(1f))
            Switch(checked = state.favoritesOnly, onCheckedChange = vm::setFavoritesOnly, modifier = Modifier.testTag("lab6FavSwitch"))
        }
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("lab6List")) {
                items(state.items, key = { it.id }) { a ->
                    ListItem(
                        headlineContent = { Text(a.title) },
                        supportingContent = { Text(a.category + if (a.favorite) " · ★" else "") },
                        modifier = Modifier.clickable { onOpen(a, state.category) }.testTag("lab6Item_${a.id}")
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun Lab6DetailsScreen(vm: LabDetailsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Lab6Header("Детали", onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = state) {
                LabDetailsUiState.Loading -> CircularProgressIndicator()
                is LabDetailsUiState.NotFound -> {
                    Text("Элемент не найден", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("lab6NotFound"))
                    Text("ID «${s.rawId}» отсутствует в каталоге (1–${LabArticles.all.size}).")
                    Button(onClick = onBack) { Text("Назад") }
                }
                is LabDetailsUiState.Found -> {
                    Text(s.article.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("lab6DetailsTitle"))
                    Text(s.article.description)
                    Text("Категория: ${s.article.category}" + if (s.article.favorite) " · избранное" else "")
                    s.ref?.let { Text("Откуда пришли (JSON-аргумент): ${it.source}, фильтр ${it.category}") }
                    Lab6Hint("Кнопка «Назад» выполняет popBackStack.")
                    Button(onClick = onBack) { Text("Назад") }
                }
            }
        }
    }
}

@Composable
private fun Lab6SettingsMain(onOpen: (String) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Lab6Header("Настройки", onBack)
        listOf("settings/general" to "Общие", "settings/notifications" to "Уведомления", "settings/theme" to "Тема").forEach { (route, title) ->
            ListItem(headlineContent = { Text(title) },
                modifier = Modifier.clickable { onOpen(route) }.testTag("lab6Settings_$title"))
            HorizontalDivider()
        }
    }
}

/** «Общие»: черновик настроек + BackHandler с подтверждением при несохранённых изменениях. */
@Composable
private fun Lab6GeneralSettings(vm: LabSettingsViewModel, onBack: () -> Unit) {
    val saved = LocalLabSettings.current
    var hints by rememberSaveable { mutableStateOf(saved.showHints) }
    var dark by rememberSaveable { mutableStateOf(saved.darkMode) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    val dirty = hints != saved.showHints || dark != saved.darkMode
    BackHandler(enabled = dirty) { confirmExit = true }
    Column(Modifier.fillMaxSize()) {
        Lab6Header("Общие", onBack = { if (dirty) confirmExit = true else onBack() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Показывать подсказки на других экранах", Modifier.weight(1f))
                Switch(checked = hints, onCheckedChange = { hints = it }, modifier = Modifier.testTag("lab6HintsSwitch"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Тёмная тема", Modifier.weight(1f))
                Switch(checked = dark, onCheckedChange = { dark = it })
            }
            Button(onClick = { vm.setShowHints(hints); vm.setDarkMode(dark) }, enabled = dirty,
                modifier = Modifier.testTag("lab6SaveSettings")) { Text("Сохранить") }
        }
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Несохранённые изменения") },
            text = { Text("Выйти без сохранения?") },
            confirmButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Остаться") } }
        )
    }
}

@Composable
private fun Lab6ToggleSettings(title: String, label: String, value: Boolean, onChange: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Lab6Header(title, onBack)
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}
