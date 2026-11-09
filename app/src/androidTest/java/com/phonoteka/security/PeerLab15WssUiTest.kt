package com.phonoteka.security

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonoteka.ui.screens.Lab15PeerScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The phone-only fixture must be reachable and show a scannable, time-limited invite. */
@RunWith(AndroidJUnit4::class)
class PeerLab15WssUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hostButtonStartsRealServerAndShowsQr() {
        compose.setContent { Lab15PeerScreen(onBack = {}) }
        compose.onNodeWithTag("peer-host").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("peer-qr").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("peer-qr").assertIsDisplayed()
    }
}
