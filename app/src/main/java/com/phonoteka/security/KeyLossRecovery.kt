package com.phonoteka.security

/**
 * ЛР13: восстановление после потери/инвалидации ключа AndroidKeyStore.
 *
 * Раньше AndroidKeystoreRefreshTokenStore.read() на ЛЮБОЕ исключение молча стирал токен:
 * временный сбой Keystore выглядел как выход из аккаунта, а пользователь не понимал причину.
 * Теперь ошибка классифицируется, стирается только то, что действительно нельзя расшифровать,
 * а причина записывается в журнал безопасных метаданных и показывается на экране входа.
 */
enum class KeyStoreFailure {
    /** Ключ цел, нужна системная аутентификация. Ничего не стирать. */
    LOCKED,
    /** Смена биометрии / снятие блокировки экрана: ключ навсегда недействителен. */
    KEY_INVALIDATED,
    /** Ключа нет (сброс Keystore, восстановление из бэкапа на другом устройстве, очистка). */
    KEY_MISSING,
    /** Шифртекст повреждён или не соответствует ключу (тег GCM не сошёлся, неверный формат). */
    CORRUPT_BLOB,
    /** Временный сбой провайдера. Данные не трогать, предложить повтор. */
    TRANSIENT
}

enum class KeyRecoveryAction {
    KEEP_AND_AUTHENTICATE,
    DELETE_KEY_AND_BLOB_RELOGIN,
    DELETE_BLOB_RELOGIN,
    KEEP_AND_RETRY
}

object KeyLossPolicy {
    /** Сопоставление по имени класса: работает на JVM без android.jar, классы Keystore не грузятся. */
    fun classify(error: Throwable): KeyStoreFailure {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            when (current.javaClass.simpleName) {
                "UserNotAuthenticatedException" -> return KeyStoreFailure.LOCKED
                "KeyPermanentlyInvalidatedException" -> return KeyStoreFailure.KEY_INVALIDATED
                "UnrecoverableKeyException", "KeyMissingException" -> return KeyStoreFailure.KEY_MISSING
                "AEADBadTagException", "BadPaddingException", "IllegalBlockSizeException",
                "InvalidBlobException" -> return KeyStoreFailure.CORRUPT_BLOB
            }
            if (current is KeyLostException) return current.failure
            if (current is IllegalArgumentException) return KeyStoreFailure.CORRUPT_BLOB // Base64 / длина
            current = current.cause; depth++
        }
        return KeyStoreFailure.TRANSIENT
    }

    fun action(failure: KeyStoreFailure): KeyRecoveryAction = when (failure) {
        KeyStoreFailure.LOCKED -> KeyRecoveryAction.KEEP_AND_AUTHENTICATE
        KeyStoreFailure.KEY_INVALIDATED -> KeyRecoveryAction.DELETE_KEY_AND_BLOB_RELOGIN
        KeyStoreFailure.KEY_MISSING, KeyStoreFailure.CORRUPT_BLOB -> KeyRecoveryAction.DELETE_BLOB_RELOGIN
        KeyStoreFailure.TRANSIENT -> KeyRecoveryAction.KEEP_AND_RETRY
    }

    fun userMessage(failure: KeyStoreFailure): String = when (failure) {
        KeyStoreFailure.LOCKED -> "Разблокируйте устройство, чтобы восстановить сессию."
        KeyStoreFailure.KEY_INVALIDATED -> "Ключ защиты сброшен из-за смены биометрии или блокировки экрана. Сохранённая сессия удалена — войдите заново."
        KeyStoreFailure.KEY_MISSING -> "Ключ защиты на этом устройстве не найден (сброс или перенос). Войдите заново."
        KeyStoreFailure.CORRUPT_BLOB -> "Сохранённая сессия повреждена и удалена. Войдите заново."
        KeyStoreFailure.TRANSIENT -> "Временная ошибка хранилища ключей. Сессия не удалена, повторите попытку."
    }
}

/** Исключение для вызывающего кода: ключ потерян, данные уже очищены, нужен новый вход. */
class KeyLostException(val failure: KeyStoreFailure) : SecurityException(KeyLossPolicy.userMessage(failure))

/** Последняя причина потери ключа. Только enum + время, без токенов и шифртекста. */
interface KeyLossJournal {
    fun record(failure: KeyStoreFailure, source: String)
    fun peek(): KeyLossNotice?
    fun acknowledge()

    companion object {
        val NONE: KeyLossJournal = object : KeyLossJournal {
            override fun record(failure: KeyStoreFailure, source: String) {}
            override fun peek(): KeyLossNotice? = null
            override fun acknowledge() {}
        }
    }
}

data class KeyLossNotice(val failure: KeyStoreFailure, val source: String, val atMillis: Long) {
    val message: String get() = KeyLossPolicy.userMessage(failure)
}

class InMemoryKeyLossJournal(private val clock: () -> Long = System::currentTimeMillis) : KeyLossJournal {
    private var notice: KeyLossNotice? = null
    @Synchronized override fun record(failure: KeyStoreFailure, source: String) {
        if (failure == KeyStoreFailure.LOCKED || failure == KeyStoreFailure.TRANSIENT) return
        notice = KeyLossNotice(failure, sanitize(source), clock())
    }
    @Synchronized override fun peek() = notice
    @Synchronized override fun acknowledge() { notice = null }
}

class AndroidKeyLossJournal(context: android.content.Context) : KeyLossJournal {
    private val prefs = context.applicationContext.getSharedPreferences("phonoteka_keyloss", android.content.Context.MODE_PRIVATE)
    @Synchronized override fun record(failure: KeyStoreFailure, source: String) {
        if (failure == KeyStoreFailure.LOCKED || failure == KeyStoreFailure.TRANSIENT) return
        prefs.edit().putString("failure", failure.name).putString("source", sanitize(source))
            .putLong("at", System.currentTimeMillis()).commit()
    }
    @Synchronized override fun peek(): KeyLossNotice? {
        val f = prefs.getString("failure", null) ?: return null
        val failure = KeyStoreFailure.entries.firstOrNull { it.name == f } ?: return null
        return KeyLossNotice(failure, prefs.getString("source", "") ?: "", prefs.getLong("at", 0))
    }
    @Synchronized override fun acknowledge() { prefs.edit().clear().commit() }
}

private fun sanitize(source: String) = source.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)

/** Ключа нет в AndroidKeyStore (имя класса распознаётся KeyLossPolicy). */
class KeyMissingException : IllegalStateException("Keystore key missing")
/** Сохранённый шифртекст имеет неверный формат. */
class InvalidBlobException : IllegalStateException("Invalid encrypted blob")
