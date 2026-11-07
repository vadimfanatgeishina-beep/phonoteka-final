package com.phonoteka.data.notes

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.decodeBase64
import java.util.UUID

/** updatedAt=0 means unknown legacy time, never an invented historical timestamp. */
data class TrackNote(val id: Long, val title: String, val text: String, val pinned: Boolean = false, val updatedAt: Long = 0)
data class NoteRead(val note: TrackNote, val recovered: Boolean = false, val damaged: Boolean = false)
data class TrashNote(val item: NoteRead, val deletedAt: Long)
data class IntegritySummary(val checked: Int, val recovered: Int, val damaged: Int)
sealed interface NoteError {
    data object Io : NoteError
    data object Corrupted : NoteError
    data object KeyUnavailable : NoteError
    data object TooLarge : NoteError
    data object UnsupportedVersion : NoteError
    data object InvalidInput : NoteError
}
class NoteException(val reason: NoteError) : IOException("Track-note storage failure: $reason")
interface NoteCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
    fun mac(bytes: ByteArray): ByteArray
    fun resetKeys() {}
}
interface AtomicNoteIndex {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}
interface TrackNotes {
    suspend fun list(): List<NoteRead>
    suspend fun save(note: TrackNote)
    suspend fun delete(id: Long)
    suspend fun restore(id: Long)
    suspend fun purgeTrash()
    suspend fun export(): ByteArray
    suspend fun importDocument(document: ByteArray)
    suspend fun saveDraft(note: TrackNote)
    suspend fun draft(id: Long): TrackNote?
    suspend fun clearDraft(id: Long)
    suspend fun resetAfterConfirmation()
    // Default seams are for older component test fakes; production implementations override all.
    suspend fun trash(): List<TrashNote> = emptyList()
    suspend fun previewImport(document: ByteArray): Int = throw NoteException(NoteError.InvalidInput)
    suspend fun checkIntegrity(progress: (Int, Int) -> Unit): IntegritySummary = throw NoteException(NoteError.InvalidInput)
    suspend fun removeCorrupted(id: Long): Unit = throw NoteException(NoteError.InvalidInput)
    suspend fun emptyTrashAfterConfirmation(): Unit = throw NoteException(NoteError.InvalidInput)
}

/** Immutable encrypted revisions, then ONE atomic authenticated-index commit per transaction. */
class FileTrackNotesRepository(
    private val root: File,
    private val cache: File,
    private val crypto: NoteCrypto,
    private val index: AtomicNoteIndex,
    private val exists: suspend (Long) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val legacyDraftDirectory: File? = null
) : TrackNotes {
    private data class Revision(val file: String, val mac: String)
    private data class Entry(val current: Revision, val previous: Revision? = null, val deletedAt: Long? = null, val updatedAt: Long = 0)
    private data class Catalog(val version: Int = 2, val active: Map<String, Entry> = emptyMap(), val trash: Map<String, Entry> = emptyMap())
    private data class Envelope(val payload: String, val mac: String)
    private data class Export(val schemaVersion: Int, val exportedAt: Long, val notes: List<TrackNote>)
    private val gson = Gson()
    private val mutex = Mutex()
    private fun b64(bytes: ByteArray) = bytes.toByteString().base64()
    private fun bytes(value: String) = value.decodeBase64()?.toByteArray() ?: throw NoteException(NoteError.Corrupted)
    private fun checked(note: TrackNote, allowEmpty: Boolean = false): TrackNote {
        if (note.id <= 0 || note.updatedAt < 0 || note.title.length > 1000 || note.text.length > 100_000 ||
            (!allowEmpty && note.title.isBlank() && note.text.isBlank())) throw NoteException(NoteError.InvalidInput)
        return note
    }
    private fun bounded(file: File): ByteArray {
        if (!file.isFile) throw NoteException(NoteError.Corrupted)
        if (file.length() > MAX_BYTES) throw NoteException(NoteError.TooLarge)
        return file.readBytes()
    }
    private fun load(): Catalog {
        val raw = index.read() ?: return Catalog()
        if (raw.size > MAX_BYTES) throw NoteException(NoteError.TooLarge)
        return try {
            val envelope = gson.fromJson(String(raw, Charsets.UTF_8), Envelope::class.java)
            val plain = bytes(envelope.payload)
            if (!MessageDigest.isEqual(crypto.mac(plain), bytes(envelope.mac))) throw NoteException(NoteError.Corrupted)
            gson.fromJson(String(plain, Charsets.UTF_8), Catalog::class.java).also { catalog ->
                if (catalog.version != 2 || catalog.active.size > MAX_NOTES || catalog.trash.size > MAX_NOTES ||
                    (catalog.active.keys intersect catalog.trash.keys).isNotEmpty()) throw NoteException(NoteError.Corrupted)
                (catalog.active + catalog.trash).forEach { (id, entry) ->
                    if (!id.matches(Regex("[1-9][0-9]{0,18}")) || id.toLongOrNull() == null ||
                        entry.updatedAt < 0 || (entry.deletedAt ?: 0) < 0) throw NoteException(NoteError.Corrupted)
                }
            }
        } catch (e: NoteException) { throw e }
        catch (_: Exception) { throw NoteException(NoteError.Corrupted) }
    }
    private fun commit(catalog: Catalog) {
        val plain = gson.toJson(catalog).toByteArray(Charsets.UTF_8)
        val raw = gson.toJson(Envelope(b64(plain), b64(crypto.mac(plain)))).toByteArray(Charsets.UTF_8)
        if (raw.size > MAX_BYTES) throw NoteException(NoteError.TooLarge)
        index.write(raw)
    }
    private fun revision(note: TrackNote, directory: String = "notes"): Revision {
        val name = "$directory/${note.id}-${UUID.randomUUID()}.enc"
        val file = File(root, name)
        file.parentFile?.mkdirs()
        val ciphertext = crypto.encrypt(gson.toJson(checked(note)).toByteArray(Charsets.UTF_8))
        FileOutputStream(file).use { it.write(ciphertext); it.fd.sync() }
        return Revision(name, b64(crypto.mac(ciphertext)))
    }
    private fun backupRevision(id: Long, revision: Revision): Revision {
        val content = bounded(File(root, revision.file))
        if (!MessageDigest.isEqual(crypto.mac(content), bytes(revision.mac))) throw NoteException(NoteError.Corrupted)
        val name = "backup/$id-${UUID.randomUUID()}.enc"
        val file = File(root, name); file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(content); it.fd.sync() }
        return Revision(name, revision.mac)
    }
    private fun readRevision(id: Long, revision: Revision): TrackNote {
        if (!revision.file.matches(Regex("(notes|trash|backup)/[0-9]+-[a-f0-9-]{36}\\.enc"))) throw NoteException(NoteError.Corrupted)
        val ciphertext = bounded(File(root, revision.file))
        if (!MessageDigest.isEqual(crypto.mac(ciphertext), bytes(revision.mac))) throw NoteException(NoteError.Corrupted)
        return try {
            checked(gson.fromJson(String(crypto.decrypt(ciphertext), Charsets.UTF_8), TrackNote::class.java))
                .also { if (it.id != id) throw NoteException(NoteError.Corrupted) }
        } catch (e: NoteException) { if (e.reason == NoteError.InvalidInput) throw NoteException(NoteError.Corrupted) else throw e }
        catch (_: Exception) { throw NoteException(NoteError.Corrupted) }
    }
    private fun damagedContent(error: NoteException) = error.reason == NoteError.Corrupted || error.reason == NoteError.TooLarge
    private fun readEntry(id: Long, entry: Entry): NoteRead {
        try { return NoteRead(readRevision(id, entry.current)) }
        catch (error: NoteException) { if (!damagedContent(error)) throw error }
        entry.previous?.let { previous ->
            try { return NoteRead(readRevision(id, previous), recovered = true) }
            catch (error: NoteException) { if (!damagedContent(error)) throw error }
        }
        // Do not attempt to decode or expose unauthenticated title/body.
        return NoteRead(TrackNote(id, "Повреждена", "", updatedAt = entry.updatedAt), damaged = true)
    }
    private fun integrityEvent(id: Long, type: String) {
        root.mkdirs()
        val line = "${now()}|$id|$type\n".toByteArray(Charsets.UTF_8)
        FileOutputStream(File(root, "integrity.log"), true).use { it.write(line); it.fd.sync() }
    }
    private fun inspect(progress: (Int, Int) -> Unit = { _, _ -> }): List<NoteRead> {
        val catalog = load(); var active = catalog.active
        val recovered = mutableListOf<Long>()
        progress(0, catalog.active.size)
        val rows = catalog.active.entries.mapIndexed { position, (id, entry) ->
            val item = readEntry(id.toLong(), entry)
            if (item.recovered) {
                // Restore an authenticated previous revision, commit all repaired pointers once.
                val valid = entry.previous ?: throw NoteException(NoteError.Corrupted)
                active = active + (id to entry.copy(current = revision(item.note), previous = valid,
                    updatedAt = item.note.updatedAt))
                recovered += id.toLong()
            }
            progress(position + 1, catalog.active.size)
            item
        }
        if (recovered.isNotEmpty()) {
            commit(catalog.copy(active = active))
            recovered.forEach { integrityEvent(it, "restored_previous") }
            collectOrphans()
        }
        return rows.sortedWith(compareByDescending<NoteRead> { it.note.updatedAt }.thenBy { it.note.id })
    }
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (typed: NoteException) { throw typed }
            catch (_: IOException) { throw NoteException(NoteError.Io) }
        }
    }
    override suspend fun list(): List<NoteRead> = io { promoteAllLegacyDrafts(); inspect() }
    override suspend fun checkIntegrity(progress: (Int, Int) -> Unit): IntegritySummary = io {
        val rows = inspect(progress)
        IntegritySummary(rows.size, rows.count { it.recovered }, rows.count { it.damaged })
    }
    override suspend fun save(note: TrackNote) = io {
        checked(note)
        if (!exists(note.id)) throw NoteException(NoteError.InvalidInput)
        promoteLegacyDraft(note.id)
        val catalog = load()
        if (note.id.toString() !in catalog.active && catalog.active.size >= MAX_NOTES) throw NoteException(NoteError.TooLarge)
        val old = catalog.active[note.id.toString()]
        val previous = old?.let { entry ->
            val read = readEntry(note.id, entry)
            if (read.damaged) throw NoteException(NoteError.Corrupted)
            if (read.recovered) entry.previous else entry.current
        }
        val stamped = note.copy(updatedAt = now().coerceAtLeast(0))
        val entry = Entry(revision(stamped), previous?.let { backupRevision(note.id, it) }, updatedAt = stamped.updatedAt)
        commit(catalog.copy(active = catalog.active + (note.id.toString() to entry), trash = catalog.trash - note.id.toString()))
        removeDraftFile(note.id)
        collectOrphans()
    }
    override suspend fun delete(id: Long) = io {
        val catalog = load()
        val entry = catalog.active[id.toString()] ?: return@io
        if (catalog.trash.size >= MAX_NOTES) throw NoteException(NoteError.TooLarge)
        promoteLegacyDraft(id)
        val read = readEntry(id, entry)
        if (read.damaged) throw NoteException(NoteError.Corrupted)
        val deleted = Entry(revision(read.note, "trash"), deletedAt = now().coerceAtLeast(0), updatedAt = read.note.updatedAt)
        commit(catalog.copy(active = catalog.active - id.toString(), trash = catalog.trash + (id.toString() to deleted)))
        removeDraftFile(id); collectOrphans()
    }
    override suspend fun restore(id: Long) = io {
        val catalog = load()
        val entry = catalog.trash[id.toString()] ?: return@io
        if (!exists(id) || catalog.active.size >= MAX_NOTES) throw NoteException(NoteError.InvalidInput)
        val read = readEntry(id, entry)
        if (read.damaged) throw NoteException(NoteError.Corrupted)
        val note = read.note.copy(updatedAt = now().coerceAtLeast(0))
        val restored = Entry(revision(note), updatedAt = note.updatedAt)
        commit(catalog.copy(active = catalog.active + (id.toString() to restored), trash = catalog.trash - id.toString()))
        collectOrphans()
    }
    override suspend fun trash(): List<TrashNote> = io {
        load().trash.map { (id, e) -> TrashNote(readEntry(id.toLong(), e), e.deletedAt ?: 0) }.sortedByDescending { it.deletedAt }
    }
    override suspend fun purgeTrash() = io {
        val catalog = load()
        commit(catalog.copy(trash = catalog.trash.filterValues { now() - (it.deletedAt ?: now()) <= TRASH_TTL }))
        collectOrphans()
    }
    override suspend fun emptyTrashAfterConfirmation() = io {
        val catalog = load(); commit(catalog.copy(trash = emptyMap())); collectOrphans()
    }
    override suspend fun removeCorrupted(id: Long) = io {
        val catalog = load(); val entry = catalog.active[id.toString()] ?: throw NoteException(NoteError.InvalidInput)
        if (!readEntry(id, entry).damaged) throw NoteException(NoteError.InvalidInput)
        promoteLegacyDraft(id)
        commit(catalog.copy(active = catalog.active - id.toString()))
        removeDraftFile(id); collectOrphans(); integrityEvent(id, "removed_damaged")
    }
    override suspend fun export(): ByteArray = io {
        val notes = load().active.map { (id, e) ->
            val item = readEntry(id.toLong(), e)
            if (item.damaged) throw NoteException(NoteError.Corrupted) // Never silently omit a broken note.
            item.note
        }
        gson.toJson(Export(2, now(), notes)).toByteArray(Charsets.UTF_8).also {
            if (it.size > MAX_BYTES) throw NoteException(NoteError.TooLarge)
        }
    }
    private fun parseImport(document: ByteArray): List<TrackNote> {
        if (document.size > MAX_BYTES) throw NoteException(NoteError.TooLarge)
        return try {
            val tree = JsonParser.parseString(String(document, Charsets.UTF_8))
            val version = if (tree.isJsonArray || !tree.asJsonObject.has("schemaVersion")) 1L
                else strictLong(tree.asJsonObject.get("schemaVersion"))
            if (version !in 1L..2L) throw NoteException(NoteError.UnsupportedVersion)
            val array = if (tree.isJsonArray) tree.asJsonArray else tree.asJsonObject.getAsJsonArray("notes")
            if (array.size() > MAX_NOTES) throw NoteException(NoteError.TooLarge)
            val notes = array.map { element ->
                val obj = element.asJsonObject
                fun text(name: String): String {
                    val value = obj.get(name) ?: throw NoteException(NoteError.InvalidInput)
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw NoteException(NoteError.InvalidInput)
                    return value.asString
                }
                val bodyName = if (obj.has("text")) "text" else "body"
                if (obj.has("text") && obj.has("body") && text("text") != text("body")) throw NoteException(NoteError.InvalidInput)
                val pinned = if (!obj.has("pinned")) false else {
                    val value = obj.get("pinned")
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) throw NoteException(NoteError.InvalidInput)
                    value.asBoolean
                }
                checked(TrackNote(strictLong(obj.get("id")), text("title"), text(bodyName), pinned,
                    if (obj.has("updatedAt")) strictLong(obj.get("updatedAt")) else 0))
            }
            if (notes.map { it.id }.toSet().size != notes.size) throw NoteException(NoteError.InvalidInput)
            notes
        } catch (e: NoteException) { throw e }
        catch (_: Exception) { throw NoteException(NoteError.InvalidInput) }
    }
    private fun strictLong(value: JsonElement?): Long {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber ||
            !value.asString.matches(Regex("[0-9]+"))) throw NoteException(NoteError.InvalidInput)
        return value.asString.toLongOrNull() ?: throw NoteException(NoteError.InvalidInput)
    }
    private suspend fun validatedImport(document: ByteArray): List<TrackNote> {
        val notes = parseImport(document)
        for (note in notes) if (!exists(note.id)) throw NoteException(NoteError.InvalidInput)
        return notes
    }
    override suspend fun previewImport(document: ByteArray): Int = io { validatedImport(document).size }
    override suspend fun importDocument(document: ByteArray) = io {
        val imported = validatedImport(document)
        for (note in imported) promoteLegacyDraft(note.id)
        val catalog = load()
        val replacement = imported.associate { note ->
            val old = catalog.active[note.id.toString()]
            val previous = old?.let { entry ->
                val read = readEntry(note.id, entry)
                if (read.damaged) null else if (read.recovered) entry.previous else entry.current
            }
            note.id.toString() to Entry(revision(note), previous?.let { backupRevision(note.id, it) }, updatedAt = note.updatedAt)
        }
        // A failed revision write leaves only orphans; ONE index commit publishes the replacement.
        commit(catalog.copy(active = replacement, trash = catalog.trash - replacement.keys))
        imported.forEach { removeDraftFile(it.id) }
        collectOrphans()
    }
    private fun cacheFile(id: Long): File {
        if (id <= 0) throw NoteException(NoteError.InvalidInput)
        return File(cache, "track-note-$id.enc")
    }
    private fun readDraft(file: File, id: Long): TrackNote = try {
        checked(gson.fromJson(String(crypto.decrypt(bounded(file)), Charsets.UTF_8), TrackNote::class.java), allowEmpty = true)
            .also { if (it.id != id) throw NoteException(NoteError.Corrupted) }
    } catch (e: NoteException) { throw e }
    catch (_: Exception) { throw NoteException(NoteError.Corrupted) }
    private fun writeDraft(note: TrackNote) {
        checked(note, allowEmpty = true)
        cache.mkdirs()
        val file = cacheFile(note.id)
        val tmp = File(cache, file.name + ".tmp")
        // Bootstrap BOTH keys before any first ciphertext makes hasExistingData true.
        // Otherwise a draft-first launch can look like HMAC-key loss at the first index commit.
        crypto.mac(ByteArray(0))
        val ciphertext = crypto.encrypt(gson.toJson(note).toByteArray(Charsets.UTF_8))
        FileOutputStream(tmp).use { it.write(ciphertext); it.fd.sync() }
        if (!com.phonoteka.data.replaceAtomically(tmp, file)) throw NoteException(NoteError.Io)
    }
    /** Promote only authenticated/decrypted legacy content. Never overwrite a newer durable file.
     * Retirement happens AFTER sync/rename. A failed legacy deletion blocks a destructive
     * transaction before its catalog commit, so a stale legacy file cannot be resurrected.
     */
    private fun promoteLegacyDraft(id: Long) {
        val legacy = legacyDraftDirectory?.let { File(it, "track-note-$id.enc") } ?: return
        if (!legacy.exists()) return
        if (!cacheFile(id).exists()) writeDraft(readDraft(legacy, id))
        if (!legacy.delete()) throw NoteException(NoteError.Io)
    }
    private fun promoteAllLegacyDrafts() {
        val directory = legacyDraftDirectory ?: return
        if (!directory.exists()) return
        val files = directory.listFiles() ?: throw NoteException(NoteError.Io)
        val pattern = Regex("track-note-([1-9][0-9]*)\\.enc")
        val ids = files.mapNotNull { file -> pattern.matchEntire(file.name)?.groupValues?.get(1) }
        if (ids.size > MAX_NOTES) throw NoteException(NoteError.TooLarge)
        for (text in ids) promoteLegacyDraft(text.toLongOrNull() ?: throw NoteException(NoteError.Corrupted))
    }
    private fun removeDraftFile(id: Long) {
        val file = cacheFile(id)
        if (file.exists() && !file.delete()) throw NoteException(NoteError.Io)
    }
    override suspend fun saveDraft(note: TrackNote) = io {
        checked(note, allowEmpty = true)
        if (!exists(note.id)) throw NoteException(NoteError.InvalidInput)
        // Write the newest supplied edit FIRST; never replace it with legacy input.
        writeDraft(note)
        promoteLegacyDraft(note.id)
    }
    override suspend fun draft(id: Long): TrackNote? = io {
        promoteLegacyDraft(id)
        val file = cacheFile(id)
        if (!file.exists()) null else readDraft(file, id)
    }
    override suspend fun clearDraft(id: Long) = io {
        promoteLegacyDraft(id)
        removeDraftFile(id)
        Unit
    }
    override suspend fun resetAfterConfirmation() = io {
        if (root.exists() && !root.deleteRecursively()) throw NoteException(NoteError.Io)
        if (cache.exists() && !cache.deleteRecursively()) throw NoteException(NoteError.Io)
        legacyDraftDirectory?.let { if (it.exists() && !it.deleteRecursively()) throw NoteException(NoteError.Io) }
        crypto.resetKeys()
    }
    private fun collectOrphans() {
        val catalog = load()
        val live = (catalog.active.values + catalog.trash.values).flatMap { listOfNotNull(it.current.file, it.previous?.file) }.toSet()
        listOf("notes", "trash", "backup").forEach { dir ->
            File(root, dir).listFiles()?.filter { "$dir/${it.name}" !in live }?.forEach { it.delete() }
        }
    }
    companion object {
        private const val MAX_BYTES = 5 * 1024 * 1024
        private const val MAX_NOTES = 10_000
        private const val TRASH_TTL = 30L * 24 * 60 * 60 * 1000
    }
}
