package com.phonoteka
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonoteka.security.DemoScope
import com.phonoteka.ui.components.RiskStepUpPrompt
import com.phonoteka.ui.screens.TrackInviteScreen
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.TrackInviteViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Explicit internal Activity (QR-приглашения треков): exported=false. Direct adb/foreign intents must not open it. */
class TrackInviteActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val app = application as PhonotekaApp
        val allowed = runCatching { app.requireAuthority(DemoScope.LIBRARY_READ, "invite.open") }.isSuccess
        if (!allowed) { finish(); return }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (!app.localAuth.isFreshAuthentication()) { app.riskStepUp.invalidate(); finish(); break }
                    delay(500)
                }
            }
        }
        setContent {
            PhonotekaTheme {
                Surface {
                    val model: TrackInviteViewModel = viewModel(factory = TrackInviteViewModel.Factory(app.trackInvites))
                    TrackInviteScreen(model, onBack = { finish() })
                    RiskStepUpPrompt(app)
                }
            }
        }
    }
}
