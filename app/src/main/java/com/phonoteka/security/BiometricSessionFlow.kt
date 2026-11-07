package com.phonoteka.security

import javax.crypto.Cipher

/** Opaque, memory-only operation. toString never reveals a token or ciphertext. */
class BiometricCryptoRequest(val cipher: Cipher, internal val finish: (Cipher) -> String?) {
    override fun toString() = "BiometricCryptoRequest(redacted)"
}
interface BiometricRefreshVault {
    fun hasSavedRefresh(): Boolean
    fun prepareEnrollment(refresh: String): BiometricCryptoRequest
    fun prepareRestore(): BiometricCryptoRequest
    fun clear()
}
enum class BiometricSessionAction { ENROLL, RESTORE }
class BiometricSessionTicket internal constructor(
    val cipher: Cipher, internal val request: BiometricCryptoRequest,
    internal val action: BiometricSessionAction, internal val revision: Long,
    internal val expires: Long
) { override fun toString() = "BiometricSessionTicket(redacted)" }

/** Independent policy seam. Only the exact prompt cipher and live ticket may complete once. */
class BiometricSessionFlow(
    private val sessions: MockSessionRepository,
    private val vault: BiometricRefreshVault,
    private val elapsed: () -> Long
) {
    private var pending: BiometricSessionTicket? = null
    @Synchronized fun prepare(action: BiometricSessionAction): BiometricSessionTicket {
        pending = null
        val revision = sessions.sessionRevision()
        val request = when (action) {
            BiometricSessionAction.ENROLL -> vault.prepareEnrollment(sessions.refreshForBiometricEnrollment())
            BiometricSessionAction.RESTORE -> vault.prepareRestore()
        }
        return BiometricSessionTicket(request.cipher, request, action, revision,
            Math.addExact(elapsed(), 60_000)).also { pending = it }
    }
    @Synchronized fun cancel() { pending = null }
    @Synchronized fun complete(ticket: BiometricSessionTicket, returnedCipher: Cipher?): BiometricSessionAction {
        check(pending === ticket) { "No live biometric operation" }
        pending = null // Claim BEFORE crypto: failure/null/wrong cipher cannot be retried.
        check(returnedCipher === ticket.cipher) { "Unexpected biometric cipher" }
        val time = elapsed()
        check(time >= ticket.expires - 60_000 && time < ticket.expires) { "Biometric operation expired" }
        check(sessions.sessionRevision() == ticket.revision) { "Session changed during biometric prompt" }
        when (ticket.action) {
            BiometricSessionAction.ENROLL -> sessions.withUnchangedSession(ticket.revision) {
                ticket.request.finish(ticket.cipher)
            }
            BiometricSessionAction.RESTORE -> {
                val refresh = ticket.request.finish(ticket.cipher) ?: error("No decrypted refresh")
                sessions.restoreFromBiometric(refresh, ticket.revision)
            }
        }
        return ticket.action
    }
}
