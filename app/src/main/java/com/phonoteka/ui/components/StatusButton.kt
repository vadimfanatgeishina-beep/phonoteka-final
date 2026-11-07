package com.phonoteka.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.testTag
import com.phonoteka.ui.theme.LocalExtendedColors

enum class ButtonState { IDLE, LOADING, ERROR, SUCCESS }

@Composable
fun StatusButton(
    text: String,
    state: ButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = when (state) {
        ButtonState.IDLE -> text
        ButtonState.LOADING -> "$text: загрузка"
        ButtonState.ERROR -> "Ошибка: повторите действие"
        ButtonState.SUCCESS -> "Сохранено"
    }
    val buttonStateText = when (state) {
        ButtonState.IDLE -> "Готово"
        ButtonState.LOADING -> "Загрузка"
        ButtonState.ERROR -> "Ошибка"
        ButtonState.SUCCESS -> "Сохранено"
    }
    val success = LocalExtendedColors.current.success
    val buttonContainer = when (state) {
        ButtonState.ERROR -> MaterialTheme.colorScheme.errorContainer
        ButtonState.SUCCESS -> success
        else -> null
    }
    val buttonContent = when (state) {
        ButtonState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        ButtonState.SUCCESS -> MaterialTheme.colorScheme.surface
        else -> null
    }
    AppButton(
        modifier = modifier.testTag("status-button").semantics {
            contentDescription = description
            stateDescription = buttonStateText
            if (state == ButtonState.ERROR || state == ButtonState.SUCCESS) liveRegion = LiveRegionMode.Assertive
        },
        text = text,
        onClick = onClick,
        style = AppButtonStyle.PRIMARY,
        enabled = state == ButtonState.IDLE,
        isLoading = state == ButtonState.LOADING,
        containerColor = buttonContainer,
        contentColor = buttonContent,
        leadingContent = if (state == ButtonState.ERROR) {
            {
            androidx.compose.material3.Icon(Icons.Default.Error, contentDescription = null,
                tint = MaterialTheme.colorScheme.error)
            }
        } else if (state == ButtonState.SUCCESS) {
            {
            androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = null, tint = buttonContent ?: MaterialTheme.colorScheme.onSurface)
            }
        } else null
    )
}
