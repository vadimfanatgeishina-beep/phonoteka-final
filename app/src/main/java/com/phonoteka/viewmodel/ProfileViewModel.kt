package com.phonoteka.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ЛР8: данные экрана «Мой профиль». Роль только отображается (поле DISABLED). */
@Immutable
data class ProfileData(
    val name: String = DEFAULT_NAME,
    val bio: String = ""
) {
    companion object { const val DEFAULT_NAME = "Слушатель Фонотеки" }
}

enum class ProfileSaveStatus { IDLE, SAVING, SAVED, ERROR }

@Immutable
data class ProfileUiState(
    val saved: ProfileData = ProfileData(),
    val draft: ProfileData = ProfileData(),
    val loaded: Boolean = false,
    val status: ProfileSaveStatus = ProfileSaveStatus.IDLE,
    val message: String? = null
) {
    val nameError: String? get() = when {
        draft.name.isBlank() -> "Имя не может быть пустым"
        draft.name.length > MAX_NAME -> "Не более $MAX_NAME символов"
        else -> null
    }
    val bioWarning: String? get() =
        if (draft.bio.length > BIO_WARN) "Длинное описание: ${draft.bio.length}/$MAX_BIO" else null
    val dirty: Boolean get() = draft != saved
    val canSave: Boolean get() = loaded && dirty && nameError == null && draft.bio.length <= MAX_BIO &&
        status != ProfileSaveStatus.SAVING

    companion object { const val MAX_NAME = 40; const val BIO_WARN = 100; const val MAX_BIO = 140 }
}

interface ProfileStore {
    suspend fun read(): ProfileData
    suspend fun write(data: ProfileData)
}

private val Context.profileDataStore: DataStore<Preferences> by preferencesDataStore(name = "phonoteka_profile")
private val NAME_KEY = stringPreferencesKey("name")
private val BIO_KEY = stringPreferencesKey("bio")

/** DataStore сам выполняет IO на фоновом диспетчере. Данные переживают перезапуск. */
class DataStoreProfileStore(private val store: DataStore<Preferences>) : ProfileStore {
    override suspend fun read(): ProfileData {
        val p = store.data.first()
        return ProfileData(name = p[NAME_KEY] ?: ProfileData.DEFAULT_NAME, bio = p[BIO_KEY] ?: "")
    }
    override suspend fun write(data: ProfileData) {
        store.edit { it[NAME_KEY] = data.name; it[BIO_KEY] = data.bio }
    }
    companion object { fun from(context: Context) = DataStoreProfileStore(context.applicationContext.profileDataStore) }
}

class ProfileViewModel(private val store: ProfileStore) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = runCatching { store.read() }.getOrElse { ProfileData() }
            _state.update { if (it.loaded) it else it.copy(saved = stored, draft = stored, loaded = true) }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(draft = it.draft.copy(name = value.take(ProfileUiState.MAX_NAME + 10)), status = ProfileSaveStatus.IDLE) }
    fun onBioChange(value: String) = _state.update { it.copy(draft = it.draft.copy(bio = value.take(ProfileUiState.MAX_BIO)), status = ProfileSaveStatus.IDLE) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        val toWrite = current.draft.copy(name = current.draft.name.trim(), bio = current.draft.bio.trim())
        _state.update { it.copy(status = ProfileSaveStatus.SAVING, message = null) }
        viewModelScope.launch {
            val result = runCatching { store.write(toWrite) }
            _state.update {
                if (result.isSuccess) it.copy(saved = toWrite, draft = toWrite, status = ProfileSaveStatus.SAVED, message = "Изменения сохранены")
                else it.copy(status = ProfileSaveStatus.ERROR, message = "Ошибка сохранения: повторите действие")
            }
        }
    }

    /** «Отмена» — вернуть поля к последнему сохранённому значению. */
    fun cancel() = _state.update { it.copy(draft = it.saved, status = ProfileSaveStatus.IDLE, message = null) }

    /** «Сбросить» — черновик к значениям по умолчанию; запись только после «Сохранить». */
    fun reset() = _state.update { it.copy(draft = ProfileData(), status = ProfileSaveStatus.IDLE, message = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    class Factory(private val store: ProfileStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(store) as T
    }
}
