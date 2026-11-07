package com.phonoteka.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.security.MessageDigest

/** Чистая политика (JVM-тестируемая): что и когда очищать. */
object ClipboardClearPolicy {
    const val CLEAR_AFTER_MS = 30_000L
    fun fingerprint(text: CharSequence): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * Очищаем, если в буфере всё ещё наш текст. Если буфер нечитаем (Android 10+ запрещает чтение в фоне) —
     * очищаем в любом случае: лучше потерять чужой фрагмент, чем оставить текст заметки.
     * Если видно, что там уже другой текст, — не трогаем.
     */
    fun shouldClear(currentFingerprint: String?, ours: String, elapsedMs: Long): Boolean =
        elapsedMs >= CLEAR_AFTER_MS && (currentFingerprint == null || currentFingerprint == ours)
}

/** ЛР16 hardening: копирование текста заметки с пометкой sensitive и автоочисткой через 30 с. */
object SensitiveClipboard {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pending: Runnable? = null

    fun copy(context: Context, text: String, label: String = "Заметка") {
        val app = context.applicationContext
        val manager = app.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        } else {
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        manager.setPrimaryClip(clip)
        val ours = ClipboardClearPolicy.fingerprint(text)
        val copiedAt = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            pending?.let(handler::removeCallbacks)
            pending = Runnable { clearIfOurs(app, ours, copiedAt) }.also { handler.postDelayed(it, ClipboardClearPolicy.CLEAR_AFTER_MS) }
        }
    }

    private fun clearIfOurs(context: Context, ours: String, copiedAt: Long) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val current = runCatching { manager.primaryClip?.getItemAt(0)?.text?.let(ClipboardClearPolicy::fingerprint) }.getOrNull()
        val elapsed = android.os.SystemClock.elapsedRealtime() - copiedAt
        if (!ClipboardClearPolicy.shouldClear(current, ours, elapsed)) return
        if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("", ""))
        synchronized(this) { pending = null }
    }
}
