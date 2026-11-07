package com.phonoteka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** ЛР8: при загрузке вместо текста — CircularProgressIndicator, кнопка отключена (через AppButton). */
@Composable
fun LoadingButton(
    text: String,
    onClick: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: AppButtonSize = AppButtonSize.LARGE,
    style: AppButtonStyle = AppButtonStyle.PRIMARY
) {
    AppButton(
        modifier = modifier.semantics { if (isLoading) stateDescription = "Загрузка" },
        text = text,
        onClick = onClick,
        style = style,
        size = size,
        enabled = enabled,
        isLoading = isLoading
    )
}

/** Сообщение о сохранении/ошибке: assertive live region, исчезает через [durationMs]. */
@Composable
fun SnackbarLikeMessage(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Long = 3_000
) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) { delay(durationMs); dismiss() }
    val scheme = MaterialTheme.colorScheme
    Text(
        text = message,
        color = if (isError) scheme.onErrorContainer else scheme.onPrimaryContainer,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier
            .fillMaxWidth()
            .background(if (isError) scheme.errorContainer else scheme.primaryContainer, MaterialTheme.shapes.small)
            .padding(12.dp)
            .testTag("snackbarLikeMessage")
            .semantics { liveRegion = LiveRegionMode.Assertive }
    )
}
