package com.phonoteka.security

data class RecoveryNotice(val channel: String, val maskedDestination: String, val sentAtMillis: Long)

/** ЛР13: уведомление владельца об использовании recovery-кода. В текст никогда не попадают код, PIN или пароль. */
fun interface RecoveryNotifier { fun recoveryCodeUsed(atMillis: Long): List<RecoveryNotice> }

/** Мок почты/SMS: «отправка» = запись в локальный outbox в памяти, без сети. */
class MockRecoveryOutbox(
    private val email: String = "student@example.test",
    private val phone: String = "+79990000000",
    private val capacity: Int = 50
) : RecoveryNotifier {
    private val sent = ArrayDeque<RecoveryNotice>()

    @Synchronized override fun recoveryCodeUsed(atMillis: Long): List<RecoveryNotice> {
        val notices = listOf(
            RecoveryNotice("email", maskEmail(email), atMillis),
            RecoveryNotice("sms", maskPhone(phone), atMillis)
        )
        notices.forEach { sent.addLast(it); while (sent.size > capacity) sent.removeFirst() }
        return notices
    }

    @Synchronized fun messages(): List<RecoveryNotice> = sent.toList()

    companion object {
        val shared = MockRecoveryOutbox()
        fun maskEmail(value: String): String {
            val at = value.indexOf('@')
            if (at <= 0) return "***"
            return value.first() + "***" + value.substring(at)
        }
        fun maskPhone(value: String): String =
            if (value.length < 4) "***" else "*".repeat(value.length - 4) + value.takeLast(4)
    }
}
