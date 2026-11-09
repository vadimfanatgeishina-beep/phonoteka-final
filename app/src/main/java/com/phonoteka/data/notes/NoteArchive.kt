package com.phonoteka.data.notes

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * ЛР11: вложение заметки — картинка (или иной небольшой файл), хранящаяся
 * ВМЕСТЕ с телом заметки внутри зашифрованной ревизии.
 *
 * Данные вложения лежат в base64 внутри JSON ревизии, поэтому они уже под
 * защитой AES-GCM и HMAC-индекса. Отдельные файлы на диске не нужны, и
 * «вложенная картинка» не может потеряться отдельно от заметки.
 */
data class NoteAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    /** Содержимое вложения в base64 (без data:-префикса). */
    val data: String
) {
    val sizeBytes: Int get() = (data.length * 3) / 4
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/** Ограничения вложений: картинка заметки не должна превращать её в архив. */
object AttachmentLimits {
    const val MAX_COUNT = 5
    const val MAX_BYTES = 512 * 1024
    val ALLOWED_MIME = listOf("image/png", "image/jpeg", "image/jpg", "image/webp")
    val FILE_NAME = Regex("[A-Za-z0-9._-]{1,80}")

    fun validate(attachments: List<NoteAttachment>) {
        if (attachments.size > MAX_COUNT) throw NoteException(NoteError.InvalidInput)
        if (attachments.map { it.id }.toSet().size != attachments.size) throw NoteException(NoteError.InvalidInput)
        var total = 0
        for (attachment in attachments) {
            if (!attachment.id.matches(Regex("[A-Za-z0-9_-]{1,40}"))) throw NoteException(NoteError.InvalidInput)
            if (!FILE_NAME.matches(attachment.fileName)) throw NoteException(NoteError.InvalidInput)
            if (attachment.mimeType.lowercase() !in ALLOWED_MIME) throw NoteException(NoteError.InvalidInput)
            if (attachment.data.length > MAX_BYTES * 4 / 3 + 16) throw NoteException(NoteError.TooLarge)
            val decoded = decodeBase64(attachment.data) ?: throw NoteException(NoteError.Corrupted)
            if (decoded.size > MAX_BYTES) throw NoteException(NoteError.TooLarge)
            total += decoded.size
        }
        if (total > MAX_BYTES * MAX_COUNT) throw NoteException(NoteError.TooLarge)
    }

    fun decodeBase64(value: String): ByteArray? = try {
        java.util.Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) { null }

    fun encodeBase64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
}

/**
 * ЛР11: зашифрованный архив заметок.
 *
 * Формат: "PTKA1" + 1 байт длины заголовка + заголовок JSON + зашифрованное тело.
 * Тело — AES/GCM (тот же NoteCrypto), внутри обычный JSON заметок, но с вложениями.
 *
 * Именно этот формат восстанавливает вложенные картинки. Обычный JSON-экспорт
 * (schemaVersion 2) картинки не содержит — UI обязан об этом сказать явно.
 */
class NoteArchiveCodec(private val crypto: NoteCrypto) {
    private val gson = Gson()

    private data class ArchiveHeader(val format: String = FORMAT, val schemaVersion: Int = SCHEMA_VERSION,
        val noteCount: Int = 0, val attachmentCount: Int = 0, val sha256: String = "")
    private data class ArchiveBody(val notes: List<TrackNote> = emptyList())

    /** Собирает зашифрованный архив из заметок вместе с вложениями. */
    fun encode(notes: List<TrackNote>): ByteArray {
        if (notes.map { it.id }.toSet().size != notes.size) throw NoteException(NoteError.InvalidInput)
        for (note in notes) AttachmentLimits.validate(note.attachments)
        val body = gson.toJson(ArchiveBody(notes)).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val header = gson.toJson(ArchiveHeader(noteCount = notes.size,
            attachmentCount = notes.sumOf { it.attachments.size }, sha256 = digest)).toByteArray(Charsets.UTF_8)
        if (header.size > MAX_HEADER) throw NoteException(NoteError.TooLarge)
        val cipher = crypto.encrypt(body)
        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(header.size)
        out.write(header)
        out.write(cipher)
        return out.toByteArray().also { if (it.size > MAX_ARCHIVE) throw NoteException(NoteError.TooLarge) }
    }

    /**
     * Разбирает и ПРОВЕРЯЕТ архив. Любое несоответствие — явная ошибка:
     * повреждённый или неполный архив никогда не считается успешным импортом.
     */
    fun decode(bytes: ByteArray): List<TrackNote> {
        if (bytes.size > MAX_ARCHIVE) throw NoteException(NoteError.TooLarge)
        if (bytes.size < MAGIC.size + 1) throw NoteException(NoteError.Corrupted)
        for (index in MAGIC.indices) if (bytes[index] != MAGIC[index]) throw NoteException(NoteError.InvalidInput)
        var cursor = MAGIC.size
        val headerLength = bytes[cursor++].toInt() and 0xFF
        if (headerLength <= 0 || headerLength > MAX_HEADER) throw NoteException(NoteError.Corrupted)
        if (bytes.size < cursor + headerLength) throw NoteException(NoteError.Corrupted)
        val header = try {
            gson.fromJson(String(bytes, cursor, headerLength, Charsets.UTF_8), ArchiveHeader::class.java)
        } catch (_: Exception) { throw NoteException(NoteError.Corrupted) }
        if (header.format != FORMAT || header.schemaVersion != SCHEMA_VERSION) throw NoteException(NoteError.UnsupportedVersion)
        cursor += headerLength
        val cipher = bytes.copyOfRange(cursor, bytes.size)
        if (cipher.size < 28) throw NoteException(NoteError.Corrupted)
        val plain = crypto.decrypt(cipher)
        val digest = MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(digest.toByteArray(Charsets.US_ASCII), header.sha256.toByteArray(Charsets.US_ASCII)))
            throw NoteException(NoteError.Corrupted)
        val notes = try {
            gson.fromJson(String(plain, Charsets.UTF_8), ArchiveBody::class.java).notes
        } catch (_: Exception) { throw NoteException(NoteError.Corrupted) }
        if (notes.size != header.noteCount) throw NoteException(NoteError.Corrupted)
        if (notes.map { it.id }.toSet().size != notes.size) throw NoteException(NoteError.Corrupted)
        val attachments = notes.sumOf { it.attachments.size }
        if (attachments != header.attachmentCount) throw NoteException(NoteError.Corrupted)
        for (note in notes) AttachmentLimits.validate(note.attachments)
        return notes
    }

    companion object {
        const val FORMAT = "phonoteka-note-archive"
        const val SCHEMA_VERSION = 1
        val MAGIC = "PTKA1".toByteArray(Charsets.US_ASCII)
        private const val MAX_HEADER = 255 // The on-disk format stores header length in one byte.
        private const val MAX_ARCHIVE = 24 * 1024 * 1024
    }
}
