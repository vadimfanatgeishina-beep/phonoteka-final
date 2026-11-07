package com.phonoteka.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonoteka.viewmodel.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEEBFC),
    onPrimaryContainer = Color(0xFF282631),
    secondary = Color(0xFF5C4FAE),
    onSecondary = Color.White,
    background = Color(0xFFF5F4F1),
    onBackground = Color(0xFF282631),
    surface = Color.White,
    onSurface = Color(0xFF282631),
    error = Color(0xFFC0392B),
    onError = Color.White,
    surfaceVariant = Color(0xFFE5E2E9),
    onSurfaceVariant = Color(0xFF736F7F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5A0FF),
    onPrimary = Color(0xFF1A0E4A),
    primaryContainer = Color(0xFF3A2A6E),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Color(0xFF9F8FE5),
    onSecondary = Color(0xFF1A0E4A),
    background = Color(0xFF1A181E),
    onBackground = Color(0xFFE5E2E9),
    surface = Color(0xFF252329),
    onSurface = Color(0xFFE5E2E9),
    error = Color(0xFFFF6B5B),
    onError = Color(0xFF1A0E0D),
    surfaceVariant = Color(0xFF3D3A43),
    onSurfaceVariant = Color(0xFFB5B0BF)
)

private val PhonotekaTypography = Typography().copy(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
)

private val PhonotekaShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

data class ExtendedColors(val success: Color, val warning: Color, val headerStart: Color, val headerEnd: Color)

private val LightExtended = ExtendedColors(Color(0xFF21735A), Color(0xFF946320), Color(0xFF6750A4), Color(0xFF8C6ED5))
private val DarkExtended = ExtendedColors(Color(0xFF74D3B1), Color(0xFFFFC36A), Color(0xFF3A2A6E), Color(0xFF6750A4))
val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

@Composable
fun PhonotekaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    forceDarkContent: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDark = forceDarkContent || when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = when {
        forceDarkContent -> DarkColors
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeMode == ThemeMode.SYSTEM ->
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        isDark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = PhonotekaTypography,
        shapes = PhonotekaShapes,
        content = {
            CompositionLocalProvider(LocalExtendedColors provides if (isDark) DarkExtended else LightExtended) {
                content()
            }
        }
    )
}
