package com.phonoteka.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults

enum class AppButtonStyle { PRIMARY, SECONDARY, OUTLINED }
enum class AppButtonSize { SMALL, MEDIUM, LARGE }

@Composable
fun AppButton(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit,
    style: AppButtonStyle = AppButtonStyle.PRIMARY,
    size: AppButtonSize = AppButtonSize.MEDIUM,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    containerColor: Color? = null,
    contentColor: Color? = null
) {
    val padding = when (size) {
        AppButtonSize.SMALL -> PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        AppButtonSize.MEDIUM -> PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        AppButtonSize.LARGE -> PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    }
    val fontSize = when (size) {
        AppButtonSize.SMALL -> 12.sp
        AppButtonSize.MEDIUM -> 14.sp
        AppButtonSize.LARGE -> 16.sp
    }
    val actionEnabled = enabled && !isLoading

    when (style) {
        AppButtonStyle.PRIMARY -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = actionEnabled,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor ?: MaterialTheme.colorScheme.primary,
                contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = containerColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                disabledContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        ) { ButtonContent(text, fontSize, isLoading, leadingContent) }
        AppButtonStyle.SECONDARY -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = actionEnabled,
            contentPadding = padding,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = containerColor ?: MaterialTheme.colorScheme.secondaryContainer,
                contentColor = contentColor ?: MaterialTheme.colorScheme.onSecondaryContainer,
                disabledContainerColor = containerColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                disabledContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        ) { ButtonContent(text, fontSize, isLoading, leadingContent) }
        AppButtonStyle.OUTLINED -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = actionEnabled,
            contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor ?: Color.Transparent,
                contentColor = contentColor ?: MaterialTheme.colorScheme.primary,
                disabledContainerColor = containerColor ?: Color.Transparent,
                disabledContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        ) { ButtonContent(text, fontSize, isLoading, leadingContent) }
    }
}

@Composable
private fun RowScope.ButtonContent(
    text: String,
    fontSize: TextUnit,
    isLoading: Boolean,
    leadingContent: (@Composable RowScope.() -> Unit)?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        } else {
            leadingContent?.invoke(this)
            if (leadingContent != null) Spacer(Modifier.width(8.dp))
            Text(text = text, fontSize = fontSize)
        }
    }
}
