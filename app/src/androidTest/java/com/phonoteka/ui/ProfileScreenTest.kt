package com.phonoteka.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.phonoteka.ui.screens.ProfileScreen
import com.phonoteka.ui.screens.ProfileStats
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.ProfileData
import com.phonoteka.viewmodel.ProfileStore
import com.phonoteka.viewmodel.ProfileViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private class MemoryProfileStore : ProfileStore {
    var saved = ProfileData()
    override suspend fun read() = saved
    override suspend fun write(data: ProfileData) { saved = data }
}

class ProfileScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewAndEditorAreSeparateAndSavePersistsNickname() {
        val store = MemoryProfileStore()
        val model = ProfileViewModel(store)
        compose.setContent {
            PhonotekaTheme {
                ProfileScreen(model, ProfileStats(7, 4, 1), "ADMIN", true, onBack = {})
            }
        }
        compose.onNodeWithTag("profileEdit").assertExists()
        compose.onNodeWithTag("profileName").assertDoesNotExist()
        compose.onNodeWithText("Главный администратор").assertExists()
        compose.onNodeWithText("Скачано").assertExists()

        compose.onNodeWithTag("profileEdit").performClick()
        compose.onNodeWithTag("profileName").performTextReplacement("Михаил")
        compose.onNodeWithTag("profileSave").performClick()
        compose.waitUntil(5_000) { store.saved.name == "Михаил" }
        compose.onNodeWithTag("profileEdit").assertExists()
        compose.onNodeWithText("Михаил").assertExists()
        assertEquals("Михаил", store.saved.name)
    }

    @Test fun cancelDoesNotPersistDraft() {
        val store = MemoryProfileStore()
        val model = ProfileViewModel(store)
        compose.setContent {
            PhonotekaTheme {
                ProfileScreen(model, ProfileStats(0, 0, 0), "EMPLOYEE", true, onBack = {})
            }
        }
        compose.onNodeWithTag("profileEdit").performClick()
        compose.onNodeWithTag("profileName").performTextReplacement("Временное имя")
        compose.onNodeWithTag("profileCancel").performClick()
        compose.onNodeWithText(ProfileData.DEFAULT_NAME).assertExists()
        assertEquals(ProfileData.DEFAULT_NAME, store.saved.name)
    }
}
