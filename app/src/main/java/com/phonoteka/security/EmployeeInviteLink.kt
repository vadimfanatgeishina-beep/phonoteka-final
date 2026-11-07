package com.phonoteka.security

import java.net.URI
import java.security.MessageDigest

enum class InviteLinkError { MALFORMED, WRONG_TARGET, MISSING_TOKEN, BAD_TOKEN, UNEXPECTED_PARAMETERS }

sealed interface InviteLinkResult {
    data class Valid(val token: String) : InviteLinkResult {
        override fun toString() = "Valid(token-redacted)"
    }
    data class Invalid(val error: InviteLinkError) : InviteLinkResult
}

/**
 * ЛР14: ссылка-приглашение проверяется ДО использования.
 * Единственный допустимый формат: phonoteka://invite?token=<[A-Za-z0-9_-]{8,64}>.
 * Любые userinfo/порт/путь/фрагмент/лишние или повторные параметры — отказ, без падения.
 */
object EmployeeInviteLink {
    const val SCHEME = "phonoteka"
    const val HOST = "invite"
    const val MAX_LENGTH = 256
    private val TOKEN = Regex("[A-Za-z0-9_-]{8,64}")

    fun parse(raw: String?): InviteLinkResult {
        if (raw.isNullOrEmpty() || raw.length > MAX_LENGTH || raw.any { it.isISOControl() || it.isWhitespace() }) {
            return InviteLinkResult.Invalid(InviteLinkError.MALFORMED)
        }
        val uri = try { URI(raw) } catch (_: Exception) { return InviteLinkResult.Invalid(InviteLinkError.MALFORMED) }
        if (uri.isOpaque || uri.scheme != SCHEME || uri.host != HOST || uri.rawUserInfo != null || uri.port != -1 ||
            (uri.rawPath != null && uri.rawPath != "" && uri.rawPath != "/") || uri.rawFragment != null) {
            return InviteLinkResult.Invalid(InviteLinkError.WRONG_TARGET)
        }
        val query = uri.rawQuery ?: return InviteLinkResult.Invalid(InviteLinkError.MISSING_TOKEN)
        val pairs = query.split('&')
        if (pairs.size != 1) return InviteLinkResult.Invalid(InviteLinkError.UNEXPECTED_PARAMETERS)
        val pair = pairs.single()
        val eq = pair.indexOf('=')
        if (eq < 0 || pair.substring(0, eq) != "token") return InviteLinkResult.Invalid(InviteLinkError.UNEXPECTED_PARAMETERS)
        val token = pair.substring(eq + 1)
        if (token.isEmpty()) return InviteLinkResult.Invalid(InviteLinkError.MISSING_TOKEN)
        // Сырое значение без percent-decoding: в алфавите токена нет символов, требующих кодирования.
        if (!TOKEN.matches(token)) return InviteLinkResult.Invalid(InviteLinkError.BAD_TOKEN)
        return InviteLinkResult.Valid(token)
    }

    fun message(error: InviteLinkError): String = when (error) {
        InviteLinkError.MALFORMED -> "Ссылка повреждена. Попросите новое приглашение."
        InviteLinkError.WRONG_TARGET -> "Это не ссылка-приглашение Phonoteka."
        InviteLinkError.MISSING_TOKEN -> "В ссылке нет кода приглашения."
        InviteLinkError.BAD_TOKEN -> "Код приглашения имеет неверный формат."
        InviteLinkError.UNEXPECTED_PARAMETERS -> "Ссылка содержит лишние параметры и отклонена."
    }
}

enum class InviteRegistrationResult { CREATED, ALREADY_USED, INVALID_TOKEN, INVALID_NAME, INVALID_EMAIL, CAPACITY }

/** Мок серверной регистрации по приглашению: один токен — одна регистрация. Хранит только SHA-256 токена. */
class MockInviteRegistration(private val capacity: Int = 1000) {
    private val used = HashSet<String>()
    private val email = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}")
    private val token = Regex("[A-Za-z0-9_-]{8,64}")

    @Synchronized fun register(inviteToken: String, name: String, mail: String): InviteRegistrationResult {
        if (!token.matches(inviteToken)) return InviteRegistrationResult.INVALID_TOKEN
        val cleanName = name.trim()
        if (cleanName.length !in 2..80 || cleanName.any { it.isISOControl() }) return InviteRegistrationResult.INVALID_NAME
        if (!email.matches(mail.trim())) return InviteRegistrationResult.INVALID_EMAIL
        val key = digest(inviteToken)
        if (key in used) return InviteRegistrationResult.ALREADY_USED
        if (used.size >= capacity) return InviteRegistrationResult.CAPACITY
        used += key
        return InviteRegistrationResult.CREATED
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object { val shared = MockInviteRegistration() }
}
