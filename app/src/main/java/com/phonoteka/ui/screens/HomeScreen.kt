package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import com.phonoteka.ui.theme.LocalExtendedColors
import androidx.compose.ui.unit.sp
import com.phonoteka.ui.LocalSettings
import com.phonoteka.ui.components.AppButton
import com.phonoteka.ui.components.AppButtonSize
import com.phonoteka.ui.components.AppButtonStyle
import com.phonoteka.ui.components.ProfileCard
import com.phonoteka.viewmodel.ThemeMode

/**
 * Главный экран (Лаба 6):
 *  - Название приложения
 *  - Краткое описание
 *  - Статистика (скачано / всего)
 *  - Кнопки перехода к списку и настройкам
 */
@Composable
fun HomeScreen(
    doneCount: Int,
    totalCount: Int,
    errorCount: Int,
    reviewCount: Int,
    onNavigateToList: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToTheme: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    com.phonoteka.diagnostics.RecordRecomposition("HomeScreen")
    // BoxWithConstraints — адаптивная компоновка (Лаба 3):
    // на широких экранах логотип и название в Row, на узких — в Column.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        val settings = LocalSettings.current
        val isWideLayout = maxWidth > 600.dp || LocalConfiguration.current.screenWidthDp > 600

        val overview: @Composable (Modifier) -> Unit = { pane ->
            Column(modifier = pane, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically()) {
                    ProfileCard(
                        leadingIcon = {
                            Box(
                                modifier = Modifier.size(48.dp).background(
                                    Brush.linearGradient(listOf(LocalExtendedColors.current.headerStart, LocalExtendedColors.current.headerEnd)), CircleShape
                                ),
                                contentAlignment = Alignment.Center
                            ) { Text("Ф", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary) }
                        },
                        title = "Фонотека",
                        subtitle = "Готово: $doneCount из $totalCount треков"
                    )
                }
                Text(
                    text = "Поиск и скачивание музыки с YouTube и SoundCloud. Скачано $doneCount из $totalCount треков.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(if (isWideLayout) 20.dp else 8.dp)) {
                    StatCard("Готово", doneCount, MaterialTheme.colorScheme.primaryContainer)
                    StatCard("Ошибки", errorCount, MaterialTheme.colorScheme.errorContainer)
                    StatCard("Проверка", reviewCount, MaterialTheme.colorScheme.tertiaryContainer)
                }
            }
        }
        val actions: @Composable (Modifier) -> Unit = { pane ->
            Column(modifier = pane, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppButton(Modifier.fillMaxWidth().height(50.dp), "Перейти к списку треков", onNavigateToList,
                    AppButtonStyle.PRIMARY, AppButtonSize.LARGE,
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) })
                AppButton(Modifier.fillMaxWidth().height(50.dp), "Настройки", onNavigateToSettings,
                    AppButtonStyle.SECONDARY,
                    leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) })
                AppButton(Modifier.fillMaxWidth(), "Тема оформления", onNavigateToTheme,
                    AppButtonStyle.OUTLINED, AppButtonSize.SMALL,
                    leadingContent = { Icon(Icons.Default.Palette, contentDescription = null) })
                Text("Тема: " + when (settings.themeMode) {
                    ThemeMode.SYSTEM -> "системная"
                    ThemeMode.LIGHT -> "светлая"
                    ThemeMode.DARK -> "тёмная"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isWideLayout) {
            Row(Modifier.fillMaxWidth().testTag("home-expanded"), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                overview(Modifier.weight(1f))
                actions(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth().testTag("home-compact"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                overview(Modifier.fillMaxWidth())
                actions(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StatCard(label: String, count: Int, bgColor: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .background(bgColor, MaterialTheme.shapes.medium)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontSize = 24.sp
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
