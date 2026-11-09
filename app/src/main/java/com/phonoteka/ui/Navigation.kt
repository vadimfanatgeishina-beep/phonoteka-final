package com.phonoteka.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.phonoteka.data.PhonotekaRepository
import com.phonoteka.security.LocalAuthManager
import com.phonoteka.security.PinResult
import com.phonoteka.ui.components.MiniPlayer
import com.phonoteka.ui.screens.AllLabsScreen
import com.phonoteka.ui.screens.FreeNotesScreen
import com.phonoteka.ui.screens.GeneralSettingsScreen
import com.phonoteka.ui.screens.Lab10CatalogScreen
import com.phonoteka.ui.screens.Lab10PostDetailScreen
import com.phonoteka.ui.screens.Lab11BackupScreen
import com.phonoteka.ui.screens.Lab13MockAuthScreen
import com.phonoteka.ui.screens.Lab13ResultsScreen
import com.phonoteka.ui.screens.AuthAuditScreen
import com.phonoteka.ui.screens.Lab14AccessScreen
import com.phonoteka.ui.screens.NotificationsSettingsScreen
import com.phonoteka.ui.screens.SettingsScreen
import com.phonoteka.ui.screens.SplashScreen
import com.phonoteka.ui.screens.ThemeSettingsScreen
import com.phonoteka.ui.screens.TrackDetailScreen
import com.phonoteka.ui.screens.TrackListScreen
import com.phonoteka.ui.screens.TrackNotesScreen
import com.phonoteka.viewmodel.PlayerViewModel
import com.phonoteka.viewmodel.PostCatalogViewModel
import com.phonoteka.viewmodel.SettingsViewModel
import com.phonoteka.viewmodel.TrackDetailViewModel
import com.phonoteka.viewmodel.TrackListViewModel
import kotlinx.coroutines.delay

/**
 * Единая навигация Фонотеки.
 *
 * Архитектура:
 *  - шесть основных разделов (Главная, Каталог, Заметки, Профиль, Журнал, Настройки) — это
 *    пейджер с горизонтальными свайпами; у каждого раздела СВОЙ back stack,
 *    поэтому свайп не ломает историю переходов внутри раздела;
 *  - нижняя панель всегда показывает заметную кнопку «Домой» — она возвращает
 *    именно на главную Фонотеки из любого раздела и любого экрана деталей;
 *  - лабораторные сценарии (ЛР9–ЛР16) открываются В КОНТЕКСТЕ раздела:
 *    профиль → вход/PIN/QR и роли, заметки → заметки и архив, каталог → посты,
 *    журнал → живой WSS и аудит. Отдельного «лабораторного приложения» в меню нет.
 */
@Composable
fun Navigation(
    repository: PhonotekaRepository,
    settingsViewModel: SettingsViewModel,
    playerViewModel: PlayerViewModel,
    authManager: LocalAuthManager,
    onLock: () -> Unit = {}
) {
    val context = LocalContext.current
    val application = context.applicationContext as com.phonoteka.PhonotekaApp
    val settingsState by settingsViewModel.state.collectAsState()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val roleSession = application.roleSession
    val currentRole by roleSession.changes.collectAsState()
    com.phonoteka.ui.components.RiskStepUpPrompt(application)
    LaunchedEffect(currentRole) { application.clearStepUpSession() }

    // Один общий экземпляр списка на всё приложение — данные разделов согласованы.
    val trackListViewModel: TrackListViewModel = viewModel(factory = TrackListViewModel.Factory(repository))
    androidx.compose.runtime.DisposableEffect(trackListViewModel) {
        onDispose { trackListViewModel.cancelBatch() }
    }

    // Back stack каждого раздела живёт в собственном контроллере.
    // rememberNavController installs ComposeNavigator/ComposeNavGraphNavigator.
    // Bare NavHostController(context) crashes as soon as NavHost builds a composable route.
    val sectionControllers = listOf(
        rememberNavController(), rememberNavController(), rememberNavController(),
        rememberNavController(), rememberNavController()
    )

    var splashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(600)
        splashVisible = false
    }

    val homeIndex = MainSection.ORDER.indexOf(MainSection.HOME)
    var currentSection by rememberSaveable { mutableIntStateOf(homeIndex) }
    // Старый индекс из сохранённого состояния мог относиться к удалённой вкладке профиля.
    if (currentSection !in MainSection.ORDER.indices) currentSection = homeIndex

    // Маршрут, который надо открыть в ДРУГОМ разделе: сначала переключаем раздел,
    // потом экран появляется уже внутри него (одна история, лабораторные не отдельное приложение).
    var pendingRoute by remember { mutableStateOf<Pair<Int, String>?>(null) }

    /**
     * Переход по «настоящему» адресу приложения. Настройки хранят старые адреса
     * вида settings/lab13 — они разрешаются в раздел-владелец, и мы переключаем пейджер.
     */
    val globalNavigate: (String) -> Unit = { route ->
        val target = AppRoutes.resolve(route)
        if (target == null) {
            // Внутренний маршрут текущего раздела (настройки, тема, уведомления).
            sectionControllers[currentSection].navigate(route)
        } else {
            val targetIndex = MainSection.ORDER.indexOf(target.section)
            if (targetIndex == currentSection) {
                sectionControllers[targetIndex].navigate(target.route)
            } else {
                currentSection = targetIndex
                pendingRoute = targetIndex to target.route
            }
        }
    }

    CompositionLocalProvider(
        LocalSettings provides settingsState,
        com.phonoteka.ui.components.LocalEmbeddedBackVisible provides false,
        LocalDemoPermissions provides com.phonoteka.security.DemoRolePolicy.scopes(currentRole)
    ) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            if (!settingsState.gestureOnlyBack && !splashVisible) {
                Spacer(Modifier.height(32.dp))
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    IconButton(onClick = { backDispatcher?.onBackPressed() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                if (splashVisible) {
                    SplashScreen(onNavigateToHome = { splashVisible = false })
                } else {
                    MainSectionsShell(
                        selectedSection = currentSection,
                        onSectionSelected = { currentSection = it },
                        onHomeClicked = {
                            currentSection = homeIndex
                            val controller = sectionControllers[homeIndex]
                            controller.popBackStack(controller.graph.startDestinationId, false)
                        }
                    ) { section ->
                        val index = MainSection.ORDER.indexOf(section)
                        SectionNavHost(
                            controller = sectionControllers[index],
                            section = section,
                            repository = repository,
                            settingsViewModel = settingsViewModel,
                            playerViewModel = playerViewModel,
                            trackListViewModel = trackListViewModel,
                            authManager = authManager,
                            application = application,
                            onLock = onLock,
                            onNavigateToSection = { target -> currentSection = MainSection.ORDER.indexOf(target) },
                            onGlobalNavigate = globalNavigate,
                            onBack = { sectionControllers[index].popBackStack() }
                        )
                        // Отложенный маршрут: экран открываем уже внутри целевого раздела.
                        val pending = pendingRoute
                        if (pending != null && pending.first == index) {
                            LaunchedEffect(pending.second) {
                                sectionControllers[index].navigate(pending.second)
                                pendingRoute = null
                            }
                        }
                    }
                }
            }

            if (!splashVisible) {
                MiniPlayerHost(playerViewModel = playerViewModel)
            }
        }
    }
}

/**
 * Контроллер навигации текущего раздела: экраны разделов ходят по нему,
 * не зная о деталях реализации оболочки.
 */
val LocalSectionNavigator = androidx.compose.runtime.staticCompositionLocalOf<NavHostController> {
    error("LocalSectionNavigator не предоставлен")
}

/**
 * NavHost конкретного раздела. Общие маршруты (детали трека, настройки, лабораторные
 * сценарии) доступны из любого раздела — это одна кодовая база и одна история.
 */
@Composable
private fun SectionNavHost(
    controller: NavHostController,
    section: MainSection,
    repository: PhonotekaRepository,
    settingsViewModel: SettingsViewModel,
    playerViewModel: PlayerViewModel,
    trackListViewModel: TrackListViewModel,
    authManager: LocalAuthManager,
    application: com.phonoteka.PhonotekaApp,
    onLock: () -> Unit,
    onNavigateToSection: (MainSection) -> Unit,
    onGlobalNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    CompositionLocalProvider(LocalSectionNavigator provides controller) {
        NavHost(
            navController = controller,
            startDestination = section.startRoute,
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 6 } },
            exitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 10 } },
            popEnterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 10 } },
            popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 6 } }
        ) {
            // ---------- Корневые экраны разделов ----------
            composable(section.startRoute) {
                when (section) {
                    MainSection.HOME -> HomeSection(
                        trackListViewModel = trackListViewModel,
                        playerViewModel = playerViewModel,
                        onNavigateToSection = onNavigateToSection,
                        onOpenTrack = { trackId -> controller.navigate("trackDetail/$trackId") }
                    )
                    MainSection.CATALOG -> CatalogSection(
                        trackListViewModel = trackListViewModel,
                        settingsViewModel = settingsViewModel,
                        onNavigateToSection = onNavigateToSection,
                        onOpenTrack = { trackId -> controller.navigate("trackDetail/$trackId") },
                        onOpenPosts = { controller.navigate("catalog/posts") }
                    )
                    MainSection.NOTES -> NotesSection(
                        onOpenTrackNotes = { controller.navigate("notes/track") },
                        onOpenFreeNotes = { controller.navigate("notes/free") },
                        onOpenArchive = { controller.navigate("notes/backup") }
                    )
                    MainSection.PROFILE -> ProfileSection(
                        trackListViewModel = trackListViewModel,
                        application = application,
                        onOpenAuth = { controller.navigate("profile/auth") },
                        onOpenAuthResults = { controller.navigate("profile/auth/results") },
                        onOpenAccess = { controller.navigate("profile/access") },
                        onOpenSettings = { controller.navigate("settings") }
                    )
                    MainSection.AUDIT -> JournalSection(
                        application = application,
                        onOpenLive = { controller.navigate("journal/live") },
                        onOpenAuthResults = { controller.navigate("journal/auth") },
                        onOpenAccess = { controller.navigate("profile/access") },
                        onOpenAbout = { controller.navigate("about") },
                        onOpenBuildMode = { controller.navigate("about/build") }
                    )
                    MainSection.SETTINGS -> SettingsScreen(
                        onBack = { onNavigateToSection(MainSection.HOME) },
                        onNavigateToSubScreen = onGlobalNavigate
                    )
                }
            }

            // ---------- Общие маршруты приложения ----------
            composable("trackDetail/{trackId}",
                arguments = listOf(navArgument("trackId") { type = NavType.LongType })) { entry ->
                val trackId = entry.arguments?.getLong("trackId") ?: 0L
                var stepUpPin by remember(trackId) { mutableStateOf("") }
                var stepUpGranted by remember(trackId) { mutableStateOf(authManager.isFreshAuthentication()) }
                if (!stepUpGranted) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Для просмотра деталей трека требуется свежая аутентификация (step-up).")
                        OutlinedTextField(value = stepUpPin,
                            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) stepUpPin = it },
                            label = { Text("PIN") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Button(onClick = {
                            stepUpGranted = authManager.verifyPin(stepUpPin) == PinResult.SUCCESS
                            stepUpPin = ""
                        }, enabled = stepUpPin.length in 4..6) { Text("Подтвердить") }
                        com.phonoteka.ui.components.AppBackTextButton(onBack)
                    }
                } else {
                    val detailViewModel: TrackDetailViewModel = viewModel(
                        factory = TrackDetailViewModel.Factory(repository, trackId))
                    if (com.phonoteka.security.DemoScope.LIBRARY_WRITE in LocalDemoPermissions.current) {
                        TrackDetailScreen(viewModel = detailViewModel, onBack = onBack,
                            onPlay = { track -> playerViewModel.play(track, trackListViewModel.playbackCatalogue()) })
                    } else {
                        val detail by detailViewModel.uiState.collectAsState()
                        Column(Modifier.padding(24.dp)) {
                            Text("Режим чтения: изменение и скачивание недоступны этой роли")
                            (detail as? com.phonoteka.viewmodel.DetailUiState.Success)?.track?.let { Text(it.displayName) }
                            com.phonoteka.ui.components.AppBackTextButton(onBack)
                        }
                    }
                }
            }

            composable("settings") {
                SettingsScreen(onBack = onBack,
                    onNavigateToSubScreen = { route -> onGlobalNavigate(route) })
            }
            composable("settings/general") { GeneralSettingsScreen(viewModel = settingsViewModel, onBack = onBack) }
            composable("settings/notifications") { NotificationsSettingsScreen(onBack = onBack) }
            composable("settings/theme") { ThemeSettingsScreen(viewModel = settingsViewModel, onBack = onBack) }

            // ЛР10: пост-каталог — тип контента общей ленты, а не отдельное приложение.
            composable("catalog/posts") {
                val catalogViewModel: PostCatalogViewModel = viewModel(
                    factory = PostCatalogViewModel.Factory(application.catalogRepository))
                Lab10CatalogScreen(catalogViewModel, onBack = onBack,
                    onPostClick = { id -> controller.navigate("catalog/posts/$id") })
            }
            composable("catalog/posts/{postId}",
                arguments = listOf(navArgument("postId") { type = NavType.IntType })) { entry ->
                val id = entry.arguments?.getInt("postId") ?: return@composable
                val catalogViewModel: PostCatalogViewModel = viewModel(
                    factory = PostCatalogViewModel.Factory(application.catalogRepository))
                Lab10PostDetailScreen(id, catalogViewModel, onBack = onBack)
            }

            // ЛР11: заметки и резервные копии — в контексте раздела «Заметки».
            composable("notes/backup") {
                Lab11BackupScreen(repository, onBack = onBack, onNotes = { controller.navigate("notes/track") })
            }
            composable("notes/track") {
                val notes: com.phonoteka.viewmodel.TrackNotesViewModel = viewModel(
                    factory = com.phonoteka.viewmodel.TrackNotesViewModel.Factory(application.trackNotes))
                TrackNotesScreen(notes, onBack = onBack)
            }
            composable("notes/free") {
                val ctx = context.applicationContext
                val freeNotes: com.phonoteka.viewmodel.FreeNotesViewModel = viewModel(
                    factory = com.phonoteka.viewmodel.FreeNotesViewModel.Factory(
                        com.phonoteka.data.freenotes.FreeNotesRepository(java.io.File(ctx.filesDir, "notes.json")),
                        com.phonoteka.data.freenotes.AndroidFreeNotesDocuments(ctx.contentResolver))
                )
                FreeNotesScreen(freeNotes, onBack = onBack)
            }

            // ЛР13: вход, PIN, биометрия, восстановление — в контексте профиля.
            composable("profile/auth") {
                Lab13MockAuthScreen(application.lab13MockSession, onBack = onBack,
                    onLogout = { trackListViewModel.cancelBatch(); onLock() })
            }
            composable("profile/auth/results") { Lab13ResultsScreen(onBack = onBack) }
            composable("journal/auth") { AuthAuditScreen(onBack = onBack) }

            // ЛР14: QR-проход и роли — в контексте профиля.
            composable("profile/access") { Lab14AccessScreen(onBack = onBack) }

            // ЛР15: живой сетевой канал — в контексте журнала.
            composable("journal/live") { com.phonoteka.ui.screens.Lab15LiveScreen(onBack = onBack) }

            // ЛР9: трекер задач с анимациями — в контексте главной (реальные действия).
            composable("tasks") {
                val ctx = context.applicationContext
                val taskViewModel: com.phonoteka.viewmodel.TaskTrackerViewModel = viewModel(
                    factory = com.phonoteka.viewmodel.TaskTrackerViewModel.Factory(
                        com.phonoteka.tasks.TaskFileStore(java.io.File(ctx.filesDir, "tasks_lab9.txt"))))
                com.phonoteka.ui.screens.TaskTrackerScreen(viewModel = taskViewModel, onBack = onBack)
            }

            // ЛР8: редактор профиля — в контексте раздела «Профиль».
            composable("profile/edit") {
                val ctx = context.applicationContext
                val profileViewModel: com.phonoteka.viewmodel.ProfileViewModel = viewModel(
                    factory = com.phonoteka.viewmodel.ProfileViewModel.Factory(
                        com.phonoteka.viewmodel.DataStoreProfileStore.from(ctx)))
                val total by trackListViewModel.totalCount.collectAsState(initial = 0)
                val done by trackListViewModel.doneCount.collectAsState(initial = 0)
                val review by trackListViewModel.reviewCount.collectAsState(initial = 0)
                val role by application.roleSession.changes.collectAsStateWithLifecycle()
                val authSnapshot by application.localAuth.authenticationSnapshotFlow.collectAsStateWithLifecycle()
                com.phonoteka.ui.screens.ProfileScreen(
                    viewModel = profileViewModel,
                    stats = com.phonoteka.ui.screens.ProfileStats(total, done, review),
                    accessRole = role.name,
                    sessionActive = authSnapshot.authenticatedAt != null,
                    onBack = onBack
                )
            }

            // ЛР9: окно измерения анимаций — в контексте настроек.
            composable("settings/lab9-measure") {
                com.phonoteka.ui.screens.Lab9MeasurementsScreen(onBack = onBack)
            }

            // Инструменты интерфейса ЛР1–7 (компоненты дизайн-системы, а не сценарий).
            composable("tools/labs1to7") { com.phonoteka.labs.LabsHubScreen(onExit = onBack) }

            composable("about") { AllLabsScreen(onBack = onBack) }
            composable("about/build") { com.phonoteka.ui.screens.BuildModeScreen(onBack = onBack) }
        }
    }
}

/** Стартовый маршрут раздела. */
val MainSection.startRoute: String
    get() = when (this) {
        MainSection.HOME -> "home"
        MainSection.CATALOG -> "catalog"
        MainSection.NOTES -> "notes"
        MainSection.PROFILE -> "profile"
        MainSection.AUDIT -> "journal"
        MainSection.SETTINGS -> "settings/root"
    }

@Composable
private fun MiniPlayerHost(playerViewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val playerState by playerViewModel.uiState.collectAsState()
    MiniPlayer(
        state = playerState,
        onPlayPause = { playerViewModel.togglePlayPause() },
        onSeek = { positionMs -> playerViewModel.seekTo(positionMs) },
        onNext = { playerViewModel.next() },
        onPrevious = { playerViewModel.previous() },
        onDismissError = { playerViewModel.clearError() },
        onRetry = { playerViewModel.retryConnect() },
        modifier = modifier
    )
}
