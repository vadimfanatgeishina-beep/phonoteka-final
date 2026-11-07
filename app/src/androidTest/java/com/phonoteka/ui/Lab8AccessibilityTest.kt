package com.phonoteka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import com.phonoteka.ui.components.ButtonState
import com.phonoteka.ui.components.AppButton
import com.phonoteka.ui.components.AppButtonStyle
import com.phonoteka.ui.components.StatusButton
import com.phonoteka.ui.screens.HomeScreen
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class Lab8AccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appButtonHonorsStyleEnabledAndClickContract() {
        var clicks = 0
        compose.setContent {
            PhonotekaTheme {
                androidx.compose.foundation.layout.Column {
                    AppButton(text = "Сохранить", onClick = { clicks++ }, modifier = Modifier.testTag("app-primary"))
                    AppButton(text = "Удалить", onClick = { clicks += 10 }, style = AppButtonStyle.OUTLINED,
                        enabled = false, modifier = Modifier.testTag("app-outlined-disabled"))
                }
            }
        }
        compose.onNodeWithTag("app-primary").performClick()
        compose.onNodeWithTag("app-outlined-disabled").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun errorStatusButtonIsAnnouncedAndDisabled() {
        var clicks = 0
        compose.setContent {
            PhonotekaTheme {
                StatusButton("Сохранить", ButtonState.ERROR, onClick = { clicks++ })
            }
        }
        compose.onNodeWithTag("status-button")
            .assertIsNotEnabled().performClick()
        val description = compose.onNodeWithTag("status-button").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("Ошибка: повторите действие"), description)
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun themeChangeChangesColorsWithoutReplacingContent() {
        var mode by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            PhonotekaTheme(themeMode = mode) {
                Text(MaterialTheme.colorScheme.background.toString(), Modifier.testTag("background"))
                androidx.compose.material3.Button(onClick = { mode = ThemeMode.DARK }) { Text("Switch") }
            }
        }
        saveThemeEvidence("lab8-light.png")
        val before = compose.onNodeWithTag("background").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        compose.onNodeWithText("Switch").performClick()
        val after = compose.onNodeWithTag("background").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        assertNotEquals(before, after)
        saveThemeEvidence("lab8-dark.png")
    }

    private fun saveThemeEvidence(name: String) {
        // Isolated component activity: no PIN, library, tokens or recovery code in the screenshot.
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.filesDir, "lab8-theme-evidence").apply { mkdirs() }
        java.io.File(directory, name).outputStream().use { stream ->
            check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    @Test fun homeChangesStructureBetweenCompactAndExpanded() {
        var width by mutableStateOf(360)
        compose.setContent {
            val configuration = Configuration().apply { screenWidthDp = width }
            androidx.compose.runtime.CompositionLocalProvider(LocalConfiguration provides configuration) {
                HomeScreen(0, 0, 0, 0, {}, {}, modifier = Modifier.width(width.dp))
            }
        }
        compose.onNodeWithTag("home-compact").assertExists()
        compose.onNodeWithTag("home-expanded").assertDoesNotExist()
        compose.runOnIdle { width = 800 }
        compose.onNodeWithTag("home-expanded").assertExists()
        compose.onNodeWithTag("home-compact").assertDoesNotExist()
    }
}
