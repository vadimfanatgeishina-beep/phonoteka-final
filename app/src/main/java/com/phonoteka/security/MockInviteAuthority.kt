package com.phonoteka.security

import java.security.MessageDigest
import java.util.UUID

fun interface InviteMac { fun sign(canonical: ByteArray): ByteArray }
data class ScannedTrackInvite(val trackId: Long)
enum class InviteFailure { DENIED, INVALID, EXPIRED, REPLAY, CAPACITY }
class InviteException(val failure: InviteFailure) : SecurityException("Owned invitation rejected: $failure")
/** Same-process, owned mock only. Elapsed-time expiry, digest ledger, signature and one-use consume. */
class MockInviteAuthority(private val authority: MockRoleAuthority, private val mac: InviteMac,
    private val now: () -> Long, private val ttl: Long = 300_000, private val capacity: Int = 1000) {
    private data class Entry(val trackId: Long, val expires: Long)
    private val issued = mutableMapOf<String, Entry>()
    init { require(ttl in 1..300_000 && capacity > 0) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun canonical(id: String, trackId: Long, expires: Long) = "PHONOTEKA-INVITE-1\n$id\n$trackId\n$expires".toByteArray(Charsets.UTF_8)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun prune(time: Long) { issued.entries.removeAll { time >= it.value.expires } }
    @Synchronized fun issue(actor: DemoSession, trackId: Long): String {
        if (!authority.authorize(actor, DemoScope.INVITE_CREATE)) throw InviteException(InviteFailure.DENIED)
        if (trackId <= 0) throw InviteException(InviteFailure.INVALID)
        val time = now(); require(time >= 0); prune(time)
        if (issued.size >= capacity) throw InviteException(InviteFailure.CAPACITY)
        val expires = Math.addExact(time, ttl)
        val id = UUID.randomUUID().toString()
        val body = canonical(id, trackId, expires)
        val signature = mac.sign(body)
        require(signature.size == 32)
        issued[digest(id.toByteArray(Charsets.UTF_8))] = Entry(trackId, expires)
        return String(body, Charsets.UTF_8) + "\n" + hex(signature)
    }
    @Synchronized fun scan(actor: DemoSession, wire: String): ScannedTrackInvite {
        if (!authority.authorize(actor, DemoScope.SCAN_TRACK)) throw InviteException(InviteFailure.DENIED)
        val fields = if (wire.length <= 512) wire.split('\n') else emptyList()
        if (fields.size != 5 || fields[0] != "PHONOTEKA-INVITE-1" || !fields[1].matches(Regex("[a-f0-9-]{36}")) ||
            !fields[4].matches(Regex("[a-f0-9]{64}"))) throw InviteException(InviteFailure.INVALID)
        val trackId = fields[2].toLongOrNull() ?: throw InviteException(InviteFailure.INVALID)
        val expires = fields[3].toLongOrNull() ?: throw InviteException(InviteFailure.INVALID)
        if (trackId <= 0 || expires < 0 || fields[2] != trackId.toString() || fields[3] != expires.toString()) throw InviteException(InviteFailure.INVALID)
        val expected = hex(mac.sign(canonical(fields[1], trackId, expires)))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), fields[4].toByteArray(Charsets.US_ASCII))) throw InviteException(InviteFailure.INVALID)
        val time = now()
        if (time < 0 || time >= expires) throw InviteException(InviteFailure.EXPIRED)
        val key = digest(fields[1].toByteArray(Charsets.UTF_8))
        val entry = issued[key] ?: throw InviteException(InviteFailure.REPLAY)
        if (entry.trackId != trackId || entry.expires != expires) throw InviteException(InviteFailure.INVALID)
        issued.remove(key) // Signature and authoritative record verified BEFORE consume.
        prune(time)
        return ScannedTrackInvite(trackId)
    }
    @Synchronized fun revokeAll() { issued.clear() }
}
