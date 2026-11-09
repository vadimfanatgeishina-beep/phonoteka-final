package com.phonoteka.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import com.phonoteka.PhonotekaApp
import com.phonoteka.ui.screens.QuickAccessRedeemScreen
import com.phonoteka.ui.theme.PhonotekaTheme

/** Uses only a loopback socket inside the emulator; no phone or external network is involved. */
@RunWith(AndroidJUnit4::class)
class QuickAccessDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun qr2IsParsedAndRedeemedOnlyOnce() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        LocalQrAccessServer(context).use { server ->
            val endpoint = server.start()
            val grant = server.issue(DemoRole.ADMIN)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(server.grantUri(grant, endpoint)))
            val link = QuickAccessLinkParser.parse(intent)
            assertNotNull(link)
            assertEquals(grant.token, link!!.ticket)
            assertEquals(endpoint.port, link.port)

            val redeemed = LocalQrAccessServer.redeem("127.0.0.1", endpoint.port, link.ticket)
            assertEquals(DemoRole.ADMIN, redeemed.first)
            assertEquals(grant.actorId, redeemed.second)

            val replay = runCatching {
                LocalQrAccessServer.redeem("127.0.0.1", endpoint.port, link.ticket)
            }
            assertTrue("A spent QR2 must fail", replay.isFailure)
        }
    }

    @Test fun malformedQuickAccessLinkIsRejected() {
        val forged = Intent(Intent.ACTION_VIEW,
            Uri.parse("phonoteka://quick-access?host=127.0.0.1&port=1234&ticket=short"))
        assertNull(QuickAccessLinkParser.parse(forged))
    }

    @Test fun qr2OpensAnActualTimedRoleSession() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhonotekaApp
        app.localAuth.lock()
        app.roleSession.resetToEmployee()
        LocalQrAccessServer(app).use { server ->
            val endpoint = server.start()
            val grant = server.issue(DemoRole.ADMIN)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(server.grantUri(grant, endpoint)))
            val link = QuickAccessLinkParser.parse(intent)!!
            var opened = false
            compose.setContent { PhonotekaTheme {
                QuickAccessRedeemScreen(app, link) { opened = true }
            } }
            compose.onNodeWithText("Подтвердить быстрый вход").performClick()
            compose.waitUntil(10_000) { app.roleSession.activeQuickAccess?.role == DemoRole.ADMIN }
            compose.onNodeWithText("Открыть приложение").assertExists().performClick()
            compose.runOnIdle { assertTrue(opened) }
            assertTrue(app.localAuth.isSessionActive())
        }
        app.localAuth.lock()
        app.roleSession.resetToEmployee()
    }
}
