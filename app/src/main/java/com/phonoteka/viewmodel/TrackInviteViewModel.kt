package com.phonoteka.viewmodel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phonoteka.data.TrackInvites
import com.phonoteka.security.InviteException
import com.phonoteka.security.StepUpRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class InviteUiState(val wire: String? = null, val scannedTrackId: Long? = null, val busy: Boolean = false, val message: String? = null)
class TrackInviteViewModel(private val repository: TrackInvites) : ViewModel() {
    private val mutable = MutableStateFlow(InviteUiState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var operation: kotlinx.coroutines.Job? = null
    fun clear() { generation++; operation?.cancel(); operation = null; mutable.value = InviteUiState() }
    fun issue(trackId: Long) = action { InviteUiState(wire = repository.issue(trackId), message = "Одноразовый QR создан на пять минут в этом процессе") }
    fun scan(wire: String) = action { InviteUiState(scannedTrackId = repository.scan(wire).trackId, message = "Приглашение проверено и использовано") }
    private fun action(block: suspend () -> InviteUiState): kotlinx.coroutines.Job? {
        if (mutable.value.busy) return operation
        val request = ++generation
        mutable.value = InviteUiState(busy = true)
        return viewModelScope.launch {
            try { val result = block(); if (generation == request) mutable.value = result }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: StepUpRequiredException) { if (generation == request) mutable.value = InviteUiState(message = "Подтвердите PIN в диалоге и повторите действие") }
            catch (e: InviteException) { if (generation == request) mutable.value = InviteUiState(message = "Приглашение отклонено: ${e.failure.name}") }
            catch (_: Exception) { if (generation == request) mutable.value = InviteUiState(message = "Нет разрешения или локальный стенд недоступен") }
            finally { if (generation == request) mutable.value = mutable.value.copy(busy = false) }
        }.also { operation = it }
    }
    class Factory(private val repository: TrackInvites) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackInviteViewModel(repository) as T
    }
}
