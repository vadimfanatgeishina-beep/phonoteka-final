package com.phonoteka.security

import android.os.Build
import android.os.Debug

data class RuntimeSignals(val debuggerAttached: Boolean, val emulator: Boolean) {
    val suspicious: Boolean get() = debuggerAttached || emulator
}

/** Low-impact educational RASP signals. They are advisory and can be bypassed on a controlled device. */
object RuntimeSignalProbe {
    fun collect(): RuntimeSignals = RuntimeSignals(
        debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger(),
        emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator", ignoreCase = true)
    )
}
