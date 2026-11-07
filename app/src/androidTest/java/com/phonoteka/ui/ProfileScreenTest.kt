package com.phonoteka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.phonoteka.ui.components.ButtonState
import com.phonoteka.ui.components.StatusButton
import com.phonoteka.ui.screens.ProfileActions
import com.phonoteka.ui.screens.ProfileScreenContent
import com.phonoteka.ui.screens.ProfileStats
import com.phonoteka.ui.screens.ProfileWidthClass
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.ProfileUiState
import com.phonoteka.viewmodel.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/** ЛР8, задание 3: ERROR-кнопка, смена темы меняет цвета, Expanded ≠ Compact. */
class ProfileScreenTest {
    @get:Rule val compose = createComposeRule()
    private val noActions = ProfileActions({}, {}, {}, {}, {}, {}, {}, {})

    @Test fun errorStatusButtonAnnouncesErrorAndStaysClickable() {
        var clicks = 0
        compose.setContent { PhonotekaTheme { StatusButton(text = "Повторить", state = ButtonState.ERROR, onClick = { clicks++ }) } }
        compose.onNodeWithTag("status-button")
            .assert(SemanticsMatcher("описание содержит ошибку") { node ->
                val desc = node.config.getOrElse(SemanticsProperties.StateDescription) { "" } +
                    node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
                desc.contains("Ошибка", ignoreCase = true)
            })
            .performClick()
        assertEquals(1, clicks)
    }

    @Test fun themeSwitchChangesColors() {
        var mode by mutableStateOf(ThemeMode.LIGHT)
        var background = Color.Unspecified
        compose.setContent {
            PhonotekaTheme(themeMode = mode) {
                background = MaterialTheme.colorScheme.background
                ProfileScreenContent(ProfileUiState(loaded = true), ProfileStats(1, 0, 0), mode, false,
                    ProfileActions({}, {}, {}, {}, {}, {}, { mode = it }, {}), ProfileWidthClass.COMPACT)
            }
        }
        compose.waitForIdle()
        val light = background
        compose.onNodeWithText("Тёмная").performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.DARK, mode)
        assertNotEquals(light, background)
    }

    @Test fun expandedHasTwoColumnsCompactHasOne() {
        var wc by mutableStateOf(ProfileWidthClass.COMPACT)
        compose.setContent {
            PhonotekaTheme(themeMode = ThemeMode.LIGHT) {
                ProfileScreenContent(ProfileUiState(loaded = true), ProfileStats(1, 0, 0), ThemeMode.LIGHT, false, noActions, wc)
            }
        }
        assertEquals(1, compose.onAllNodes(hasTestTag("profileColumn")).fetchSemanticsNodes().size)
        wc = ProfileWidthClass.EXPANDED
        compose.waitForIdle()
        assertEquals(2, compose.onAllNodes(hasTestTag("profileColumn")).fetchSemanticsNodes().size)
    }
}
