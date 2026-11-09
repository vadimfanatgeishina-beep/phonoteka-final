package com.phonoteka.identity

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.phonoteka.ui.screens.PhoneAccountScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class PhoneAccountScreenDeviceTest {
    @get:Rule val ui = createComposeRule()

    @Test fun ownerCanStartHostSeeQrLoginAndLogoutThroughActualScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "screen-${UUID.randomUUID()}"
        val changes = AtomicInteger()
        val controller = PhoneAccountsController(context, {}, { changes.incrementAndGet(); Unit }, {}, name)
        try {
            ui.setContent { MaterialTheme { PhoneAccountScreen(controller, {}) } }
            ui.onNodeWithTag("account-host-tab").performClick()
            ui.onNodeWithTag("account-open-host").performScrollTo().performClick()
            ui.waitUntil(10_000) { controller.state.value.hostOpened && !controller.state.value.busy }
            ui.onNodeWithTag("account-owner-login").performScrollTo().performTextInput("owner")
            ui.onNodeWithTag("account-owner-password").performScrollTo().performTextInput("owner-screen-password")
            ui.onNodeWithTag("account-provision").performScrollTo().performClick()
            ui.waitUntil(15_000) { !controller.state.value.needsOwner && !controller.state.value.busy }
            ui.onNodeWithTag("account-start-host").performScrollTo().performClick()
            ui.waitUntil(15_000) { controller.state.value.hostEndpoint != null && !controller.state.value.busy }
            ui.onNodeWithTag("account-pair-qr").performScrollTo().assertIsDisplayed()
            assertNotNull(IdentityPairingCode.parse(controller.state.value.pairing!!.encode()))
            ui.onNodeWithTag("account-use-own-host").performScrollTo().performClick()
            ui.waitUntil(5_000) { controller.state.value.pairedEndpoint != null && !controller.state.value.busy }
            ui.onNodeWithTag("account-login").performScrollTo().performTextInput("owner")
            ui.onNodeWithTag("account-password").performScrollTo().performTextInput("owner-screen-password")
            ui.onNodeWithTag("account-sign-in").performScrollTo().performClick()
            ui.waitUntil(15_000) { controller.state.value.user != null && !controller.state.value.busy }
            assertEquals(IdentityRole.ADMIN, controller.state.value.user!!.role)
            assertEquals(1, controller.state.value.sessions.size)
            ui.onNodeWithTag("account-user").performScrollTo().assertTextContains("owner", substring = true)
            ui.onNodeWithTag("account-revoke-current").performScrollTo().performClick()
            ui.waitUntil(10_000) { controller.state.value.user == null && !controller.state.value.busy }
            ui.onNodeWithTag("account-password").performScrollTo().performTextInput("owner-screen-password")
            ui.onNodeWithTag("account-sign-in").performScrollTo().performClick()
            ui.waitUntil(15_000) { controller.state.value.user != null && !controller.state.value.busy }
            ui.onNodeWithTag("account-logout").performScrollTo().performClick()
            ui.waitUntil(10_000) { controller.state.value.user == null && !controller.state.value.busy }
            assertTrue(changes.get() >= 2)
            val previousEndpoint = controller.state.value.hostEndpoint!!
            controller.invalidate()
            ui.waitUntil(20_000) { !controller.state.value.busy }
            assertNull(controller.state.value.hostEndpoint)
            val disconnected = previousEndpoint.client()
            try {
                assertTrue(runCatching { disconnected.newCall(okhttp3.Request.Builder().url(previousEndpoint.baseUrl + "/me").build())
                    .execute().close() }.isFailure)
            } finally { disconnected.dispatcher.executorService.shutdown(); disconnected.connectionPool.evictAll() }
        } finally {
            controller.invalidate()
            ui.waitUntil(20_000) { !controller.state.value.busy }
            File(context.noBackupFilesDir, "identity/$name").deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("phonoteka.identity.$name.aes.v1") }
        }
    }

    @Test fun pairingRejectsExpiredDuplicatedOrPublicHostQr() {
        val endpoint = IdentityTlsEndpoint("192.168.43.1", 8445, "sha256/" + java.util.Base64.getEncoder().encodeToString(ByteArray(32)))
        val now = System.currentTimeMillis()
        val code = IdentityPairingCode(endpoint, now + 60_000).encode()
        assertEquals(endpoint, IdentityPairingCode.parse(code, now)!!.endpoint)
        assertNull(IdentityPairingCode.parse(code, now + 60_000))
        assertNull(IdentityPairingCode.parse("$code&host=192.168.43.2", now))
        assertNull(IdentityPairingCode.parse(code.replace("192.168.43.1", "8.8.8.8"), now))
    }
}
