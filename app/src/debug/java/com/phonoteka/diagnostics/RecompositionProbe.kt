package com.phonoteka.diagnostics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import android.os.SystemClock
object RecompositionProbe {
    private val allowed = setOf("TrackCard", "TrackListScreen", "HomeScreen", "TaskRow", "TaskTrackerScreen", "ProfileScreen")
    private var enabled = false
    private var started = 0L
    private val counts = mutableMapOf<String, Long>()
    @Synchronized fun start() { counts.clear(); started = SystemClock.elapsedRealtime(); enabled = true }
    @Synchronized fun record(name: String) { if (enabled && name in allowed) counts[name] = (counts[name] ?: 0) + 1 }
    @Synchronized fun stopCsv(): ByteArray {
        check(enabled) { "No active measurement window" }
        enabled = false
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)
        return ("component,committed_compositions,elapsed_ms\n" + allowed.sorted().joinToString("\n") { "$it,${counts[it] ?: 0},$elapsed" } + "\n").toByteArray()
    }
}
@Composable fun RecordRecomposition(name: String) { SideEffect { RecompositionProbe.record(name) } }
