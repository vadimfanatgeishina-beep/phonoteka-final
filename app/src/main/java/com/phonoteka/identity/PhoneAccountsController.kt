package com.phonoteka.identity

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

data class PhoneAccountsState(
    val busy: Boolean = false, val hostOpened: Boolean = false, val needsOwner: Boolean = false,
    val hostEndpoint: IdentityTlsEndpoint? = null, val pairing: IdentityPairingCode? = null,
    val pairedEndpoint: IdentityTlsEndpoint? = null, val user: IdentityUser? = null,
    val sessions: List<IdentitySessionInfo> = emptyList(), val message: String? = null
)

/** App-scoped runtime. UI state has no credentials; serialized IO prevents host and session races. */
class PhoneAccountsController(context: Context, private val requireLocalOwner: () -> Unit,
    private val principalChanged: () -> Unit, private val audit: (IdentityEvent) -> Unit,
    private val namespace: String = "host") {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val epoch = AtomicLong()
    private val mutable = MutableStateFlow(PhoneAccountsState())
    val state = mutable.asStateFlow()
    private var host: AndroidIdentityHost? = null
    private var client: PhoneIdentityClient? = null
    private class InvalidPairing : Exception()

    private fun publish(version: Long, change: (PhoneAccountsState) -> PhoneAccountsState) {
        mutable.update { if (version == epoch.get()) change(it) else it }
    }
    @Synchronized private fun action(block: suspend (Long) -> Unit) {
        if (mutable.value.busy) return
        val version = epoch.get()
        mutable.update { it.copy(busy = true, message = null) }
        scope.launch { mutex.withLock {
            try {
                if (version != epoch.get()) return@withLock
                requireLocalOwner(); block(version)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is IdentityClientException && failure.status == 401) {
                    client?.close(); client = null; principalChanged()
                    publish(version) { it.copy(user = null, sessions = emptyList()) }
                }
                val message = when (failure) {
                    is IdentityClientException -> when (failure.status) {
                        401 -> "Вход отклонён или сессия отозвана. Войдите заново."
                        403 -> "Сервер запретил действие: недостаточно прав."
                        409 -> "Пользователь уже существует или изменение конфликтует с правилами сервера."
                        429 -> "Слишком много попыток. Повторите позже."
                        else -> "Сервер не выполнил действие (${failure.status})."
                    }
                    is IdentityException -> "Проверьте логин и пароль: логин до 64 латинских символов, пароль от 8 символов."
                    is SecurityException -> "Требуется действующий локальный вход владельца."
                    is InvalidPairing -> "QR сервера повреждён или истёк. Обновите код на телефоне-сервере и проверьте дату и время устройств."
                    else -> "Не удалось выполнить действие. Проверьте точку доступа и доступность сервера. Данные автоматически не сбрасываются."
                }
                publish(version) { it.copy(message = message) }
            } finally { publish(version) { it.copy(busy = false) } }
        } }
    }
    private fun openedHost(): AndroidIdentityHost = host ?: AndroidIdentityHost.open(app, requireLocalOwner, namespace, audit)
        .also { host = it }

    fun openHost() = action { version ->
        val current = openedHost()
        publish(version) { it.copy(hostOpened = true, needsOwner = current.needsOwner) }
    }
    fun provisionOwner(login: String, password: String) = action { version ->
        openedHost().provisionOwner(login, password)
        publish(version) { it.copy(hostOpened = true, needsOwner = false, message = "Владелец сервера создан. Запустите сервер.") }
    }
    fun startHost() = action { version ->
        val address = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.contains(Regex("wlan|wifi|ap", RegexOption.IGNORE_CASE))) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress ?: error("No LAN address")
        val endpoint = openedHost().start(address)
        publish(version) { it.copy(hostEndpoint = endpoint, pairing = IdentityPairingCode(endpoint, System.currentTimeMillis() + IdentityPairingCode.TTL_MS),
            message = "Сервер работает. Покажите QR второму телефону.") }
    }
    fun refreshPairing() = action { version ->
        val endpoint = mutable.value.hostEndpoint ?: error("No host")
        publish(version) { it.copy(pairing = IdentityPairingCode(endpoint, System.currentTimeMillis() + IdentityPairingCode.TTL_MS)) }
    }
    fun stopHost() = action { version ->
        val ownEndpoint = mutable.value.hostEndpoint
        host?.stop()
        val disconnect = mutable.value.pairedEndpoint == ownEndpoint
        if (disconnect) { client?.close(); client = null; principalChanged() }
        publish(version) { it.copy(hostEndpoint = null, pairing = null,
            pairedEndpoint = if (disconnect) null else it.pairedEndpoint,
            user = if (disconnect) null else it.user, sessions = if (disconnect) emptyList() else it.sessions,
            message = "Сервер остановлен.") }
    }
    fun pair(raw: String) = action { version ->
        check(client == null) { "Log out before pairing another host" }
        publish(version) { it.copy(pairedEndpoint = null) }
        val code = IdentityPairingCode.parse(raw) ?: throw InvalidPairing()
        publish(version) { it.copy(pairedEndpoint = code.endpoint, message = "Сервер выбран. Введите свою учётную запись.") }
    }
    fun useOwnHost() = action { version ->
        check(client == null)
        val endpoint = mutable.value.hostEndpoint ?: error("No host")
        publish(version) { it.copy(pairedEndpoint = endpoint) }
    }
    fun login(login: String, password: String) = action { version ->
        check(client == null)
        val next = PhoneIdentityClient(mutable.value.pairedEndpoint ?: error("Pair first"))
        try {
            val user = next.login(login, password, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            val sessions = next.sessions()
            client = next
            principalChanged()
            publish(version) { it.copy(user = user, sessions = sessions, message = "Вход подтверждён сервером.") }
        } catch (failure: Exception) { next.close(); throw failure }
    }
    fun refreshSessions() = action { version ->
        val current = client ?: error("Login first")
        val user = current.currentUser(); val sessions = current.sessions()
        if (user != mutable.value.user) principalChanged()
        publish(version) { it.copy(user = user, sessions = sessions, message = "Сессии обновлены.") }
    }
    fun createUser(login: String, password: String, role: IdentityRole) = action { version ->
        val created = (client ?: error("Login first")).createUser(login, password, role)
        publish(version) { it.copy(message = "Создан пользователь ${created.login}, роль ${created.role.name}.") }
    }
    fun revokeSession(id: String, current: Boolean) = action { version ->
        val connected = client ?: error("Login first")
        if (current) {
            // Refresh rotates the current session ID. Logout revokes the same family even after rotation.
            try { connected.logout() }
            finally {
                connected.close(); client = null; principalChanged()
                publish(version) { it.copy(user = null, sessions = emptyList()) }
            }
            publish(version) { it.copy(message = "Текущая сессия отозвана.") }
        } else {
            connected.revokeSession(id)
            val sessions = connected.sessions()
            publish(version) { it.copy(sessions = sessions, message = "Сессия отозвана сервером.") }
        }
    }
    fun logout() = action { version ->
        val connected = client ?: return@action
        try { connected.logout() }
        finally {
            connected.close(); client = null; principalChanged()
            publish(version) { it.copy(user = null, sessions = emptyList()) }
        }
        publish(version) { it.copy(message = "Вы вышли из серверного аккаунта.") }
    }

    /** Clear UI immediately; wait for in-flight IO before releasing host and client resources. */
    @Synchronized fun invalidate() {
        val version = epoch.incrementAndGet()
        mutable.value = PhoneAccountsState(busy = true)
        scope.launch { mutex.withLock {
            var error: String? = null
            try { client?.close(); client = null; host?.close(); host = null }
            catch (_: Exception) { error = "Не удалось полностью завершить сервер. Повторите после остановки соединений." }
            finally { publish(version) { PhoneAccountsState(message = error) } }
        } }
    }
}
