package com.phonoteka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonoteka.security.BuildMode
import com.phonoteka.security.SecurityProfile

/**
 * Явная подпись режима сборки.
 *
 * Нужна, чтобы на стенде не перепутать уязвимый и защищённый APK: подпись видна
 * на главном экране и в разделе «О версии» во всех сборках.
 */
@Composable
fun BuildModeBadge(
    modifier: Modifier = Modifier,
    mode: BuildMode = SecurityProfile.mode
) {
    val background = when (mode) {
        BuildMode.VULNERABLE -> MaterialTheme.colorScheme.errorContainer
        BuildMode.HARDENED -> MaterialTheme.colorScheme.primaryContainer
    }
    val foreground = when (mode) {
        BuildMode.VULNERABLE -> MaterialTheme.colorScheme.onErrorContainer
        BuildMode.HARDENED -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("buildModeBadge")
            .semantics { contentDescription = "Режим сборки: ${mode.label}" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = mode.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = foreground
        )
        Text(
            text = "· сборка",
            style = MaterialTheme.typography.labelSmall,
            color = foreground.copy(alpha = 0.8f)
        )
    }
}

/** Компактный индикатор режима для мест без места на плашку. */
@Composable
fun BuildModeDot(modifier: Modifier = Modifier, mode: BuildMode = SecurityProfile.mode) {
    val color: Color = when (mode) {
        BuildMode.VULNERABLE -> MaterialTheme.colorScheme.error
        BuildMode.HARDENED -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("buildModeDot"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = mode.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
