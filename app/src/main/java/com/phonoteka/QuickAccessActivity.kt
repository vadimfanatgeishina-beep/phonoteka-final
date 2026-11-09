package com.phonoteka

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.fragment.app.FragmentActivity
import com.phonoteka.security.QuickAccessLinkParser
import com.phonoteka.ui.screens.QuickAccessRedeemScreen
import com.phonoteka.ui.theme.PhonotekaTheme

/** QR2 entry point. A URI only opens the confirmation screen; the server must redeem its ticket. */
class QuickAccessActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val app = application as PhonotekaApp
        val link = QuickAccessLinkParser.parse(intent)
        setContent {
            PhonotekaTheme {
                Surface {
                    QuickAccessRedeemScreen(app, link, onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}
