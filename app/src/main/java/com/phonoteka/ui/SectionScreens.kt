package com.phonoteka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.ui.components.TrackCard
import com.phonoteka.viewmodel.ListUiState
import com.phonoteka.viewmodel.TrackListViewModel
import com.phonoteka.viewmodel.SettingsViewModel
import com.phonoteka.viewmodel.PlayerViewModel
import com.phonoteka.security.AuditEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Экран треков ЛР1 — главная вкладка объединённой Фонотеки. */
@Composable
fun HomeSection(
    trackListViewModel: TrackListViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateToSection: (MainSection) -> Unit,
    onOpenTrack: (Long) -> Unit
) {
    com.phonoteka.ui.screens.TrackListScreen(
        viewModel = trackListViewModel,
        onTrackClick = onOpenTrack,
        onPlayDownloadedTrack = { track ->
            playerViewModel.play(track, trackListViewModel.playbackCatalogue().filter(TrackListViewModel::isPhonotekaDownload))
        },
        onNavigateToSettings = { onNavigateToSection(MainSection.SETTINGS) },
        showLab9Tick = false
    )
}
/**
 * Раздел «Каталог»: поиск и скачивание музыки, локальные файлы и воспроизведение.
 * Сценарий каталог → загрузка → библиотека → воспроизведение → заметка.
 */
@Composable
fun CatalogSection(
    trackListViewModel: TrackListViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToSection: (MainSection) -> Unit,
    onOpenTrack: (Long) -> Unit,
    onOpenPosts: () -> Unit
) {
    val settings = LocalSettings.current
    var showCatalogSettings by remember { mutableStateOf(false) }
    val uiState by trackListViewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by trackListViewModel.searchQuery.collectAsStateWithLifecycle()
    val doneCount by trackListViewModel.doneCount.collectAsState(initial = 0)
    val totalCount by trackListViewModel.totalCount.collectAsState(initial = 0)

    SectionScaffold(
        title = "Каталог",
        subtitle = "Фильтруйте библиотеку, открывайте трек для поиска на YouTube/SoundCloud и скачивайте аудио."
    ) { pane ->
        Column(modifier = pane, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Треки: $doneCount из $totalCount готовы к воспроизведению",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (settings.showHints) {
                Text("Поиск ниже фильтрует уже добавленные треки. Добавление и скачивание доступны в карточке трека.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = trackListViewModel::onSearchQueryChange,
                modifier = Modifier.fillMaxWidth().testTag("catalog-search"),
                label = { Text("Исполнитель или песня") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )

            TextButton(onClick = onOpenPosts, modifier = Modifier.testTag("catalog-go-posts")) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Музыкальные истории")
            }
            TextButton(onClick = { onNavigateToSection(MainSection.NOTES) }) {
                Icon(Icons.Default.NoteAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Заметки к трекам")
            }
            TextButton(onClick = { showCatalogSettings = true }, modifier = Modifier.testTag("catalog-settings")) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Настройки каталога")
            }

            if (showCatalogSettings) AlertDialog(
                onDismissRequest = { showCatalogSettings = false },
                title = { Text("Настройки каталога") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Подсказки каталога", modifier = Modifier.weight(1f))
                            Switch(checked = settings.showHints, onCheckedChange = settingsViewModel::setShowHints)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showCatalogSettings = false }) { Text("Готово") } }
            )

            Text("Результаты", style = MaterialTheme.typography.titleMedium)
            when (val state = uiState) {
                is ListUiState.Success -> {
                    state.tracks.forEachIndexed { index, track ->
                        TrackCard(track = track, index = index, onClick = { onOpenTrack(track.id) })
                    }
                }
                is ListUiState.Loading -> Text("Загрузка…")
                is ListUiState.Empty -> Text("Ничего не найдено. Измените запрос.")
                is ListUiState.Error -> Text("Ошибка: ${state.message}")
            }
        }
    }
}

/** Раздел «Заметки»: заметки к трекам, свободные заметки и зашифрованный архив. */
@Composable
fun NotesSection(
    onOpenTrackNotes: () -> Unit,
    onOpenFreeNotes: () -> Unit,
    onOpenArchive: () -> Unit
) {
    SectionScaffold(
        title = "Заметки",
        subtitle = "Заметки привязаны к треку, редактируются и экспортируются. Зашифрованный архив восстанавливает вложения."
    ) { pane ->
        Column(modifier = pane, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NavigationCard(
                icon = Icons.Default.NoteAlt,
                title = "Заметки к трекам",
                description = "Создание, правка, удаление, сортировка по дате изменения. Заметка живёт рядом со своим треком.",
                testTag = "notes-open-track",
                onClick = onOpenTrackNotes
            )
            NavigationCard(
                icon = Icons.Default.NoteAlt,
                title = "Свободные заметки",
                description = "Заметки без привязки к треку: отдельный набор с автосохранением.",
                testTag = "notes-open-free",
                onClick = onOpenFreeNotes
            )
            NavigationCard(
                icon = Icons.Default.Download,
                title = "Резервная копия библиотеки",
                description = "JSON списка треков. Фотографии и тексты заметок в эту копию не входят.",
                testTag = "notes-open-archive",
                onClick = onOpenArchive
            )
            NavigationCard(
                icon = Icons.Default.Download,
                title = "Архив заметок с фотографиями",
                description = "Открыть заметки к трекам, где находятся сохранение и восстановление фотоархива.",
                testTag = "notes-open-photo-archive",
                onClick = onOpenTrackNotes
            )
        }
    }
}

/** Раздел «Профиль»: вход, PIN, биометрия, восстановление, QR-проход и роли. */
@Composable
fun ProfileSection(
    trackListViewModel: TrackListViewModel,
    application: com.phonoteka.PhonotekaApp,
    onOpenAuth: () -> Unit,
    onOpenAuthResults: () -> Unit,
    onOpenAccess: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val total by trackListViewModel.totalCount.collectAsState(initial = 0)
    val done by trackListViewModel.doneCount.collectAsState(initial = 0)
    val review by trackListViewModel.reviewCount.collectAsState(initial = 0)
    val role by application.roleSession.changes.collectAsStateWithLifecycle()
    val snapshot by application.localAuth.authenticationSnapshotFlow.collectAsStateWithLifecycle()

    SectionScaffold(
        title = "Профиль",
        subtitle = "Учётная запись, локальный вход, роль и доступ к закрытым данным — всё привязано к одному профилю."
    ) { pane ->
        Column(modifier = pane, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityRow(
                icon = SectionIcons.PIN,
                title = "Локальный вход и PIN",
                description = "Учебная запись, 4-значный PIN, по желанию TOTP-код из Authenticator и биометрия.",
                state = if (snapshot.authenticatedAt != null) "Сессия активна" else "Требуется вход"
            )
            CapabilityRow(
                icon = SectionIcons.BIOMETRIC,
                title = "Биометрия и step-up",
                description = "Подтверждение перед чувствительными действиями: каталог, заметки, экспорт журнала."
            )
            CapabilityRow(
                icon = SectionIcons.QR,
                title = "QR-проход и роли (ЛР14)",
                description = "Короткоживущий одноразовый код, привязанный к тестовому пользователю и роли.",
                state = "Роль: ${role.name}"
            )
            CapabilityRow(
                icon = SectionIcons.ACCESS,
                title = "Доступ и роли",
                description = "Решения о доступе пишутся в единый журнал с указанием, кто, когда и с каким результатом."
            )

            Text("Библиотека профиля: $done из $total, на проверке $review",
                style = MaterialTheme.typography.bodyMedium)

            NavigationCard(
                icon = SectionIcons.PIN,
                title = "Вход, PIN и восстановление",
                description = "Первый вход, PIN, подключение Authenticator, биометрия, восстановление и блокировка.",
                testTag = "profile-open-auth",
                onClick = onOpenAuth
            )
            NavigationCard(
                icon = Icons.Default.History,
                title = "Журнал входов",
                description = "История входов, неудачных попыток, блокировок и смены PIN.",
                testTag = "profile-open-auth-results",
                onClick = onOpenAuthResults
            )
            NavigationCard(
                icon = SectionIcons.QR,
                title = "QR-проход и роли",
                description = "Выпуск и сканирование учебного QR-прохода, проверка роли и срока действия.",
                testTag = "profile-open-access",
                onClick = onOpenAccess
            )
            NavigationCard(
                icon = Icons.Default.Settings,
                title = "Настройки",
                description = "Тема, уведомления, общие параметры приложения.",
                testTag = "profile-open-settings",
                onClick = onOpenSettings
            )
        }
    }
}

/** Раздел «Журнал»: единый журнал событий ЛР13–ЛР16. */
@Composable
fun JournalSection(
    application: com.phonoteka.PhonotekaApp,
    onOpenLive: () -> Unit,
    onOpenAuthResults: () -> Unit,
    onOpenAccess: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBuildMode: () -> Unit
) {
    val audit = application.unifiedAudit
    var events by remember(audit) { mutableStateOf(emptyList<AuditEvent>()) }
    var isRefreshing by remember(audit) { mutableStateOf(true) }
    var integrityIntact by remember(audit) { mutableStateOf<Boolean?>(null) }
    var loadError by remember(audit) { mutableStateOf<String?>(null) }
    var confirmClearAudit by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()
    val authSnapshot by application.localAuth.authenticationSnapshotFlow.collectAsStateWithLifecycle()
    var remainingFreshMillis by remember { mutableLongStateOf(0L) }

    LaunchedEffect(audit) {
        isRefreshing = true
        val result = runCatching {
            withContext(Dispatchers.IO) {
                audit.recent(50) to audit.isIntegrityIntact()
            }
        }
        result.onSuccess { (loaded, intact) ->
            events = loaded
            integrityIntact = intact
            loadError = null
        }.onFailure {
            loadError = "Не удалось прочитать журнал. Попробуйте обновить."
            integrityIntact = false
        }
        isRefreshing = false
    }

    LaunchedEffect(authSnapshot.version, authSnapshot.authenticatedAt) {
        while (authSnapshot.authenticatedAt != null) {
            remainingFreshMillis = application.localAuth.remainingFreshMillis()
            if (remainingFreshMillis <= 0L) break
            delay(1000L)
        }
        if (authSnapshot.authenticatedAt == null) remainingFreshMillis = 0L
    }

    SectionScaffold(
        title = "Журнал",
        subtitle = "Единый журнал событий: вход и восстановление, QR и роли, сетевые действия и WSS, проверки защиты."
    ) { pane ->
        Column(modifier = pane, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Целостность журнала: " + when (integrityIntact) {
                    true -> "цепочка хешей не нарушена"
                    false -> "не удалось подтвердить целостность"
                    null -> "проверяется"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (integrityIntact == false) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
            )
            val sessionStatus = when {
                authSnapshot.authenticatedAt == null -> "Нет активной локальной сессии"
                remainingFreshMillis > 0L -> "Сессия активна · свежая аутентификация ещё ${formatRemaining(remainingFreshMillis)}"
                else -> "Сессия истекла · требуется повторный вход"
            }
            Text(sessionStatus, style = MaterialTheme.typography.bodyMedium)
            Text("Показаны последние ${events.size} событий. Устройство — модель телефона; IP показывается, только если доступен локальный адрес. Геопозиция не собирается.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            NavigationCard(
                icon = Icons.Default.Security,
                title = "Живой сетевой канал (ЛР15)",
                description = "Подключение к собственному WSS-стенду: статус, сообщения, ошибка и повтор.",
                testTag = "journal-open-live",
                onClick = onOpenLive
            )
            NavigationCard(
                icon = Icons.Default.History,
                title = "Журнал входов (ЛР13)",
                description = "Отдельный подробный журнал аутентификации с блокировками и восстановлением.",
                testTag = "journal-open-auth-results",
                onClick = onOpenAuthResults
            )
            NavigationCard(
                icon = SectionIcons.ACCESS,
                title = "Решения доступа и QR (ЛР14)",
                description = "Кто, кому, когда, что и с каким результатом.",
                testTag = "journal-open-access",
                onClick = onOpenAccess
            )
            NavigationCard(
                icon = Icons.Default.VerifiedUser,
                title = "Режим сборки и отличия версий",
                description = "Что включено в этой сборке и чем она отличается от второй: сценарий за сценарием.",
                testTag = "journal-open-build-mode",
                onClick = onOpenBuildMode
            )
            NavigationCard(
                icon = Icons.Default.LibraryMusic,
                title = "Карта функций Фонотеки",
                description = "Где найти возможности ЛР1–16 и что делает каждый раздел.",
                testTag = "journal-open-about",
                onClick = onOpenAbout
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Последние события", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmClearAudit = true }, enabled = events.isNotEmpty(),
                    modifier = Modifier.testTag("journal-clear")) { Text("Очистить") }
                IconButton(
                    onClick = {
                        if (!isRefreshing) refreshScope.launch {
                            isRefreshing = true
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    audit.recent(50) to audit.isIntegrityIntact()
                                }
                            }
                            result.onSuccess { (loaded, intact) ->
                                events = loaded
                                integrityIntact = intact
                                loadError = null
                            }.onFailure {
                                loadError = "Не удалось прочитать журнал. Попробуйте ещё раз."
                                integrityIntact = false
                            }
                            isRefreshing = false
                        }
                    },
                    enabled = !isRefreshing,
                    modifier = Modifier.testTag("journal-refresh")
                ) {
                    if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = "Обновить журнал")
                }
                }
            }
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (events.isEmpty() && !isRefreshing) {
                Text("Событий пока нет.", style = MaterialTheme.typography.bodyMedium)
            } else {
                events.asReversed().forEach { event ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${formatAuditTimestamp(event.timestamp)} · ${event.type.labelRu()} · ${event.result.labelRu()}",
                            style = MaterialTheme.typography.labelLarge)
                        Text("${event.userId} (${event.role}) · ${event.action} · ${event.subject}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Сессия ${event.sessionId ?: "—"} · Устройство ${event.device ?: "недоступно"} · IP ${event.ip ?: "недоступно"} · Гео ${event.geo ?: "не собирается"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        event.reason?.let { reason ->
                            Text("Причина: $reason", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (confirmClearAudit) AlertDialog(
        onDismissRequest = { confirmClearAudit = false },
        title = { Text("Очистить журнал событий?") },
        text = { Text("Старые события будут скрыты из этого списка. Полная история сохранится для проверки целостности и экспорта. В списке останется отметка об очистке.") },
        confirmButton = { TextButton(onClick = {
            confirmClearAudit = false
            refreshScope.launch {
                isRefreshing = true
                val result = runCatching { withContext(Dispatchers.IO) { audit.clearHistory() } }
                result.onSuccess {
                    events = audit.recent(50)
                    integrityIntact = audit.isIntegrityIntact()
                    loadError = null
                }.onFailure { loadError = "Не удалось очистить журнал." }
                isRefreshing = false
            }
        }) { Text("Очистить журнал") } },
        dismissButton = { TextButton(onClick = { confirmClearAudit = false }) { Text("Отмена") } }
    )
}

private fun formatAuditTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun com.phonoteka.security.AuditEventType.labelRu(): String = when (this) {
    com.phonoteka.security.AuditEventType.LOGIN -> "Вход"
    com.phonoteka.security.AuditEventType.LOGIN_FAILED -> "Неудачный вход"
    com.phonoteka.security.AuditEventType.LOGOUT -> "Выход"
    com.phonoteka.security.AuditEventType.LOCK -> "Блокировка"
    com.phonoteka.security.AuditEventType.PIN_SET -> "Настройка PIN"
    com.phonoteka.security.AuditEventType.PIN_CHANGED -> "Смена PIN"
    com.phonoteka.security.AuditEventType.RECOVERY -> "Восстановление"
    com.phonoteka.security.AuditEventType.SESSION -> "Сессия"
    com.phonoteka.security.AuditEventType.STEP_UP -> "Повторная проверка"
    com.phonoteka.security.AuditEventType.QR_SCAN -> "Сканирование QR"
    com.phonoteka.security.AuditEventType.QR_ISSUE -> "Выдача QR"
    com.phonoteka.security.AuditEventType.QR_REJECT -> "QR отклонён"
    com.phonoteka.security.AuditEventType.ACCESS_ALLOWED -> "Доступ разрешён"
    com.phonoteka.security.AuditEventType.ACCESS_DENIED -> "Доступ запрещён"
    com.phonoteka.security.AuditEventType.NETWORK -> "Сетевая проверка"
    com.phonoteka.security.AuditEventType.WSS -> "WSS-соединение"
    com.phonoteka.security.AuditEventType.DOWNLOAD -> "Загрузка"
    com.phonoteka.security.AuditEventType.NOTES_IMPORT -> "Импорт заметок"
    com.phonoteka.security.AuditEventType.NOTES_EXPORT -> "Экспорт заметок"
    com.phonoteka.security.AuditEventType.AUDIT_CHECK -> "Проверка журнала"
    com.phonoteka.security.AuditEventType.RASP -> "Проверка защиты приложения"
}

private fun String.labelRu(): String = when (uppercase(Locale.ROOT)) {
    "OK", "SUCCESS" -> "Успешно"
    "ALLOWED" -> "Разрешено"
    "DENIED", "REJECTED" -> "Отказано"
    "ERROR", "FAILED" -> "Ошибка"
    else -> this
}

private fun formatRemaining(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) + 999L) / 1000L
    return "%02d:%02d".format(Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
}

/** Карточка-переход внутри раздела. */
@Composable
private fun NavigationCard(
    icon: ImageVector,
    title: String,
    description: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(testTag)) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun HomeStat(label: String, count: Int, background: Color) {
    Column(
        modifier = Modifier.width(88.dp).background(background, MaterialTheme.shapes.medium)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
