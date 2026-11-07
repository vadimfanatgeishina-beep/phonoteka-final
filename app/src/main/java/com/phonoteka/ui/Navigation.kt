package com.phonoteka.ui

import android.app.Activity
import com.phonoteka.ui.screens.AllLabsMenu
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.phonoteka.data.PhonotekaRepository
import com.phonoteka.ui.components.MiniPlayer
import com.phonoteka.ui.screens.HomeScreen
import com.phonoteka.ui.screens.SplashScreen
import com.phonoteka.ui.screens.SettingsScreen
import com.phonoteka.ui.screens.GeneralSettingsScreen
import com.phonoteka.ui.screens.NotificationsSettingsScreen
import com.phonoteka.ui.screens.ThemeSettingsScreen
import com.phonoteka.ui.screens.TrackDetailScreen
import com.phonoteka.ui.screens.TrackListScreen
import com.phonoteka.ui.screens.Lab10CatalogScreen
import com.phonoteka.ui.screens.Lab10PostDetailScreen
import com.phonoteka.ui.screens.Lab11BackupScreen
import com.phonoteka.ui.screens.Lab14AccessScreen
import com.phonoteka.ui.screens.Lab13MockAuthScreen
import com.phonoteka.ui.screens.Lab13ResultsScreen
import com.phonoteka.viewmodel.PlayerViewModel
import com.phonoteka.viewmodel.SettingsViewModel
import com.phonoteka.viewmodel.TrackDetailViewModel
import com.phonoteka.viewmodel.TrackListViewModel
import com.phonoteka.viewmodel.PostCatalogViewModel
import com.phonoteka.security.LocalAuthManager
import com.phonoteka.security.PinResult
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

/**
 * Навигация приложения:
 *  - home — главный экран
 *  - trackList — список треков
 *  - trackDetail/{trackId} — детали трека (без внешнего deep link)
 *  - settings — настройки
 *
 * [TrackListViewModel] создаётся один раз здесь (activity scope) — инициализация
 * (импорт) запускается сразу на главном экране, а список использует тот же
 * экземпляр. [PlayerViewModel] тоже activity-scoped, мини-плеер рендерится один
 * раз поверх всех экранов.
 *
 * Дополнительно (Лаба 6):
 *  - [CompositionLocalProvider] предоставляет настройки всем экранам через
 *    [LocalSettings] — без явной передачи через параметры;
 *  - [BackHandler] на главном экране: двойное нажатие «назад» для выхода;
 *  - Анимации переходов: fade + slide между экранами.
 */
@Composable
fun Navigation(
    repository: PhonotekaRepository,
    settingsViewModel: SettingsViewModel,
    playerViewModel: PlayerViewModel,
    authManager: LocalAuthManager,
    onLock: () -> Unit = {}
) {
    val navController = rememberNavController()
    val settingsState by settingsViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val roleSession = (context.applicationContext as com.phonoteka.PhonotekaApp).roleSession
    val currentRole by roleSession.changes.collectAsState()
    val application = context.applicationContext as com.phonoteka.PhonotekaApp
    com.phonoteka.ui.components.RiskStepUpPrompt(application)
    androidx.compose.runtime.LaunchedEffect(currentRole) { application.clearStepUpSession() }
    // Один общий экземпляр списка на всё приложение.
    val trackListViewModel: TrackListViewModel = viewModel(
        factory = TrackListViewModel.Factory(repository)
    )

    androidx.compose.runtime.DisposableEffect(trackListViewModel) {
        onDispose { trackListViewModel.cancelBatch() }
    }

    // Наблюдаем текущий маршрут для условного BackHandler.
    val currentRoute by navController.currentBackStackEntryAsState()
    val isHome = currentRoute?.destination?.route == "home"
    val isSplash = currentRoute?.destination?.route == "splash"

    // Время последнего нажатия «назад» на главном экране — для двойного тапа.
    var lastBackPressMs by remember { mutableLongStateOf(0L) }
    val exitWindowMs = 2000L

    // BackHandler: активен только на главном экране — двойное нажатие для выхода.
    BackHandler(enabled = isHome) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs < exitWindowMs) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressMs = now
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Нажмите «назад» ещё раз, чтобы выйти",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    CompositionLocalProvider(LocalSettings provides settingsState,
        LocalDemoPermissions provides com.phonoteka.security.DemoRolePolicy.scopes(currentRole)) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            Box(modifier = Modifier.weight(1f)) {
                NavHost(
                    navController = navController,
                    startDestination = "splash",
                    // Анимации переходов (Лаба 6): fade + slide
                    enterTransition = {
                        fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 4 }
                    },
                    exitTransition = {
                        fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 8 }
                    },
                    popEnterTransition = {
                        fadeIn(tween(250)) + slideInHorizontally(tween(250)) { -it / 8 }
                    },
                    popExitTransition = {
                        fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 4 }
                    }
                ) {
                    // Заставка при запуске
                    composable("splash") {
                        SplashScreen(
                            onNavigateToHome = {
                                navController.navigate("home") {
                                    popUpTo("splash") { inclusive = true }
                                }
                            }
                        )
                    }

                    // Главный экран
                    composable("home") {
                        val doneCount by trackListViewModel.doneCount.collectAsState(initial = 0)
                        val totalCount by trackListViewModel.totalCount.collectAsState(initial = 0)
                        val errorCount by trackListViewModel.errorCount.collectAsState(initial = 0)
                        val reviewCount by trackListViewModel.reviewCount.collectAsState(initial = 0)
                        val labDeepLink by com.phonoteka.labs.LabDeepLinks.pendingDetailsId.collectAsState()
                        androidx.compose.runtime.LaunchedEffect(labDeepLink) {
                            if (labDeepLink != null) navController.navigate("settings/labs1to7") { launchSingleTop = true }
                        }

                        androidx.compose.foundation.layout.Box {
                        HomeScreen(
                            doneCount = doneCount,
                            totalCount = totalCount,
                            errorCount = errorCount,
                            reviewCount = reviewCount,
                            onNavigateToList = { navController.navigate("trackList") },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToTheme = { navController.navigate("settings/theme") }
                        )
                        // ⋮ → «О версии»: сводка по всем 16 лабам
                        AllLabsMenu(onAbout = { navController.navigate("about") })
                        }
                    }
                    composable("about") {
                        com.phonoteka.ui.screens.AllLabsScreen(onBack = { navController.popBackStack() })
                    }

                    // Список треков (тот же общий ViewModel)
                    composable("trackList") {
                        TrackListScreen(
                            viewModel = trackListViewModel,
                            onTrackClick = { trackId ->
                                navController.navigate("trackDetail/$trackId")
                            },
                            onNavigateToSettings = { navController.navigate("settings") }
                        )
                    }

                    // Детали трека (только внутренняя навигация; внешний deep link удалён в ЛР14)
                    composable(
                        route = "trackDetail/{trackId}",
                        arguments = listOf(
                            navArgument("trackId") { type = NavType.LongType }
                        )
                    ) { backStackEntry ->
                        val trackId = backStackEntry.arguments?.getLong("trackId") ?: 0L
                        var stepUpPin by remember(trackId) { mutableStateOf("") }
                        var stepUpGranted by remember(trackId) { mutableStateOf(authManager.isFreshAuthentication()) }
                        if (!stepUpGranted) {
                            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                Text("Для просмотра деталей трека требуется свежая аутентификация (step-up).")
                                OutlinedTextField(value = stepUpPin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) stepUpPin = it },
                                    label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                                Button(onClick = {
                                    stepUpGranted = authManager.verifyPin(stepUpPin) == PinResult.SUCCESS
                                    stepUpPin = ""
                                }, enabled = stepUpPin.length in 4..6) { Text("Подтвердить") }
                                TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                            }
                        } else {
                            val viewModel: TrackDetailViewModel = viewModel(factory = TrackDetailViewModel.Factory(repository, trackId))
                            if (com.phonoteka.security.DemoScope.LIBRARY_WRITE in LocalDemoPermissions.current) {
                            TrackDetailScreen(viewModel = viewModel, onBack = { navController.popBackStack() },
                                onPlay = { track -> playerViewModel.play(track, trackListViewModel.playbackCatalogue()) })
                            } else {
                                val detail by viewModel.uiState.collectAsState()
                                Column(Modifier.padding(24.dp)) {
                                    Text("Режим чтения: изменение и скачивание недоступны этой роли")
                                    (detail as? com.phonoteka.viewmodel.DetailUiState.Success)?.track?.let { Text(it.displayName) }
                                    TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                                }
                            }
                        }
                    }

                    // Настройки (Лаба 6: вложенная навигация)
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToSubScreen = { route -> navController.navigate(route) }
                        )
                    }

                    // Вложенные подэкраны настроек (Лаба 6: nested navigation)
                    composable("settings/general") {
                        GeneralSettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/notifications") {
                        NotificationsSettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/theme") {
                        ThemeSettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings/lab10") {
                        val catalogViewModel: PostCatalogViewModel = viewModel(factory = PostCatalogViewModel.Factory((context.applicationContext as com.phonoteka.PhonotekaApp).catalogRepository))
                        Lab10CatalogScreen(catalogViewModel, onBack = { navController.popBackStack() }, onPostClick = { id -> navController.navigate("settings/lab10/post/$id") })
                    }
                    composable("settings/lab10/post/{postId}", arguments = listOf(navArgument("postId") { type = NavType.IntType })) { entry ->
                        val id = entry.arguments?.getInt("postId") ?: return@composable
                        val catalogViewModel: PostCatalogViewModel = viewModel(factory = PostCatalogViewModel.Factory((context.applicationContext as com.phonoteka.PhonotekaApp).catalogRepository))
                        Lab10PostDetailScreen(id, catalogViewModel, onBack = { navController.popBackStack() })
                    }
                    composable("settings/lab11") {
                        Lab11BackupScreen(repository, onBack = { navController.popBackStack() }, onNotes = { navController.navigate("settings/lab11/notes") })
                    }
                    composable("settings/lab11/free-notes") {
                        val ctx = context.applicationContext
                        val freeNotes: com.phonoteka.viewmodel.FreeNotesViewModel = viewModel(factory = com.phonoteka.viewmodel.FreeNotesViewModel.Factory(
                            com.phonoteka.data.freenotes.FreeNotesRepository(java.io.File(ctx.filesDir, "notes.json")),
                            com.phonoteka.data.freenotes.AndroidFreeNotesDocuments(ctx.contentResolver)))
                        com.phonoteka.ui.screens.FreeNotesScreen(freeNotes, onBack = { navController.popBackStack() })
                    }
                    composable("settings/lab11/notes") {
                        val app = context.applicationContext as com.phonoteka.PhonotekaApp
                        val notes: com.phonoteka.viewmodel.TrackNotesViewModel = viewModel(factory = com.phonoteka.viewmodel.TrackNotesViewModel.Factory(app.trackNotes))
                        com.phonoteka.ui.screens.TrackNotesScreen(notes, onBack = { navController.popBackStack() })
                    }
                    composable("settings/lab13/results") {
                        Lab13ResultsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("settings/lab13") {
                        val app = context.applicationContext as com.phonoteka.PhonotekaApp
                        Lab13MockAuthScreen(app.lab13MockSession, onBack = { navController.popBackStack() }, onLogout = { trackListViewModel.cancelBatch(); onLock() })
                    }
                    composable("settings/lab9-measure") { com.phonoteka.ui.screens.Lab9MeasurementsScreen(onBack = { navController.popBackStack() }) }
                    composable("settings/lab15") { com.phonoteka.ui.screens.Lab15LiveScreen(onBack = { navController.popBackStack() }) }
                    composable("settings/lab14") {
                        Lab14AccessScreen(onBack = { navController.popBackStack() })
                    }
                    composable("settings/profile") {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val profileViewModel: com.phonoteka.viewmodel.ProfileViewModel = viewModel(
                            factory = com.phonoteka.viewmodel.ProfileViewModel.Factory(
                                com.phonoteka.viewmodel.DataStoreProfileStore.from(ctx)))
                        val total by trackListViewModel.totalCount.collectAsState(initial = 0)
                        val done by trackListViewModel.doneCount.collectAsState(initial = 0)
                        val review by trackListViewModel.reviewCount.collectAsState(initial = 0)
                        com.phonoteka.ui.screens.ProfileScreen(
                            viewModel = profileViewModel,
                            stats = com.phonoteka.ui.screens.ProfileStats(total, done, review),
                            themeMode = settingsState.themeMode,
                            forceDark = settingsState.forceDarkContent,
                            onThemeMode = settingsViewModel::setThemeMode,
                            onForceDark = settingsViewModel::setForceDarkContent,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    // ЛР1–7: отдельный раздел со своим NavHost (см. labs/LabsHub.kt)
                    composable("settings/labs1to7") {
                        com.phonoteka.labs.LabsHubScreen(onExit = { navController.popBackStack() })
                    }
                    composable("settings/tasks") {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val taskViewModel: com.phonoteka.viewmodel.TaskTrackerViewModel = viewModel(
                            factory = com.phonoteka.viewmodel.TaskTrackerViewModel.Factory(
                                com.phonoteka.tasks.TaskFileStore(java.io.File(ctx.filesDir, "tasks_lab9.txt"))))
                        com.phonoteka.ui.screens.TaskTrackerScreen(
                            viewModel = taskViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }

            // Snackbar для сообщения о выходе
            if (!isSplash) {
                SnackbarHost(hostState = snackbarHostState, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }

            // Мини-плеер — один раз, поверх всех экранов (кроме заставки).
            if (!isSplash) {
                MiniPlayerHost(
                    playerViewModel = playerViewModel,
                    modifier = Modifier
                )
            }
        }
    }
}

/**
 * Изолирует чтение состояния плеера, чтобы обновления позиции
 * не перерисовывали NavHost и экраны.
 */
@Composable
private fun MiniPlayerHost(
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val playerState by playerViewModel.uiState.collectAsState()

    MiniPlayer(
        state = playerState,
        onPlayPause = { playerViewModel.togglePlayPause() },
        onSeek = { positionMs -> playerViewModel.seekTo(positionMs) },
        onNext = { playerViewModel.next() },
        onDismissError = { playerViewModel.clearError() },
        onRetry = { playerViewModel.retryConnect() },
        modifier = modifier
    )
}
