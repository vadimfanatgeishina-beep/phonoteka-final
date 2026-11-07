package com.phonoteka.ui.screens

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
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonoteka.viewmodel.SettingsViewModel
import com.phonoteka.viewmodel.ThemeMode

/**
 * Экран настроек с вложенной навигацией (Лаба 6).
 * Показывает список категорий: Общие, Уведомления, Тема.
 * Каждая категория — отдельный подэкран.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToSubScreen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
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
            if (com.phonoteka.BuildConfig.DEBUG) androidx.compose.material3.TextButton(onClick = { onNavigateToSubScreen("settings/lab9-measure") }) { Text("ЛР9 · окно измерения") }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsCategoryItem(icon = Icons.Default.Tune, title = "Мой профиль · ЛР8",
                        subtitle = "Дизайн-система, форма, тема", onClick = { onNavigateToSubScreen("settings/profile") })
                    HorizontalDivider()
                    SettingsCategoryItem(icon = Icons.Default.Tune, title = "Трекер задач · ЛР9",
                        subtitle = "60 задач, анимации, эффекты, автосохранение", onClick = { onNavigateToSubScreen("settings/tasks") })
                    HorizontalDivider()
                    SettingsCategoryItem(icon = Icons.Default.Tune, title = "Заметки · ЛР11",
                        subtitle = "Независимые заметки, автосохранение, импорт/экспорт", onClick = { onNavigateToSubScreen("settings/lab11/free-notes") })
                    HorizontalDivider()
                    SettingsCategoryItem(icon = Icons.Default.Tune, title = "Лабораторные 1–7",
                        subtitle = "Первый экран, лейауты, состояние, списки, навигация, загрузка", onClick = { onNavigateToSubScreen("settings/labs1to7") })
                }
            }
            Text("Категории настроек", style = MaterialTheme.typography.titleSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    SettingsCategoryItem(
                        icon = Icons.Default.Tune,
                        title = "Общие",
                        subtitle = "Тёмная тема, автоскачивание, подсказки",
                        onClick = { onNavigateToSubScreen("settings/general") }
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
                        icon = Icons.Default.Palette,
                        title = "Тема",
                        subtitle = "Тёмная / светлая тема",
                        onClick = { onNavigateToSubScreen("settings/theme") }
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
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

            HorizontalDivider()

            Text("Дополнительно", style = MaterialTheme.typography.titleSmall)

            // Чекбоксы (Лаба 4)
            SettingRow(title = "Показывать подсказки", subtitle = "Вспомогательные тексты в интерфейсе") {
                Checkbox(checked = state.showHints, onCheckedChange = { viewModel.setShowHints(it) })
            }
            SettingRow(title = "Автоскачивание", subtitle = "Скачивать после поиска автоматически") {
                Checkbox(checked = state.autoDownload, onCheckedChange = { viewModel.setAutoDownload(it) })
            }
            SettingRow(title = "Бейдж explicit", subtitle = "Показывать метку Explicit на треках") {
                Checkbox(checked = state.showExplicitBadge, onCheckedChange = { viewModel.setShowExplicitBadge(it) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Уведомления") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
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
            Text(
                "Уведомления в разработке",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
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
