package com.phonoteka.data.notes
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
/** JVM-only fixture. Android production always uses AndroidAtomicNoteIndex. */
class TestFileNoteIndex(private val file: File) : AtomicNoteIndex {
    override fun read(): ByteArray? = if (file.exists()) file.readBytes() else null
    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
