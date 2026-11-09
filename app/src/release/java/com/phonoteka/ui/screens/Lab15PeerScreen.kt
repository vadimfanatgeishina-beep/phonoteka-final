package com.phonoteka.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column

@Composable fun Lab15PeerScreen(onBack: () -> Unit) { Column {
    Text("Двухтелефонный WSS-стенд поставляется только с учебной debug-сборкой.")
    TextButton(onClick = onBack) { Text("Назад") }
} }
