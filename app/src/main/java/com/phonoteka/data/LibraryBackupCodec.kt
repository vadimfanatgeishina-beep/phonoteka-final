package com.phonoteka.data

import com.google.gson.Gson
import java.io.IOException

data class BackupDocument(val schemaVersion: Int, val tracks: List<TrackEntity>, val candidates: List<CandidateEntity>)

object LibraryBackupCodec {
    private val gson = Gson()
    fun encode(tracks: List<TrackEntity>, candidates: List<CandidateEntity>): String =
        gson.toJson(BackupDocument(1, tracks, candidates))

    fun decode(json: String): BackupDocument {
        if (json.length > 25 * 1024 * 1024) throw IOException("Резервная копия больше 25 МБ")
        val doc = try { gson.fromJson(json, BackupDocument::class.java) }
        catch (e: Exception) { throw IOException("Файл резервной копии повреждён", e) }
            ?: throw IOException("Пустой файл резервной копии")
        if (doc.schemaVersion != 1) throw IOException("Версия резервной копии не поддерживается")
        val tracks = doc.tracks ?: throw IOException("В резервной копии нет списка треков")
        val candidates = doc.candidates ?: throw IOException("В резервной копии нет списка кандидатов")
        if (tracks.size > 100_000 || candidates.size > 500_000) throw IOException("Слишком много записей")
        val ids = tracks.map { it.id }.toSet()
        if (ids.size != tracks.size || tracks.any { it.id <= 0 || it.artist.length > 1_000 || it.title.length > 1_000 }) {
            throw IOException("В резервной копии есть некорректные треки")
        }
        if (candidates.map { it.id }.toSet().size != candidates.size || candidates.any {
                it.trackId !in ids || it.id.isBlank() || it.id.length > 512
            }) throw IOException("В резервной копии есть некорректные кандидаты")
        return doc
    }
}
