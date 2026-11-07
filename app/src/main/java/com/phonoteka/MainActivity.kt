package com.phonoteka

import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.SideEffect
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.phonoteka.security.LocalAuthManager
import com.phonoteka.security.BiometricSessionFlow
import com.phonoteka.security.BiometricSessionAction
import com.phonoteka.security.BiometricSessionTicket
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.phonoteka.ui.Navigation
import com.phonoteka.ui.screens.AuthGateScreen
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.PlayerViewModel
import com.phonoteka.viewmodel.SettingsViewModel

/**
 * Главная и единственная Activity.
 * Использует ComponentActivity + Jetpack Compose (edge-to-edge).
 *
 * Здесь создаются два activity-scoped ViewModel:
 *  - [SettingsViewModel] — тёмная тема (DataStore);
 *  - [PlayerViewModel] — подключение к сервису воспроизведения.
 */
class MainActivity : FragmentActivity() {
    private lateinit var authManager: LocalAuthManager
    private var sessionUnlocked by mutableStateOf(false)
    private var backgroundAt = 0L
    private lateinit var biometricFlow: BiometricSessionFlow
    private var biometricPrompt: BiometricPrompt? = null
    private var biometricTicket: BiometricSessionTicket? = null
    private var biometricBusy by mutableStateOf(false)
    private var biometricSaved by mutableStateOf(false)
    private var biometricMessage by mutableStateOf<String?>(null)
    private var foregroundEpoch = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authApp = applicationContext as PhonotekaApp
        authManager = authApp.localAuth
        biometricFlow = BiometricSessionFlow(authApp.lab13MockSession, authApp.biometricRefresh, android.os.SystemClock::elapsedRealtime)
        biometricSaved = authApp.biometricRefresh.hasSavedRefresh()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        com.phonoteka.labs.LabDeepLinks.offer(intent?.data) // ЛР6 deep link (debug-фильтр)

        setContent {
            val app = applicationContext as PhonotekaApp
            val settingsViewModel: SettingsViewModel = viewModel()
            val playerViewModel: PlayerViewModel = viewModel()
            val settings by settingsViewModel.state.collectAsState()

            SideEffect {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            LaunchedEffect(sessionUnlocked) {
                while (sessionUnlocked) {
                    val remaining = authManager.remainingFreshMillis()
                    if (remaining == 0L) { authManager.lock(); app.lab13MockSession.forgetAccessSession(); biometricSaved = app.biometricRefresh.hasSavedRefresh(); sessionUnlocked = false; break }
                    kotlinx.coroutines.delay(remaining)
                }
            }

            PhonotekaTheme(
                themeMode = settings.themeMode,
                forceDarkContent = settings.forceDarkContent
            ) {
                androidx.compose.material3.Surface {
                    if (sessionUnlocked && authManager.isFreshAuthentication()) {
                        Navigation(
                            repository = app.repository,
                            settingsViewModel = settingsViewModel,
                            playerViewModel = playerViewModel,
                            authManager = authManager,
                            onLock = {
                                authManager.lock(); app.lab13MockSession.forgetAccessSession(); app.invalidateProtectedSession(); app.roleSession.resetToEmployee(); sessionUnlocked = false
                                biometricSaved = app.biometricRefresh.hasSavedRefresh()
                            }
                        )
                    } else {
                        sessionUnlocked = false
                        val biometricAvailable = BiometricManager.from(this@MainActivity)
                            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
                        AuthGateScreen(
                            auth = authManager,
                            repository = app.lab13MockSession,
                            biometricAvailable = biometricAvailable,
                            onBiometric = { showBiometricPrompt(BiometricSessionAction.RESTORE) },
                            onUnlock = {
                                sessionUnlocked = true
                                if (biometricAvailable && !app.biometricRefresh.hasSavedRefresh()) showBiometricPrompt(BiometricSessionAction.ENROLL)
                            },
                            biometricSaved = biometricSaved,
                            biometricBusy = biometricBusy,
                            biometricMessage = biometricMessage
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.phonoteka.labs.LabDeepLinks.offer(intent.data)
    }

    override fun onPause() {
        backgroundAt = android.os.SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        biometricSaved = (application as PhonotekaApp).biometricRefresh.hasSavedRefresh()
        if (backgroundAt != 0L && android.os.SystemClock.elapsedRealtime() - backgroundAt >= 60_000L) {
            (application as PhonotekaApp).markRiskyResume()
        }
        if (backgroundAt != 0L && android.os.SystemClock.elapsedRealtime() - backgroundAt >= 5 * 60_000L) {
            authManager.lock()
            (application as PhonotekaApp).lab13MockSession.forgetAccessSession()
            biometricSaved = (application as PhonotekaApp).biometricRefresh.hasSavedRefresh()
            sessionUnlocked = false
        }
        backgroundAt = 0L
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        foregroundEpoch++
        biometricFlow.cancel()
        biometricPrompt?.cancelAuthentication()
        biometricTicket = null; biometricBusy = false
        super.onStop()
    }

    private fun showBiometricPrompt(action: BiometricSessionAction) {
        if (biometricBusy || isFinishing || isDestroyed) return
        if (action == BiometricSessionAction.ENROLL && !authManager.isFreshAuthentication()) return
        val app = application as PhonotekaApp
        val epoch = foregroundEpoch
        biometricMessage = null
        val ticket = try { biometricFlow.prepare(action) } catch (_: Exception) {
            biometricMessage = "Биометрический ключ/сессия недоступны. Используйте системную аутентификацию + PIN или новый учебный вход."
            runCatching { authManager.recordAudit("biometric_prepare_failed") }
            return
        }
        biometricTicket = ticket; biometricBusy = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (biometricTicket !== ticket || foregroundEpoch != epoch) return
                biometricTicket = null // Duplicate callbacks cannot start a second refresh.
                lifecycleScope.launch {
                    try {
                        val completed = withContext(Dispatchers.IO) { biometricFlow.complete(ticket, result.cryptoObject?.cipher) }
                        if (foregroundEpoch != epoch || isFinishing || isDestroyed) return@launch
                        biometricSaved = app.biometricRefresh.hasSavedRefresh()
                        if (completed == BiometricSessionAction.RESTORE) {
                            authManager.markRestoredBiometricSession()
                            sessionUnlocked = true
                            // The saved token was consumed. A SEPARATE optional encryption prompt
                            // seals the rotated refresh; cancelling it preserves the valid current session.
                            biometricBusy = false
                            showBiometricPrompt(BiometricSessionAction.ENROLL)
                        } else biometricMessage = "Биометрическое восстановление включено для текущей учебной сессии."
                    } catch (_: Exception) {
                        if (foregroundEpoch == epoch) {
                            biometricSaved = app.biometricRefresh.hasSavedRefresh()
                            biometricMessage = "Восстановление/сохранение не завершено. Доступ биометрией не предоставлен; используйте системный вход + PIN."
                            runCatching { authManager.recordAudit("biometric_session_failed") }
                        }
                    } finally {
                        if (biometricTicket == null) biometricBusy = false
                    }
                }
            }
            override fun onAuthenticationFailed() {
                runCatching { authManager.recordAudit("biometric_not_recognized") }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (biometricTicket !== ticket) return
                biometricFlow.cancel(); biometricTicket = null; biometricBusy = false
                biometricMessage = "Биометрическое действие отменено или недоступно. Можно использовать PIN/системный вход."
                runCatching { authManager.recordAudit("biometric_cancel_or_error") }
            }
        })
        biometricPrompt = prompt
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (action == BiometricSessionAction.RESTORE) "Восстановить учебную сессию" else "Сохранить сессию для биометрии")
            .setSubtitle(if (action == BiometricSessionAction.RESTORE) "Расшифрование refresh token; затем локальная проверка и rotation" else "Необязательно: зашифровать текущий refresh token отдельным biometric key")
            .setNegativeButtonText("Отмена")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
        try { prompt.authenticate(info, BiometricPrompt.CryptoObject(ticket.cipher)) }
        catch (_: Exception) { biometricFlow.cancel(); biometricTicket = null; biometricBusy = false; biometricMessage = "Биометрия недоступна; используйте системный вход + PIN." }
    }
}
