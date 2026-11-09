package com.phonoteka.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Состояние настроек приложения.
 * Тёмная тема по умолчанию светлая. Дополнительные настройки (Лаба 4).
 */
data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val forceDarkContent: Boolean = false,
    val gestureOnlyBack: Boolean = false,
    val showHints: Boolean = true,          // Показывать подсказки (Лаба 4: чекбокс)
    val autoDownload: Boolean = false,      // Автоскачивание после поиска (Лаба 4: чекбокс)
    val showExplicitBadge: Boolean = true   // Показывать бейдж explicit (Лаба 4: чекбокс)
) {
    /** Kept for older UI call sites; SYSTEM is resolved by the app theme. */
    val isDarkMode: Boolean get() = themeMode == ThemeMode.DARK
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "phonoteka_settings"
)

private val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
private val FORCE_DARK_CONTENT_KEY = booleanPreferencesKey("force_dark_content")
private val GESTURE_ONLY_BACK_KEY = booleanPreferencesKey("gesture_only_back")
private val SHOW_HINTS_KEY = booleanPreferencesKey("show_hints")
private val AUTO_DOWNLOAD_KEY = booleanPreferencesKey("auto_download")
private val SHOW_EXPLICIT_BADGE_KEY = booleanPreferencesKey("show_explicit_badge")

/**
 * ViewModel настроек.
 *
 * Тёмная тема хранится в DataStore и переживает перезапуск приложения.
 * Скачивание из интернета в приложении отсутствует, поэтому настроек
 * источников/загрузки здесь нет.
 */
class SettingsViewModel(
    application: Application,
    testDataStore: DataStore<Preferences>? = null
) : AndroidViewModel(application) {
    /** Required by AndroidViewModelFactory used by the legacy app entry point. */
    constructor(application: Application) : this(application, null)

    private val store: DataStore<Preferences> = testDataStore ?: application.applicationContext.settingsDataStore


    val state: StateFlow<SettingsState> = store.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            SettingsState(
                themeMode = prefs[THEME_MODE_KEY]?.let { stored ->
                    ThemeMode.entries.firstOrNull { it.name == stored }
                } ?: if (prefs[DARK_MODE_KEY] == true) ThemeMode.DARK else ThemeMode.SYSTEM,
                forceDarkContent = prefs[FORCE_DARK_CONTENT_KEY] ?: false,
                gestureOnlyBack = prefs[GESTURE_ONLY_BACK_KEY] ?: false,
                showHints = prefs[SHOW_HINTS_KEY] ?: true,
                autoDownload = prefs[AUTO_DOWNLOAD_KEY] ?: false,
                showExplicitBadge = prefs[SHOW_EXPLICIT_BADGE_KEY] ?: true
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsState()
        )

    fun setDarkMode(enabled: Boolean) {
        setThemeMode(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT)
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[THEME_MODE_KEY] = mode.name
            }
        }
    }

    fun setForceDarkContent(enabled: Boolean) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[FORCE_DARK_CONTENT_KEY] = enabled
            }
        }
    }

    fun setGestureOnlyBack(enabled: Boolean) {
        viewModelScope.launch { store.edit { it[GESTURE_ONLY_BACK_KEY] = enabled } }
    }

    fun setShowHints(enabled: Boolean) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[SHOW_HINTS_KEY] = enabled
            }
        }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[AUTO_DOWNLOAD_KEY] = enabled
            }
        }
    }

    fun setShowExplicitBadge(enabled: Boolean) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[SHOW_EXPLICIT_BADGE_KEY] = enabled
            }
        }
    }
}
