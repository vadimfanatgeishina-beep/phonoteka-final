package com.phonoteka.data.notes

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Android SAF adapter is data-layer IO. VM sees only validated bytes/domain states, never Uri. */
class AndroidJsonDocuments(private val resolver: ContentResolver, private val maxBytes: Int = 5 * 1024 * 1024) {
    init { require(maxBytes > 0) }
    suspend fun read(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    if (n > maxBytes - out.size()) throw NoteException(NoteError.TooLarge)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: throw NoteException(NoteError.Io)
        } catch (e: NoteException) { throw e }
        catch (_: IOException) { throw NoteException(NoteError.Io) }
        catch (_: SecurityException) { throw NoteException(NoteError.Io) }
    }
    suspend fun write(uri: Uri, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (bytes.size > maxBytes) throw NoteException(NoteError.TooLarge)
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                ?: throw NoteException(NoteError.Io)
        } catch (e: NoteException) { throw e }
        catch (_: IOException) { throw NoteException(NoteError.Io) }
        catch (_: SecurityException) { throw NoteException(NoteError.Io) }
    }
}
