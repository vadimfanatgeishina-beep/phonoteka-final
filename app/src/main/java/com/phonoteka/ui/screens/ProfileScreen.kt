package com.phonoteka.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.phonoteka.viewmodel.ProfileData
import com.phonoteka.viewmodel.ProfileSaveStatus
import com.phonoteka.viewmodel.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Counters come from the track repository, never from profile preferences. */
data class ProfileStats(val totalTracks: Int, val doneTracks: Int, val reviewTracks: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    stats: ProfileStats,
    accessRole: String,
    sessionActive: Boolean,
    onBack: () -> Unit,
    serverUser: com.phonoteka.identity.IdentityUser? = null,
    onOpenAccount: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var photoSheet by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    BackHandler(editing) { viewModel.cancel(); editing = false }

    LaunchedEffect(state.status) {
        if (state.status == ProfileSaveStatus.SAVED) editing = false
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { copyAvatar(context, uri) } }
                .onSuccess { viewModel.onAvatarChange(it); photoError = null }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it
                    photoError = "Не удалось прочитать фото. Выбери другое изображение." }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { saveAvatar(context, bitmap) } }
                .onSuccess { viewModel.onAvatarChange(it); photoError = null }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it
                    photoError = "Не удалось сохранить фото. Попробуй ещё раз." }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { camera.launch(null) }
                .onFailure { photoError = "Не удалось открыть камеру. Попробуй выбрать фото из галереи." }
        } else photoError = "Для съёмки аватара разреши доступ к камере. Галерея доступна без него."
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (editing) "Редактировать профиль" else "Мой профиль") },
            navigationIcon = { if (com.phonoteka.ui.components.LocalEmbeddedBackVisible.current) IconButton(onClick = {
                if (editing) { viewModel.cancel(); editing = false } else onBack()
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProfileAvatar(if (editing) state.draft else state.saved, Modifier.size(132.dp))
            if (editing) {
                TextButton(onClick = { photoSheet = true }) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Изменить фото")
                }
                OutlinedTextField(
                    value = state.draft.name, onValueChange = viewModel::onNameChange,
                    label = { Text("Ник") }, isError = state.nameError != null,
                    supportingText = state.nameError?.let { error -> { Text(error) } },
                    modifier = Modifier.fillMaxWidth().testTag("profileName"), singleLine = true
                )
                OutlinedTextField(
                    value = state.draft.personalRole, onValueChange = viewModel::onRoleChange,
                    label = { Text("Роль о себе") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = state.draft.bio, onValueChange = viewModel::onBioChange,
                    label = { Text("Описание") }, modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    maxLines = 4
                )
                Text("Роль доступа назначается отдельно и здесь не меняется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = viewModel::save, enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth().testTag("profileSave")
                ) { Text(if (state.status == ProfileSaveStatus.SAVING) "Сохранение…" else "Сохранить") }
                OutlinedButton(onClick = { viewModel.cancel(); editing = false }, modifier = Modifier.fillMaxWidth().testTag("profileCancel")) {
                    Text("Отмена")
                }
                TextButton(onClick = viewModel::reset, modifier = Modifier.testTag("profileReset")) { Text("Сбросить поля") }
            } else {
                Text(state.saved.name, style = MaterialTheme.typography.headlineMedium)
                Text(state.saved.personalRole.ifBlank { "Роль о себе не указана" },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(state.saved.bio.ifBlank { "Расскажи немного о себе" },
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().testTag("profileEdit")) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Редактировать")
                }
                onOpenAccount?.let { open ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(serverUser?.let { "Аккаунт сервера: ${it.login}" } ?: "Серверный аккаунт не подключён")
                            serverUser?.let { Text("Роль на сервере: ${it.role.name}") }
                            TextButton(onClick = open) { Text("Аккаунт и устройства") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("В библиотеке", stats.totalTracks, Modifier.weight(1f))
                    StatTile("Скачано", stats.doneTracks, Modifier.weight(1f))
                    StatTile("На проверке", stats.reviewTracks, Modifier.weight(1f))
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(when (accessRole) {
                            "ADMIN" -> "Главный администратор"
                            "SECURITY" -> "Руководитель безопасности"
                            else -> "Владелец профиля"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text("Текущие права: ${when (accessRole) {
                            "ADMIN" -> "администратор"
                            "SECURITY" -> "безопасность"
                            else -> "сотрудник"
                        }}", style = MaterialTheme.typography.bodySmall)
                        Text(if (sessionActive) "Сессия активна" else "Требуется повторный вход",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            state.message?.let { Text(it, color = if (state.status == ProfileSaveStatus.ERROR)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            photoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    com.phonoteka.ui.components.CelebrationBurst(state.celebrationTick, Modifier.matchParentSize())
    }
    if (photoSheet) ModalBottomSheet(onDismissRequest = { photoSheet = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Фото профиля", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { photoSheet = false; gallery.launch("image/*") }) { Text("Выбрать из галереи") }
            TextButton(onClick = {
                photoSheet = false
                photoError = null
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { camera.launch(null) }
                        .onFailure { photoError = "Не удалось открыть камеру. Попробуй выбрать фото из галереи." }
                } else cameraPermission.launch(Manifest.permission.CAMERA)
            }) { Text("Сделать фото") }
            TextButton(onClick = { photoSheet = false; viewModel.onAvatarChange("") }) { Text("Удалить фото") }
            TextButton(onClick = { photoSheet = false }) { Text("Отмена") }
        }
    }
}

@Composable private fun ProfileAvatar(data: ProfileData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val safeName = data.avatarFile.takeIf { it.matches(Regex("avatar_[a-f0-9-]+\\.jpg")) }
    val image = safeName?.let { File(File(context.filesDir, "profile"), it) }?.takeIf(File::exists)
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (image != null) AsyncImage(model = image, contentDescription = "Аватар профиля",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        else Icon(Icons.Default.Person, contentDescription = "Аватар профиля", modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun StatTile(label: String, count: Int, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

private fun avatarDirectory(context: android.content.Context) = File(context.filesDir, "profile").also { it.mkdirs() }
private fun avatarFile(context: android.content.Context) = File(avatarDirectory(context), "avatar_${UUID.randomUUID()}.jpg")

internal suspend fun copyAvatar(context: android.content.Context, uri: Uri): String {
    // Decode and normalize the image instead of storing arbitrary input under a .jpg name.
    val image = prepareNoteImage(context, uri)
    val output = avatarFile(context)
    try {
        output.outputStream().use { it.write(image.bytes); it.fd.sync() }
        return output.name
    } catch (failure: Exception) { output.delete(); throw failure }
}

internal fun saveAvatar(context: android.content.Context, bitmap: Bitmap): String {
    val output = avatarFile(context)
    try {
        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)); it.fd.sync() }
        return output.name
    } catch (failure: Exception) { output.delete(); throw failure }
}
