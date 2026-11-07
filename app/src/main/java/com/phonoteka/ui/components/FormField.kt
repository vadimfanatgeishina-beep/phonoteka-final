package com.phonoteka.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

enum class FormFieldState { IDLE, ERROR, WARNING, DISABLED }

@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    state: FormFieldState = FormFieldState.IDLE,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    message: String? = null,
    singleLine: Boolean = true
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = singleLine,
            enabled = state != FormFieldState.DISABLED,
            isError = state == FormFieldState.ERROR,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (state == FormFieldState.WARNING) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (state == FormFieldState.WARNING) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                errorBorderColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.minimumInteractiveComponentSize().semantics {
                contentDescription?.let { this.contentDescription = it }
                if (state == FormFieldState.ERROR && !message.isNullOrBlank()) error(message)
                if (state == FormFieldState.ERROR) stateDescription = message ?: "Ошибка"
                if (state == FormFieldState.DISABLED) stateDescription = "Недоступно"
                if (state == FormFieldState.WARNING) stateDescription = "Предупреждение"
            }
        )
        if (!message.isNullOrBlank()) {
            Text(
                text = message,
                color = when (state) {
                    FormFieldState.ERROR -> MaterialTheme.colorScheme.error
                    FormFieldState.WARNING -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
