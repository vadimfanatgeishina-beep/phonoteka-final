package com.phonoteka.network

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException

/** HTTP success alone does not prove that a provider returned playable audio. */
fun validateDownloadedAudio(file: File) {
    val metadata = MediaMetadataRetriever()
    try {
        metadata.setDataSource(file.absolutePath)
        val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val hasAudio = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        if (!hasAudio || duration <= 0) throw IOException("Источник вернул файл без читаемой аудиодорожки")
    } catch (failure: IOException) {
        throw failure
    } catch (failure: Exception) {
        throw IOException("Не удалось прочитать скачанное аудио. Попробуйте другой источник.", failure)
    } finally {
        runCatching { metadata.release() }
    }
}
