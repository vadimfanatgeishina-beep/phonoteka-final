package com.phonoteka.data
import com.phonoteka.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
interface TrackInvites {
    suspend fun issue(trackId: Long): String
    suspend fun scan(wire: String): ScannedTrackInvite
}
class AuthorizedTrackInvites(private val dao: TrackDao, private val endpoint: MockInviteAuthority,
    private val session: () -> DemoSession, private val gate: (DemoScope, String) -> Unit,
    private val audit: (String, Boolean, String) -> Unit) : TrackInvites {
    override suspend fun issue(trackId: Long): String = withContext(Dispatchers.IO) {
        gate(DemoScope.INVITE_CREATE, "invite.create")
        try {
            if (trackId <= 0 || dao.getTrackById(trackId).first() == null) throw InviteException(InviteFailure.INVALID)
            val wire = endpoint.issue(session(), trackId)
            audit("invite.issue.result", true, "track:$trackId")
            wire
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { runCatching { audit("invite.issue.result", false, "track:$trackId") }; throw error }
    }
    override suspend fun scan(wire: String): ScannedTrackInvite = withContext(Dispatchers.IO) {
        gate(DemoScope.SCAN_TRACK, "invite.scan")
        // A consumed invitation is never restored on an audit write failure: fail closed.
        try {
            val result = endpoint.scan(session(), wire)
            if (dao.getTrackById(result.trackId).first() == null) throw InviteException(InviteFailure.INVALID)
            audit("invite.scan.result", true, "track:${result.trackId}")
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { runCatching { audit("invite.scan.result", false, "feature:invites") }; throw error }
    }
}
