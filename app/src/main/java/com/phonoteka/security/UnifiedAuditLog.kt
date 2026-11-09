package com.phonoteka.security

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Источник события — откуда пришла запись в журнале. */
enum class AuditSource(val wire: String) {
    AUTH("auth"),
    RECOVERY("recovery"),
    QR("qr"),
    NETWORK("network"),
    AUDIT("audit"),
    LIBRARY("library"),
    NOTES("notes");

    companion object {
        fun fromWire(value: String): AuditSource? = entries.firstOrNull { it.wire == value }
    }
}

/** Тип события журнала. */
enum class AuditEventType(val wire: String) {
    LOGIN("login"),
    LOGIN_FAILED("login_failed"),
    LOGOUT("logout"),
    LOCK("lock"),
    PIN_SET("pin_set"),
    PIN_CHANGED("pin_changed"),
    RECOVERY("recovery"),
    SESSION("session"),
    STEP_UP("step_up"),
    QR_SCAN("qr_scan"),
    QR_ISSUE("qr_issue"),
    QR_REJECT("qr_reject"),
    ACCESS_ALLOWED("access_allowed"),
    ACCESS_DENIED("access_denied"),
    NETWORK("network"),
    WSS("wss"),
    DOWNLOAD("download"),
    NOTES_IMPORT("notes_import"),
    NOTES_EXPORT("notes_export"),
    AUDIT_CHECK("audit_check"),
    RASP("rasp");

    companion object {
        fun fromWire(value: String): AuditEventType? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Одна запись единого журнала ЛР13–16.
 *
 * Секреты в запись не попадают: OTP, PIN, пароль, полный токен и тело приватных
 * запросов не записываются. IP/геолокация заполняются ТОЛЬКО если они реально
 * доступны; иначе остаются пустыми и честно отображаются как «недоступно».
 */
data class AuditEvent(
    val type: AuditEventType,
    val source: AuditSource,
    val timestamp: Long,
    val userId: String,
    val role: String,
    val action: String,
    val subject: String,
    val result: String,
    val reason: String? = null,
    val sessionId: String? = null,
    val device: String? = null,
    val ip: String? = null,
    val geo: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("type", type.wire)
        put("source", source.wire)
        put("timestamp", timestamp)
        put("user", userId)
        put("role", role)
        put("action", action)
        put("object", subject)
        put("result", result)
        reason?.let { put("reason", it) }
        sessionId?.let { put("session", it) }
        device?.let { put("device", it) }
        ip?.let { put("ip", it) }
        geo?.let { put("geo", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): AuditEvent? {
            val type = AuditEventType.fromWire(json.optString("type")) ?: return null
            val source = AuditSource.fromWire(json.optString("source")) ?: return null
            return AuditEvent(
                type = type,
                source = source,
                timestamp = json.optLong("timestamp", 0L),
                userId = json.optString("user", "unverified"),
                role = json.optString("role", "UNVERIFIED"),
                action = json.optString("action", ""),
                subject = json.optString("object", ""),
                result = json.optString("result", ""),
                reason = if (json.has("reason")) json.optString("reason") else null,
                sessionId = if (json.has("session")) json.optString("session") else null,
                device = if (json.has("device")) json.optString("device") else null,
                ip = if (json.has("ip")) json.optString("ip") else null,
                geo = if (json.has("geo")) json.optString("geo") else null
            )
        }
    }
}

/**
 * Единый журнал событий для ЛР13 (вход), ЛР14 (QR/роли), ЛР15 (сеть/WSS) и ЛР16 (аудит).
 *
 * Хранится как JSONL с цепочкой хешей: каждая строка содержит хеш предыдущей,
 * поэтому удаление или правка записи в середине файла обнаруживается.
 * Целостность проверяется при чтении — «журнал показывает правду» проверяемо.
 */
class UnifiedAuditLog(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "audit")
    private val file = File(directory, "unified.jsonl")
    private val lock = locks.computeIfAbsent(directory.absolutePath) { Any() }

    /** Идентификатор текущей пользовательской сессии — стабильный между событиями. */
    @Volatile var sessionId: String? = null

    /** Читаемая модель устройства без серийного номера, IMEI или рекламного ID. */
    val deviceLabel: String by lazy {
        val manufacturer = Build.MANUFACTURER.trim().takeIf { it.isNotBlank() && it != "unknown" }
        val model = Build.MODEL.trim().takeIf { it.isNotBlank() && it != "unknown" }
        val name = listOfNotNull(manufacturer, model).distinct().joinToString(" ")
            .ifBlank { "Android-устройство" }
        "$name · Android ${Build.VERSION.RELEASE}"
    }

    /**
     * IP-адрес доступен только из локального сокета самого устройства.
     * Он не геолокация и не идентификатор человека; в офлайн-режиме остаётся пустым.
     */
    fun localIpAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** Записывает событие. Ошибка записи не должна ломать пользовательский сценарий. */
    fun record(event: AuditEvent): Boolean = try {
        synchronized(lock) {
            directory.mkdirs()
            val previous = lastHash()
            val payload = event.toJson().apply { put("prev", previous) }
            val hash = sha256(payload.toString())
            payload.put("hash", hash)
            FileOutputStream(file, true).use { out ->
                out.write(payload.toString().toByteArray(Charsets.UTF_8))
                out.write(10)
                out.fd.sync()
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Удобная запись с подстановкой текущей сессии и устройства. */
    fun record(
        type: AuditEventType,
        source: AuditSource,
        userId: String,
        role: String,
        action: String,
        subject: String,
        result: String,
        reason: String? = null,
        includeIp: Boolean = false
    ): Boolean {
        val ip = if (includeIp) localIpAddress() else null
        return record(
            AuditEvent(
                type = type,
                source = source,
                timestamp = now(),
                userId = userId,
                role = role,
                action = action,
                subject = subject,
                result = result,
                reason = reason,
                sessionId = sessionId,
                device = deviceLabel,
                ip = ip,
                geo = null // геолокация не собирается: нет разрешения и нет источника
            )
        )
    }

    /** Читает журнал и проверяет цепочку хешей. Возвращает null при нарушении целостности. */
    fun readVerified(): List<AuditEvent>? {
        if (!file.exists()) return emptyList()
        val lines = try { file.readLines(Charsets.UTF_8).filter { it.isNotBlank() } } catch (_: Exception) { return null }
        val events = mutableListOf<AuditEvent>()
        var expectedPrev = "0".repeat(64)
        for (line in lines) {
            val json = try { JSONObject(line) } catch (_: Exception) { return null }
            val hash = json.optString("hash")
            if (hash.length != 64) return null
            if (json.optString("prev") != expectedPrev) return null
            val copy = JSONObject(line)
            copy.remove("hash")
            if (sha256(copy.toString()) != hash) return null
            expectedPrev = hash
            val event = AuditEvent.fromJson(json) ?: return null
            events += event
        }
        return events
    }

    /** Последние записи для журнала в интерфейсе. */
    fun recent(limit: Int = 200): List<AuditEvent> {
        val events = readVerified() ?: return emptyList()
        val boundary = events.indexOfLast { it.source == AuditSource.AUDIT && it.action == "clear_view" }
        return events.drop(if (boundary < 0) 0 else boundary).takeLast(limit)
    }

    /** Очищает видимый список, сохраняя полную цепочку для проверки и экспорта. */
    fun clearHistory(): Int = synchronized(lock) {
        check(readVerified() != null) { "Audit integrity failed; history cannot be hidden" }
        val removed = recent(Int.MAX_VALUE).size
        check(record(AuditEvent(
            type = AuditEventType.AUDIT_CHECK,
            source = AuditSource.AUDIT,
            timestamp = now(),
            userId = "local-user",
            role = "USER",
            action = "clear_view",
            subject = "unified audit log",
            result = "CLEARED",
            reason = "Пользователь очистил видимый список; скрыто записей: $removed. Полная история сохранена.",
            sessionId = sessionId,
            device = deviceLabel
        ))) { "Could not record audit clear marker" }
        removed
    }

    /** Признак нарушения целостности журнала (для ЛР16). */
    fun isIntegrityIntact(): Boolean = readVerified() != null

    private fun lastHash(): String {
        if (!file.exists()) return "0".repeat(64)
        val last = try { file.readLines(Charsets.UTF_8).lastOrNull { it.isNotBlank() } } catch (_: Exception) { null }
            ?: return "0".repeat(64)
        return try { JSONObject(last).optString("hash", "0".repeat(64)) } catch (_: Exception) { "0".repeat(64) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()

        /** Строка для отображения, когда данных нет — честно, без выдуманных значений. */
        const val UNAVAILABLE = "недоступно в офлайн-режиме"
    }
}
