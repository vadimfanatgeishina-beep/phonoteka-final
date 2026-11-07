package com.phonoteka.security

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

fun interface AuditChainMac { fun sign(message: ByteArray): ByteArray }
interface AuditJournalFiles {
    fun <T> exclusive(action: () -> T): T
    fun log(): ByteArray
    fun head(): ByteArray?
    fun pending(): ByteArray?
    fun append(bytes: ByteArray)
    fun putHead(bytes: ByteArray)
    fun putPending(bytes: ByteArray)
    fun removePending()
}
data class AuditActor(val id: String, val role: String)
data class AuditDecision(val timestamp: Long, val actor: AuditActor, val action: String, val subject: String, val allowed: Boolean)
data class AuditRow(val sequence: Long, val decision: AuditDecision, val previousHash: String, val tag: String) {
    override fun toString() = "AuditRow(sequence=$sequence, metadata-redacted)"
}

/** Strict canonical JSONL + HMAC chain + signed durable head + signed recovery intent.
 * No log truncation/deletion API. A crash may only append a missing suffix of a verified intent.
 * Full filesystem rollback of a mutually consistent log+head is NOT detectable locally.
 */
class SignedAuditJournal(
    private val files: AuditJournalFiles, private val mac: AuditChainMac,
    private val maxEntries: Int = 10_000, private val maxBytes: Int = 8 * 1024 * 1024
) {
    init { require(maxEntries in 1..10_000 && maxBytes in 1024..8 * 1024 * 1024) }
    private data class Head(val seq: Long, val bytes: Long, val tail: String)
    private data class Intent(val before: Head, val line: String)
    private val zero = "0".repeat(64)
    private fun fail(): Nothing = error("Audit journal integrity/recovery failed")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun tag(domain: String, json: JsonObject) = mac.sign((domain + "\n" + json.toString()).toByteArray(Charsets.UTF_8)).also { check(it.size == 32) }
    private fun seal(domain: String, json: JsonObject): ByteArray {
        json.addProperty("hmac", hex(tag(domain, json))); return json.toString().toByteArray(Charsets.UTF_8)
    }
    private fun unseal(domain: String, raw: ByteArray, keys: Set<String>, limit: Int): JsonObject {
        check(raw.size in 1..limit)
        val root = try { JsonParser.parseString(String(raw, Charsets.UTF_8)).asJsonObject } catch (_: Exception) { fail() }
        check(root.keySet() == keys + "hmac")
        val provided = string(root, "hmac"); check(provided.matches(Regex("[a-f0-9]{64}")))
        root.remove("hmac")
        val expected = tag(domain, root)
        check(MessageDigest.isEqual(expected, provided.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        // Reject duplicate fields, unknown fields, whitespace aliases, noncanonical number spellings.
        root.addProperty("hmac", provided)
        check(MessageDigest.isEqual(raw, root.toString().toByteArray(Charsets.UTF_8)))
        root.remove("hmac"); return root
    }
    private fun string(root: JsonObject, name: String): String {
        val p = root[name] ?: fail(); check(p.isJsonPrimitive && p.asJsonPrimitive.isString); return p.asString
    }
    private fun number(root: JsonObject, name: String): Long {
        val p = root[name] ?: fail(); check(p.isJsonPrimitive && p.asJsonPrimitive.isNumber)
        val raw = p.asString; check(raw.matches(Regex("0|[1-9][0-9]*"))); return raw.toLongOrNull() ?: fail()
    }
    private fun validate(d: AuditDecision) {
        require(d.timestamp >= 0)
        require(d.actor.id.matches(Regex("[A-Za-z0-9_.:-]{1,96}")))
        require(d.actor.role in setOf("EMPLOYEE", "SECURITY", "ADMIN", "UNVERIFIED", "SYSTEM"))
        require(d.action.matches(Regex("[A-Za-z0-9_.:-]{1,96}")))
        require(d.subject.matches(Regex("[A-Za-z0-9_.:/-]{1,160}")))
    }
    private fun rowJson(seq: Long, d: AuditDecision, previous: String) = JsonObject().apply {
        addProperty("version", 1); addProperty("sequence", seq); addProperty("timestamp", d.timestamp)
        addProperty("actor", d.actor.id); addProperty("role", d.actor.role); addProperty("action", d.action)
        addProperty("subject", d.subject); addProperty("result", if (d.allowed) "ALLOWED" else "DENIED")
        addProperty("previousHash", previous)
    }
    private fun rowBytes(seq: Long, d: AuditDecision, previous: String): ByteArray {
        validate(d); val bytes = seal("phonoteka-audit-row-v1", rowJson(seq, d, previous)); check(bytes.size <= 1024); return bytes + 10.toByte()
    }
    private fun decodeRow(raw: ByteArray): AuditRow {
        val j = unseal("phonoteka-audit-row-v1", raw, setOf("version", "sequence", "timestamp", "actor", "role", "action", "subject", "result", "previousHash"), 1024)
        check(number(j, "version") == 1L)
        val outcome = string(j, "result"); check(outcome == "ALLOWED" || outcome == "DENIED")
        val d = AuditDecision(number(j, "timestamp"), AuditActor(string(j, "actor"), string(j, "role")), string(j, "action"), string(j, "subject"), outcome == "ALLOWED")
        validate(d); val seq = number(j, "sequence"); check(seq >= 1)
        val previous = string(j, "previousHash"); check(previous.matches(Regex("[a-f0-9]{64}")))
        check(j.toString() == rowJson(seq, d, previous).toString())
        return AuditRow(seq, d, previous, JsonParser.parseString(String(raw, Charsets.UTF_8)).asJsonObject["hmac"].asString)
    }
    private fun headJson(h: Head) = JsonObject().apply {
        addProperty("version", 1); addProperty("sequence", h.seq); addProperty("bytes", h.bytes); addProperty("tailHash", h.tail)
    }
    private fun headBytes(h: Head) = seal("phonoteka-audit-head-v1", headJson(h))
    private fun parseHead(bytes: ByteArray): Head {
        val j = unseal("phonoteka-audit-head-v1", bytes, setOf("version", "sequence", "bytes", "tailHash"), 512)
        check(number(j, "version") == 1L)
        val h = Head(number(j, "sequence"), number(j, "bytes"), string(j, "tailHash"))
        check(h.seq <= maxEntries && h.bytes <= maxBytes && h.tail.matches(Regex("[a-f0-9]{64}")))
        check(h.seq != 0L || (h.bytes == 0L && h.tail == zero)); check(j.toString() == headJson(h).toString()); return h
    }
    private fun intentBytes(i: Intent) = seal("phonoteka-audit-intent-v1", JsonObject().apply {
        addProperty("version", 1); addProperty("beforeSequence", i.before.seq); addProperty("beforeBytes", i.before.bytes)
        addProperty("beforeHash", i.before.tail); addProperty("line", i.line)
    })
    private fun parseIntent(raw: ByteArray): Intent {
        val j = unseal("phonoteka-audit-intent-v1", raw, setOf("version", "beforeSequence", "beforeBytes", "beforeHash", "line"), 2048)
        check(number(j, "version") == 1L)
        val before = Head(number(j, "beforeSequence"), number(j, "beforeBytes"), string(j, "beforeHash"))
        check(before.seq < maxEntries && before.bytes <= maxBytes && before.tail.matches(Regex("[a-f0-9]{64}")))
        check(before.seq != 0L || (before.bytes == 0L && before.tail == zero))
        val i = Intent(before, string(j, "line")); val line = i.line.toByteArray(Charsets.UTF_8)
        check(line.isNotEmpty() && line.last() == 10.toByte())
        val row = decodeRow(line.copyOf(line.size - 1)); check(row.sequence == before.seq + 1 && row.previousHash == before.tail)
        check(before.bytes + line.size <= maxBytes)
        check(MessageDigest.isEqual(raw, intentBytes(i))); return i
    }
    private fun verifyPrefix(raw: ByteArray, h: Head): List<AuditRow> {
        check(h.bytes <= raw.size && h.bytes <= maxBytes)
        val prefix = raw.copyOf(h.bytes.toInt()); val rows = mutableListOf<AuditRow>()
        var offset = 0; var previous = zero
        while (offset < prefix.size) {
            var end = offset; while (end < prefix.size && prefix[end] != 10.toByte()) end++
            check(end < prefix.size && end - offset in 1..1024 && rows.size < maxEntries)
            val row = decodeRow(prefix.copyOfRange(offset, end))
            check(row.sequence == rows.size + 1L && row.previousHash == previous)
            previous = hash(prefix.copyOfRange(offset, end + 1)); rows += row; offset = end + 1
        }
        check(rows.size.toLong() == h.seq && previous == h.tail); return rows
    }
    private fun settled(): Head {
        var raw = files.log(); check(raw.size <= maxBytes)
        val pending = files.pending()
        val headRaw = files.head()
        var h = if (headRaw == null) {
            check(raw.isEmpty() && pending == null) { "Audit head missing" }
            Head(0, 0, zero).also { files.putHead(headBytes(it)) }
        } else parseHead(headRaw)
        if (pending != null) {
            val i = parseIntent(pending); val line = i.line.toByteArray(Charsets.UTF_8)
            val after = Head(i.before.seq + 1, i.before.bytes + line.size, hash(line))
            when (h) {
                i.before -> {
                    verifyPrefix(raw, h)
                    val suffix = raw.copyOfRange(h.bytes.toInt(), raw.size)
                    check(suffix.size <= line.size && MessageDigest.isEqual(suffix, line.copyOf(suffix.size)))
                    if (suffix.size < line.size) files.append(line.copyOfRange(suffix.size, line.size))
                    raw = files.log(); check(raw.size.toLong() == after.bytes); verifyPrefix(raw, after)
                    files.putHead(headBytes(after)); h = after
                }
                after -> { check(raw.size.toLong() == after.bytes); verifyPrefix(raw, after) }
                else -> fail()
            }
            files.removePending()
        }
        check(raw.size.toLong() == h.bytes) { "Uncommitted/missing audit bytes" }
        verifyPrefix(raw, h); return h
    }
    fun append(decision: AuditDecision): AuditRow = files.exclusive {
        validate(decision); val before = settled(); check(before.seq < maxEntries) { "Audit capacity reached" }
        val bytes = rowBytes(before.seq + 1, decision, before.tail)
        check(before.bytes + bytes.size <= maxBytes) { "Audit capacity reached" }
        files.putPending(intentBytes(Intent(before, String(bytes, Charsets.UTF_8))))
        files.append(bytes)
        files.putHead(headBytes(Head(before.seq + 1, before.bytes + bytes.size, hash(bytes))))
        files.removePending()
        decodeRow(bytes.copyOf(bytes.size - 1))
    }
    fun read(): List<AuditRow> = files.exclusive { val h = settled(); verifyPrefix(files.log(), h) }
    fun jsonLines(): List<String> = files.exclusive {
        val h = settled(); val raw = files.log(); verifyPrefix(raw, h)
        if (raw.isEmpty()) emptyList() else String(raw, Charsets.UTF_8).dropLast(1).split('\n')
    }
}
