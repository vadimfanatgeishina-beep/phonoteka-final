package com.phonoteka.ui

import androidx.compose.runtime.compositionLocalOf
import com.phonoteka.viewmodel.SettingsState
import com.phonoteka.viewmodel.ThemeMode

/**
 * CompositionLocal для доступа к настройкам из любого composable-экрана
 * без явной передачи через параметры (требование Лабы 6).
 *
 * Значение по умолчанию — светлая тема. Реальное значение предоставляется
 * в [Navigation] через CompositionLocalProvider.
 */
val LocalSettings = compositionLocalOf<SettingsState> {
    SettingsState(themeMode = ThemeMode.SYSTEM)
}
