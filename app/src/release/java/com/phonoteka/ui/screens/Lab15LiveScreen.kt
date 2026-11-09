package com.phonoteka.ui.screens
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
@Composable fun Lab15LiveScreen(onBack: () -> Unit, onPeer: () -> Unit) { Column { Text("Локальный сетевой стенд доступен только в debug."); TextButton(onClick = onBack) { Text("Назад") } } }
