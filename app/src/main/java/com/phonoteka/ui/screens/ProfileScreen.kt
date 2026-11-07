package com.phonoteka.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.diagnostics.RecordRecomposition
import com.phonoteka.ui.components.AppButton
import com.phonoteka.ui.components.AppButtonSize
import com.phonoteka.ui.components.AppButtonStyle
import com.phonoteka.ui.components.ButtonState
import com.phonoteka.ui.components.FormField
import com.phonoteka.ui.components.FormFieldState
import com.phonoteka.ui.components.LoadingButton
import com.phonoteka.ui.components.ProfileCard
import com.phonoteka.ui.components.SnackbarLikeMessage
import com.phonoteka.ui.components.StatusButton
import com.phonoteka.ui.theme.LocalExtendedColors
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.ProfileData
import com.phonoteka.viewmodel.ProfileSaveStatus
import com.phonoteka.viewmodel.ProfileUiState
import com.phonoteka.viewmodel.ProfileViewModel
import com.phonoteka.viewmodel.ThemeMode

enum class ProfileWidthClass { COMPACT, MEDIUM, EXPANDED }

fun profileWidthClass(widthDp: Int): ProfileWidthClass = when {
    widthDp < 600 -> ProfileWidthClass.COMPACT
    widthDp < 840 -> ProfileWidthClass.MEDIUM
    else -> ProfileWidthClass.EXPANDED
}

/** Статистика передаётся снаружи (реальные счётчики фонотеки), а не захардкожена. */
data class ProfileStats(val totalTracks: Int, val doneTracks: Int, val reviewTracks: Int)

class ProfileActions(
    val onName: (String) -> Unit,
    val onBio: (String) -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onReset: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onForceDark: (Boolean) -> Unit
)

/** ЛР8: экран «Мой профиль». ViewModel остаётся здесь — в компоненты она не передаётся. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    stats: ProfileStats,
    themeMode: ThemeMode,
    forceDark: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onForceDark: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    RecordRecomposition("ProfileScreen")
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onThemeMode, onForceDark) {
        ProfileActions(viewModel::onNameChange, viewModel::onBioChange, viewModel::save, viewModel::cancel,
            viewModel::reset, viewModel::dismissMessage, onThemeMode, onForceDark)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Профиль · ЛР8") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
        })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            ProfileScreenContent(state, stats, themeMode, forceDark, actions, profileWidthClass(maxWidth.value.toInt()))
        }
    }
}

@Composable
fun ProfileScreenContent(
    state: ProfileUiState,
    stats: ProfileStats,
    themeMode: ThemeMode,
    forceDark: Boolean,
    actions: ProfileActions,
    widthClass: ProfileWidthClass
) {
    // MEDIUM использует двухколоночную раскладку: на планшете в портрете ей хватает места.
    if (widthClass == ProfileWidthClass.COMPACT) ProfileScreenCompact(state, stats, themeMode, forceDark, actions)
    else ProfileScreenExpanded(state, stats, themeMode, forceDark, actions)
}

@Composable
fun ProfileScreenCompact(state: ProfileUiState, stats: ProfileStats, themeMode: ThemeMode, forceDark: Boolean, actions: ProfileActions) {
    val nameFocus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("profileColumn"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProfileHeader()
        AppearingProfileCard(state.saved, onEdit = { nameFocus.requestFocus() })
        StatsBlock(stats, tiles = false)
        Achievements(stats)
        ProfileForm(state, actions, nameFocus)
        ThemeBlock(themeMode, forceDark, actions)
    }
}

@Composable
fun ProfileScreenExpanded(state: ProfileUiState, stats: ProfileStats, themeMode: ThemeMode, forceDark: Boolean, actions: ProfileActions) {
    val nameFocus = remember { FocusRequester() }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("profileColumn"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileHeader()
            AppearingProfileCard(state.saved, onEdit = { nameFocus.requestFocus() })
            StatsBlock(stats, tiles = true)
            Achievements(stats)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("profileColumn"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileForm(state, actions, nameFocus)
            ThemeBlock(themeMode, forceDark, actions)
        }
    }
}

@Composable
private fun ProfileHeader() {
    val ext = LocalExtendedColors.current
    Text(
        "Мой профиль",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(ext.headerStart, ext.headerEnd)), MaterialTheme.shapes.large)
            .padding(20.dp)
            .testTag("profileHeader")
            .semantics { heading() }
    )
}

@Composable
private fun AppearingProfileCard(data: ProfileData, onEdit: () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 },
        label = "profileCardAppear") {
        ProfileCard(
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }, // декоративная
            title = data.name,
            subtitle = data.bio.ifBlank { "Описание не заполнено" },
            trailingContent = {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Редактировать имя") }
            }
        )
    }
}

@Composable
private fun StatsBlock(stats: ProfileStats, tiles: Boolean) {
    val lines = listOf(
        "Треков в библиотеке: ${stats.totalTracks}",
        "Обработано: ${stats.doneTracks}",
        "На проверке: ${stats.reviewTracks}"
    )
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text("Статистика", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (tiles) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(8.dp)) }
        } else lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun Achievements(stats: ProfileStats) {
    val items = listOf(
        "Первый трек" to (stats.totalTracks > 0),
        "10 обработанных" to (stats.doneTracks >= 10),
        "Без треков на проверке" to (stats.totalTracks > 0 && stats.reviewTracks == 0)
    )
    Column(Modifier.testTag("achievements").semantics { collectionInfo = CollectionInfo(rowCount = items.size, columnCount = 1) }) {
        Text("Достижения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        items.forEachIndexed { index, (title, got) ->
            Text(
                (if (got) "★ " else "☆ ") + title + if (got) " — получено" else " — не получено",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) }
            )
        }
    }
}

@Composable
private fun ProfileForm(state: ProfileUiState, actions: ProfileActions, nameFocus: FocusRequester) {
    val saving = state.status == ProfileSaveStatus.SAVING
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormField(
            value = state.draft.name, onValueChange = actions.onName, label = "Имя",
            state = if (saving) FormFieldState.DISABLED else if (state.nameError != null) FormFieldState.ERROR else FormFieldState.IDLE,
            message = state.nameError, modifier = Modifier.fillMaxWidth().focusRequester(nameFocus).testTag("profileName")
        )
        FormField(value = "Слушатель", onValueChange = {}, label = "Роль", state = FormFieldState.DISABLED,
            message = "Роль назначается администратором", modifier = Modifier.fillMaxWidth())
        FormField(
            value = state.draft.bio, onValueChange = actions.onBio, label = "О себе",
            state = if (saving) FormFieldState.DISABLED else if (state.bioWarning != null) FormFieldState.WARNING else FormFieldState.IDLE,
            message = state.bioWarning, singleLine = false, modifier = Modifier.fillMaxWidth().testTag("profileBio")
        )
        LoadingButton("Сохранить изменения", actions.onSave, isLoading = saving, enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().testTag("profileSave"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(modifier = Modifier.testTag("profileCancel"), text = "Отмена", onClick = actions.onCancel,
                style = AppButtonStyle.SECONDARY, size = AppButtonSize.MEDIUM, enabled = state.dirty && !saving)
            AppButton(modifier = Modifier.testTag("profileReset"), text = "Сбросить", onClick = actions.onReset,
                style = AppButtonStyle.OUTLINED, size = AppButtonSize.SMALL, enabled = !saving)
        }
        StatusButton(
            text = "Статус сохранения",
            state = when (state.status) {
                ProfileSaveStatus.IDLE -> ButtonState.IDLE; ProfileSaveStatus.SAVING -> ButtonState.LOADING
                ProfileSaveStatus.SAVED -> ButtonState.SUCCESS; ProfileSaveStatus.ERROR -> ButtonState.ERROR
            },
            onClick = actions.onSave
        )
        state.message?.let { msg ->
            SnackbarLikeMessage(msg, isError = state.status == ProfileSaveStatus.ERROR, onDismiss = actions.onDismissMessage)
        }
    }
}

@Composable
private fun ThemeBlock(themeMode: ThemeMode, forceDark: Boolean, actions: ProfileActions) {
    Column {
        Text("Тема", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Column(Modifier.selectableGroup()) {
            listOf(ThemeMode.SYSTEM to "Как в системе", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная").forEach { (mode, label) ->
                Row(
                    Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("theme_${mode.name}")
                        .selectable(selected = themeMode == mode, role = Role.RadioButton, onClick = { actions.onThemeMode(mode) }),
                    verticalAlignment = Alignment.CenterVertically
                ) { RadioButton(selected = themeMode == mode, onClick = null); Text(label, Modifier.padding(start = 8.dp)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().minimumInteractiveComponentSize().testTag("forceDark")
                .toggleable(value = forceDark, role = Role.Switch, onValueChange = actions.onForceDark),
            verticalAlignment = Alignment.CenterVertically
        ) { Text("Принудительно тёмный контент", Modifier.weight(1f)); Switch(checked = forceDark, onCheckedChange = null) }
    }
}

private val previewActions = ProfileActions({}, {}, {}, {}, {}, {}, {}, {})
private val previewState = ProfileUiState(saved = ProfileData(bio = "Люблю джаз"), draft = ProfileData(bio = "Люблю джаз"), loaded = true)

@Preview(name = "Профиль — светлая", showBackground = true, widthDp = 400)
@Composable
private fun ProfileLightPreview() = PhonotekaTheme(themeMode = ThemeMode.LIGHT) {
    ProfileScreenContent(previewState, ProfileStats(42, 12, 0), ThemeMode.LIGHT, false, previewActions, ProfileWidthClass.COMPACT)
}

@Preview(name = "Профиль — тёмная", showBackground = true, widthDp = 400)
@Composable
private fun ProfileDarkPreview() = PhonotekaTheme(themeMode = ThemeMode.DARK) {
    ProfileScreenContent(previewState, ProfileStats(42, 12, 3), ThemeMode.DARK, false, previewActions, ProfileWidthClass.COMPACT)
}

@Preview(name = "Профиль — Expanded", showBackground = true, widthDp = 900)
@Composable
private fun ProfileExpandedPreview() = PhonotekaTheme(themeMode = ThemeMode.LIGHT) {
    ProfileScreenContent(previewState, ProfileStats(42, 12, 3), ThemeMode.LIGHT, false, previewActions, ProfileWidthClass.EXPANDED)
}
