package com.phonoteka

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.phonoteka.security.EmployeeInviteLink
import com.phonoteka.security.InviteLinkError
import com.phonoteka.security.InviteLinkResult
import com.phonoteka.ui.screens.InviteRegistrationScreen
import com.phonoteka.ui.theme.PhonotekaTheme

/**
 * ЛР14: ЕДИНСТВЕННАЯ функциональная exported-activity (MainActivity открыта только ради LAUNCHER).
 * Принимает только ACTION_VIEW phonoteka://invite?token=… и открывает РЕГИСТРАЦИЮ, а не внутренние функции.
 * Ссылка проверяется до использования; токен не логируется и не показывается.
 */
class InviteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val app = application as PhonotekaApp
        val result = parse(intent)
        val event = when (result) {
            is InviteLinkResult.Valid -> "invite_link_accepted"
            is InviteLinkResult.Invalid -> "invite_link_rejected:${result.error.name}"
        }
        runCatching { app.localAuth.recordAudit(event) }
        setContent {
            PhonotekaTheme {
                Surface {
                    InviteRegistrationScreen(
                        link = result,
                        onGoToLogin = {
                            startActivity(Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            finish()
                        },
                        onClose = { finish() },
                        onRegistered = { runCatching { app.localAuth.recordAudit("invite_registration_created") } },
                        onRejected = { reason -> runCatching { app.localAuth.recordAudit("invite_registration_rejected:$reason") } }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Новая ссылка не подменяет данные уже открытой формы: экран пересоздаётся с повторной проверкой.
        setIntent(intent)
        recreate()
    }

    private fun parse(intent: Intent?): InviteLinkResult {
        if (intent?.action != Intent.ACTION_VIEW) return InviteLinkResult.Invalid(InviteLinkError.MALFORMED)
        // Лишние extras от чужого приложения не читаются вообще: контракт — только data URI.
        return EmployeeInviteLink.parse(intent.dataString)
    }
}
