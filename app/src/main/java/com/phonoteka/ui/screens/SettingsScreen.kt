package com.phonoteka.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phonoteka.viewmodel.SettingsViewModel
import com.phonoteka.viewmodel.ThemeMode

/**
 * Экран настроек с вложенной навигацией (Лаба 6).
 * Показывает профиль и категории настроек.
 * Каждая категория — отдельный подэкран.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSubScreen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    com.phonoteka.ui.components.AppBackIconButton(onBack)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsCategoryItem(icon = Icons.Default.Person, title = "Мой профиль",
                subtitle = "Аватар, ник, описание и статистика библиотеки",
                onClick = { onNavigateToSubScreen("settings/profile") })
            if (com.phonoteka.BuildConfig.DEBUG) androidx.compose.material3.TextButton(onClick = { onNavigateToSubScreen("settings/lab9-measure") }) { Text("ЛР9 · окно измерения") }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsCategoryItem(icon = Icons.Default.Tune, title = "Трекер задач · ЛР9",
                        subtitle = "60 задач, анимации, эффекты, автосохранение", onClick = { onNavigateToSubScreen("settings/tasks") })
                    HorizontalDivider()
                    SettingsCategoryItem(icon = Icons.Default.Tune, title = "Заметки · ЛР11",
                        subtitle = "Независимые заметки, автосохранение, импорт/экспорт", onClick = { onNavigateToSubScreen("settings/lab11/free-notes") })
                    HorizontalDivider()
                    SettingsCategoryItem(icon = Icons.Default.Tune, title = "Учебные примеры ЛР1–7",
                        subtitle = "Исходные демонстрации Compose; реальные функции работают в разделах Фонотеки", onClick = { onNavigateToSubScreen("settings/labs1to7") })
                }
            }
            Text("Категории настроек", style = MaterialTheme.typography.titleSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsCategoryItem(
                        icon = Icons.Default.Tune,
                        title = "Общие",
                        subtitle = "Навигация, автоскачивание и подсказки",
                        onClick = { onNavigateToSubScreen("settings/general") }
                    )
                    HorizontalDivider()
                    SettingsCategoryItem(
                        icon = Icons.Default.Tune,
                        title = "Тема оформления",
                        subtitle = "Системная, светлая или тёмная тема",
                        onClick = { onNavigateToSubScreen("settings/theme") }
                    )
                    HorizontalDivider()
                    SettingsCategoryItem(
                        icon = Icons.Default.Notifications,
                        title = "Уведомления",
                        subtitle = "Управление уведомлениями",
                        onClick = { onNavigateToSubScreen("settings/notifications") }
                    )
                    HorizontalDivider()
                    SettingsCategoryItem(
                        icon = Icons.Default.Tune,
                        title = "Сетевой каталог · ЛР10",
                        subtitle = "Загрузка, список и повтор запроса",
                        onClick = { onNavigateToSubScreen("settings/lab10") }
                    )
                    HorizontalDivider()
                    SettingsCategoryItem(
                        icon = Icons.Default.Tune,
                        title = "Резервная копия · ЛР11",
                        subtitle = "JSON экспорт и восстановление библиотеки",
                        onClick = { onNavigateToSubScreen("settings/lab11") }
                    )
                    HorizontalDivider()
                    SettingsCategoryItem(
                        icon = Icons.Default.Tune,
                        title = "Вход и токены · ЛР13",
                        subtitle = "Mock login, lockout и lifecycle токенов",
                        onClick = { onNavigateToSubScreen("settings/lab13") }
                    )
                    HorizontalDivider()
                    SettingsCategoryItem(
                        icon = Icons.Default.Tune,
                        title = "Заказы и результаты · ЛР13",
                        subtitle = "Результаты — только после повторного подтверждения",
                        onClick = { onNavigateToSubScreen("settings/lab13/results") }
                    )
                    HorizontalDivider()
                    SettingsCategoryItem(
                        icon = Icons.Default.Tune,
                        title = "Доступ и аудит · ЛР14",
                        subtitle = "Роли, scopes и разрешение уведомлений",
                        onClick = { onNavigateToSubScreen("settings/lab14") }
                    )
                    HorizontalDivider()
                    SettingsCategoryItem(icon = Icons.Default.Tune, title = "Локальный WSS · ЛР15",
                        subtitle = "Собственный TLS/HMAC стенд и метрики", onClick = { onNavigateToSubScreen("settings/lab15") })
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("О приложении", style = MaterialTheme.typography.titleSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Фонотека ищет треки на YouTube и SoundCloud, " +
                            "оценивает совпадение по исполнителю и названию, " +
                            "и скачивает лучший кандидат в локальное хранилище.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Скачанные файлы сохраняются в папке приложения " +
                            "и доступны для воспроизведения. " +
                            "Также можно загружать текстовики (.txt) с треками " +
                            "и привязывать локальные аудиофайлы.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Подэкран «Общие настройки» (Лаба 6: вложенная навигация).
 * Содержит Switch и Checkbox (Лаба 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Общие") },
                navigationIcon = {
                    com.phonoteka.ui.components.AppBackIconButton(onBack)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Дополнительно", style = MaterialTheme.typography.titleSmall)

            SettingRow(
                title = "Возврат жестом",
                subtitle = "Слева — кнопка «Назад» сверху. Справа — кнопка скрыта, используйте системный жест Android."
            ) {
                Switch(checked = state.gestureOnlyBack, onCheckedChange = viewModel::setGestureOnlyBack,
                    modifier = Modifier.testTag("gesture-only-back"))
            }

            // Чекбоксы (Лаба 4)
            SettingRow(title = "Показывать подсказки", subtitle = "Вспомогательные тексты в интерфейсе") {
                Checkbox(checked = state.showHints, onCheckedChange = { viewModel.setShowHints(it) })
            }
            SettingRow(title = "Автоскачивание", subtitle = "Скачивать после поиска автоматически") {
                Checkbox(checked = state.autoDownload, onCheckedChange = { viewModel.setAutoDownload(it) })
            }
            SettingRow(title = "Метка Explicit", subtitle = "Показывать пометку у найденных кандидатов") {
                Checkbox(checked = state.showExplicitBadge, onCheckedChange = { viewModel.setShowExplicitBadge(it) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notificationsGranted = it }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уведомления") },
                navigationIcon = {
                    com.phonoteka.ui.components.AppBackIconButton(onBack)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Уведомления воспроизведения", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android показывает медиа-уведомление, пока играет музыка. Разрешение можно включить здесь; звук и библиотека работают и без него.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            Build.VERSION.SDK_INT < 33 -> "Отдельное разрешение не требуется на Android ${Build.VERSION.SDK_INT}."
                            notificationsGranted -> "Разрешение на уведомления включено."
                            else -> "Разрешение на уведомления выключено."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("notification-permission-status")
                    )
                    if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                        androidx.compose.material3.Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.fillMaxWidth().testTag("notification-permission-request")
                        ) { Text("Разрешить уведомления") }
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        }, modifier = Modifier.testTag("notification-open-system-settings")) {
                            Text("Открыть настройки Android")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Тема оформления") },
                navigationIcon = {
                    com.phonoteka.ui.components.AppBackIconButton(onBack)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Режим темы", style = MaterialTheme.typography.titleSmall)
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setThemeMode(mode) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) }
                    )
                    Text(
                        text = when (mode) {
                            ThemeMode.SYSTEM -> "Системная"
                            ThemeMode.LIGHT -> "Светлая"
                            ThemeMode.DARK -> "Тёмная"
                        }
                    )
                }
            }
            SettingRow(
                title = "Принудительно тёмная тема",
                subtitle = "Тёмные поверхности при любом режиме"
            ) {
                Switch(
                    checked = state.forceDarkContent,
                    onCheckedChange = viewModel::setForceDarkContent
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}
