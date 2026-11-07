package com.phonoteka.tasks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Хранилище задач. Реализация обязана выполнять IO вне главного потока. */
interface TaskStore {
    /** null = файла ещё нет (первый запуск). */
    suspend fun load(): Result<List<TaskItem>?>
    suspend fun save(tasks: List<TaskItem>): Result<Unit>
}

/**
 * Локальный файл (без сети): автосохранение не обращается к сети на каждый тап.
 * Формат: строка на задачу `id\tdone\ttitle\tdetails`, \\ \t \n экранируются.
 * Запись через временный файл + rename.
 */
class TaskFileStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : TaskStore {

    override suspend fun load(): Result<List<TaskItem>?> = withContext(io) {
        runCatching {
            if (!file.exists()) return@runCatching null
            if (file.length() > MAX_BYTES) throw IOException("Файл задач слишком большой")
            TaskCodec.decode(file.readText(Charsets.UTF_8))
        }
    }

    override suspend fun save(tasks: List<TaskItem>): Result<Unit> = withContext(io) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(TaskCodec.encode(tasks), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Не удалось заменить файл задач")
            }
        }
    }

    private companion object { const val MAX_BYTES = 1_000_000L }
}

object TaskCodec {
    const val HEADER = "phonoteka-tasks-v1"

    fun encode(tasks: List<TaskItem>): String = buildString {
        append(HEADER).append('\n')
        tasks.forEach { t ->
            append(t.id).append('\t').append(if (t.done) '1' else '0').append('\t')
            append(escape(t.title)).append('\t').append(escape(t.details)).append('\n')
        }
    }

    fun decode(text: String): List<TaskItem> {
        val lines = text.split('\n').filter { it.isNotEmpty() }
        require(lines.firstOrNull() == HEADER) { "Неизвестный формат файла задач" }
        val result = lines.drop(1).map { line ->
            val parts = line.split('\t')
            require(parts.size == 4) { "Повреждённая строка задачи" }
            val id = parts[0].toLongOrNull() ?: throw IllegalArgumentException("Неверный id")
            require(parts[1] == "0" || parts[1] == "1") { "Неверный флаг done" }
            TaskItem(id, unescape(parts[2]), parts[1] == "1", unescape(parts[3]))
        }
        require(result.map { it.id }.toSet().size == result.size) { "Повторяющиеся id задач" }
        return result
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                require(i + 1 < s.length) { "Обрывающееся экранирование" }
                when (s[i + 1]) {
                    '\\' -> out.append('\\')
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    else -> throw IllegalArgumentException("Неизвестное экранирование")
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
