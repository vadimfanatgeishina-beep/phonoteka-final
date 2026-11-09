package com.phonoteka.security

import android.content.Context
import com.phonoteka.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BuildMode {
    VULNERABLE, HARDENED;
    val isVulnerable: Boolean get() = this == VULNERABLE
    val label: String get() = when (this) {
        VULNERABLE -> "УЯЗВИМАЯ сборка (учебный стенд)"
        HARDENED -> "ЗАЩИЩЁННАЯ сборка"
    }
    val shortLabel: String get() = if (this == VULNERABLE) "УЯЗВИМАЯ" else "ЗАЩИЩЁННАЯ"
}

/** Runtime controls for protections whose decision can genuinely change without reinstalling. */
data class RuntimeSecurityControls(
    val otpSingleUse: Boolean,
    val qrTicketSingleUse: Boolean,
    val stepUp: Boolean,
    val freshAuthForLibrary: Boolean
)

enum class RuntimeSecurityControl { OTP_SINGLE_USE, QR_SINGLE_USE, STEP_UP, FRESH_LIBRARY_AUTH }

/**
 * Build-time policy plus persisted lab controls. The vulnerable debug build may turn controls
 * on and off for comparison. The hardened build and all release builds are fail-closed: runtime
 * preferences may strengthen policy but cannot weaken the build's secure defaults.
 */
object SecurityProfile {
    val mode: BuildMode = if (BuildConfig.SECURITY_MODE == "VULNERABLE") BuildMode.VULNERABLE else BuildMode.HARDENED
    private val defaults = RuntimeSecurityControls(
        otpSingleUse = BuildConfig.ENFORCE_OTP_SINGLE_USE,
        qrTicketSingleUse = BuildConfig.ENFORCE_QR_TICKET_SINGLE_USE,
        stepUp = BuildConfig.ENFORCE_STEP_UP,
        freshAuthForLibrary = BuildConfig.REQUIRE_FRESH_AUTH_FOR_LIBRARY
    )
    private val state = MutableStateFlow(defaults)
    val controls: StateFlow<RuntimeSecurityControls> = state.asStateFlow()
    @Volatile private var preferences: android.content.SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("phonoteka_lab_security_controls", Context.MODE_PRIVATE)
        preferences = prefs
        state.value = RuntimeSecurityControls(
            otpSingleUse = effective(prefs, RuntimeSecurityControl.OTP_SINGLE_USE, defaults.otpSingleUse),
            qrTicketSingleUse = effective(prefs, RuntimeSecurityControl.QR_SINGLE_USE, defaults.qrTicketSingleUse),
            stepUp = effective(prefs, RuntimeSecurityControl.STEP_UP, defaults.stepUp),
            freshAuthForLibrary = effective(prefs, RuntimeSecurityControl.FRESH_LIBRARY_AUTH, defaults.freshAuthForLibrary)
        )
    }

    /** Returns false for immutable policy or if the requested state was not accepted. */
    @Synchronized fun setEnabled(control: RuntimeSecurityControl, enabled: Boolean): Boolean {
        val current = state.value
        val defaultEnabled = defaultFor(control)
        val mutableLabBuild = BuildConfig.DEBUG && mode == BuildMode.VULNERABLE
        // Hardened defaults can never be switched off. A vulnerable debug build is the controlled
        // comparison fixture; release builds always keep the compile-time policy.
        if (!mutableLabBuild && defaultEnabled && !enabled) return false
        if (!BuildConfig.DEBUG && enabled != defaultEnabled) return false
        val updated = when (control) {
            RuntimeSecurityControl.OTP_SINGLE_USE -> current.copy(otpSingleUse = enabled)
            RuntimeSecurityControl.QR_SINGLE_USE -> current.copy(qrTicketSingleUse = enabled)
            RuntimeSecurityControl.STEP_UP -> current.copy(stepUp = enabled)
            RuntimeSecurityControl.FRESH_LIBRARY_AUTH -> current.copy(freshAuthForLibrary = enabled)
        }
        if (updated == current) return true
        val prefs = preferences ?: return false
        check(prefs.edit().putBoolean(keyFor(control), enabled).commit())
        state.value = updated
        return true
    }

    val enforceCertificatePinning: Boolean get() = BuildConfig.ENFORCE_CERT_PINNING
    val enforceStepUp: Boolean get() = state.value.stepUp
    val enforceOtpSingleUse: Boolean get() = state.value.otpSingleUse
    val enforceQrTicketSingleUse: Boolean get() = state.value.qrTicketSingleUse
    val requireFreshAuthForLibrary: Boolean get() = state.value.freshAuthForLibrary
    val allowUserCa: Boolean = BuildConfig.ALLOW_USER_CA
    val allowCleartext: Boolean = BuildConfig.ALLOW_CLEARTEXT
    val isRelease: Boolean = !BuildConfig.DEBUG

    fun canEditRuntimeControls(): Boolean = BuildConfig.DEBUG && mode == BuildMode.VULNERABLE
    fun isControlLocked(control: RuntimeSecurityControl): Boolean = !canEditRuntimeControls() && defaultFor(control)

    fun summary(): String = when (mode) {
        BuildMode.VULNERABLE -> "Учебная сборка с локальными фиктивными данными. В debug можно включать защиты по одной и сравнивать результат."
        BuildMode.HARDENED -> "Защищённая сборка: защитные настройки включены и не могут быть отключены из интерфейса."
    }

    private fun effective(prefs: android.content.SharedPreferences, control: RuntimeSecurityControl, default: Boolean): Boolean {
        val stored = prefs.getBoolean(keyFor(control), default)
        return if (isControlLocked(control)) default || stored else stored
    }
    private fun defaultFor(control: RuntimeSecurityControl): Boolean = when (control) {
        RuntimeSecurityControl.OTP_SINGLE_USE -> defaults.otpSingleUse
        RuntimeSecurityControl.QR_SINGLE_USE -> defaults.qrTicketSingleUse
        RuntimeSecurityControl.STEP_UP -> defaults.stepUp
        RuntimeSecurityControl.FRESH_LIBRARY_AUTH -> defaults.freshAuthForLibrary
    }
    private fun keyFor(control: RuntimeSecurityControl): String = when (control) {
        RuntimeSecurityControl.OTP_SINGLE_USE -> "otp_single_use"
        RuntimeSecurityControl.QR_SINGLE_USE -> "qr_single_use"
        RuntimeSecurityControl.STEP_UP -> "step_up"
        RuntimeSecurityControl.FRESH_LIBRARY_AUTH -> "fresh_library_auth"
    }
}
