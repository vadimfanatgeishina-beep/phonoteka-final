package com.phonoteka.data.freenotes

import androidx.compose.runtime.Immutable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import com.phonoteka.data.replaceAtomically

/**
 * ЛР11 (база): НЕЗАВИСИМЫЕ заметки — не привязаны к треку.
 * Хранение: filesDir/notes.json, формат {"version":1,"notes":[{id,title,body,updatedAt}]}.
 */
@Immutable
data class FreeNote(val id: String, val title: String, val body: String, val updatedAt: Long)

enum class FreeNotesError { CORRUPT, UNSUPPORTED_VERSION, TOO_LARGE, IO }

class FreeNotesException(val error: FreeNotesError, cause: Throwable? = null) : Exception(error.name, cause)

/** ЛР11: DTO файла notes.json (kotlinx.serialization). Имена полей = формат версии 1, совместим с файлами прежней Gson-версии. */
@Serializable
internal data class FreeNoteDto(val id: String, val title: String, val body: String, val updatedAt: Long)

@Serializable
internal data class FreeNotesFileDto(val version: Int, val notes: List<FreeNoteDto>)

object FreeNotesCodec {
    const val VERSION = 1
    const val MAX_TITLE = 120
    const val MAX_BODY = 20_000
    const val MAX_NOTES = 1_000
    const val MAX_BYTES = 2 * 1024 * 1024
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  "; ignoreUnknownKeys = true; isLenient = false; encodeDefaults = true }

    fun encode(notes: List<FreeNote>): ByteArray =
        json.encodeToString(FreeNotesFileDto.serializer(),
            FreeNotesFileDto(VERSION, notes.map { FreeNoteDto(it.id, it.title, it.body, it.updatedAt) })
        ).toByteArray(Charsets.UTF_8)

    /**
     * Строгая проверка: сначала дерево JsonElement (типы: число в кавычках — ошибка, версия — целое),
     * затем decodeFromJsonElement в DTO, затем лимиты и уникальность id. Любая ошибка = исключение, частичных данных нет.
     */
    fun decode(bytes: ByteArray): List<FreeNote> {
        if (bytes.size > MAX_BYTES) throw FreeNotesException(FreeNotesError.TOO_LARGE)
        val root: JsonElement = try { json.parseToJsonElement(String(bytes, Charsets.UTF_8)) }
        catch (e: Exception) { throw FreeNotesException(FreeNotesError.CORRUPT, e) }
        val obj = root as? JsonObject ?: corrupt()
        val version = obj.intStrict("version")
        if (version != VERSION) throw FreeNotesException(FreeNotesError.UNSUPPORTED_VERSION)
        val arr = obj["notes"] as? JsonArray ?: corrupt()
        if (arr.size > MAX_NOTES) throw FreeNotesException(FreeNotesError.TOO_LARGE)
        arr.forEach { el ->
            val o = el as? JsonObject ?: corrupt()
            listOf("id", "title", "body").forEach { o.stringStrict(it) }
            o.longStrict("updatedAt")
        }
        val dto = try { json.decodeFromJsonElement(FreeNotesFileDto.serializer(), obj) }
        catch (e: SerializationException) { throw FreeNotesException(FreeNotesError.CORRUPT, e) }
        catch (e: IllegalArgumentException) { throw FreeNotesException(FreeNotesError.CORRUPT, e) }
        val notes = dto.notes.map { n ->
            if (n.id.isBlank() || n.id.length > 64 || n.title.length > MAX_TITLE || n.body.length > MAX_BODY || n.updatedAt < 0) corrupt()
            FreeNote(n.id, n.title, n.body, n.updatedAt)
        }
        if (notes.map { it.id }.toSet().size != notes.size) corrupt()
        return notes
    }

    private fun corrupt(): Nothing = throw FreeNotesException(FreeNotesError.CORRUPT)
    private fun JsonObject.primitive(k: String): JsonPrimitive = this[k] as? JsonPrimitive ?: corrupt()
    private fun JsonObject.stringStrict(k: String): String {
        val p = primitive(k)
        if (!p.isString) corrupt()
        return p.content
    }
    private fun JsonObject.longStrict(k: String): Long {
        val p = primitive(k)
        if (p.isString) corrupt()
        return p.longOrNull ?: p.doubleOrNull?.takeIf { it == Math.floor(it) && it >= 0 && it <= Long.MAX_VALUE.toDouble() }?.toLong() ?: corrupt()
    }
    private fun JsonObject.intStrict(k: String): Int {
        val p = primitive(k)
        if (p.isString) corrupt()
        val d = p.doubleOrNull ?: corrupt()
        if (d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) corrupt()
        return d.toInt()
    }
}

/** Файловый репозиторий. Всё IO — на [io]; запись tmp + atomic rename; битый файл не перезаписывается молча. */
class FreeNotesRepository(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val replaceFile: (File, File) -> Boolean = ::replaceAtomically
) {
    /** Пустой список, если файла нет. */
    suspend fun load(): List<FreeNote> = withContext(io) {
        if (!file.exists()) return@withContext emptyList()
        if (file.length() > FreeNotesCodec.MAX_BYTES) throw FreeNotesException(FreeNotesError.TOO_LARGE)
        val bytes = try { file.readBytes() } catch (e: IOException) { throw FreeNotesException(FreeNotesError.IO, e) }
        FreeNotesCodec.decode(bytes)
    }

    suspend fun save(notes: List<FreeNote>): Unit = withContext(io) {
        val bytes = FreeNotesCodec.encode(notes)
        val dir = file.absoluteFile.parentFile ?: throw FreeNotesException(FreeNotesError.IO)
        val tmp = File(dir, file.name + ".tmp")
        try {
            dir.mkdirs()
            tmp.outputStream().use { out -> out.write(bytes); out.fd.sync() }
            if (!replaceFile(tmp, file)) throw IOException("Cannot replace notes file atomically")
        } catch (e: IOException) {
            tmp.delete(); throw FreeNotesException(FreeNotesError.IO, e)
        }
    }

    /** Preserve damaged input before a confirmed import without removing the current file before commit. */
    suspend fun preserveCorruptForImport(now: Long): File? = withContext(io) {
        if (!file.exists()) return@withContext null
        val target = File(file.absoluteFile.parentFile, "${file.name}.corrupt-$now-${java.util.UUID.randomUUID()}")
        try {
            file.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
            target
        } catch (failure: IOException) {
            target.delete(); throw FreeNotesException(FreeNotesError.IO, failure)
        }
    }

    /** Явное решение пользователя «Начать заново»: битый файл откладывается, а не удаляется. */
    suspend fun quarantineCorrupt(now: Long): File? = withContext(io) {
        if (!file.exists()) return@withContext null
        val target = File(file.absoluteFile.parentFile, "${file.name}.corrupt-$now")
        if (!file.renameTo(target)) throw FreeNotesException(FreeNotesError.IO)
        target
    }
}

/** SAF-порт: только байты и строка URI; ViewModel не знает ContentResolver. */
interface FreeNotesDocuments {
    suspend fun read(uri: String): ByteArray
    suspend fun write(uri: String, bytes: ByteArray)
}

class AndroidFreeNotesDocuments(
    private val resolver: android.content.ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : FreeNotesDocuments {
    override suspend fun read(uri: String): ByteArray = withContext(io) {
        try {
            resolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    if (out.size() + n > FreeNotesCodec.MAX_BYTES) throw FreeNotesException(FreeNotesError.TOO_LARGE)
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: throw FreeNotesException(FreeNotesError.IO)
        } catch (e: FreeNotesException) { throw e }
        catch (e: IOException) { throw FreeNotesException(FreeNotesError.IO, e) }
        catch (e: SecurityException) { throw FreeNotesException(FreeNotesError.IO, e) }
    }

    override suspend fun write(uri: String, bytes: ByteArray): Unit = withContext(io) {
        try {
            resolver.openOutputStream(android.net.Uri.parse(uri), "wt")?.use { it.write(bytes); it.flush() }
                ?: throw FreeNotesException(FreeNotesError.IO)
        } catch (e: FreeNotesException) { throw e }
        catch (e: IOException) { throw FreeNotesException(FreeNotesError.IO, e) }
        catch (e: SecurityException) { throw FreeNotesException(FreeNotesError.IO, e) }
    }
}
