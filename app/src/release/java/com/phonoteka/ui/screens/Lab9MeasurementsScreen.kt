package com.phonoteka.ui.screens
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
@Composable fun Lab9MeasurementsScreen(onBack: () -> Unit) {
    Text("Диагностические счётчики доступны только в debug")
    TextButton(onClick = onBack) { Text("Назад") }
}
