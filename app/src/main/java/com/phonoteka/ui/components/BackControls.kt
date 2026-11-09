package com.phonoteka.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Embedded back controls are for standalone previews; the app shell owns navigation. */
val LocalEmbeddedBackVisible = staticCompositionLocalOf { true }

@Composable
fun AppBackTextButton(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    if (LocalEmbeddedBackVisible.current) TextButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text("Назад") }
}

@Composable
fun AppBackIconButton(onClick: () -> Unit) {
    if (LocalEmbeddedBackVisible.current) IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
    }
}
