package com.phonoteka.data.notes
import com.phonoteka.security.DemoScope
class AuthorizedTrackNotes(private val delegate: TrackNotes, private val check: (DemoScope, String) -> Unit) : TrackNotes {
    private fun read(resource: String = "notes.read") = check(DemoScope.LIBRARY_READ, resource)
    private fun write(resource: String = "notes.write") = check(DemoScope.LIBRARY_WRITE, resource)
    override suspend fun trash(): List<TrashNote> { read(); return delegate.trash() }
    override suspend fun previewImport(document: ByteArray): Int { read(); return delegate.previewImport(document) }
    override suspend fun checkIntegrity(progress: (Int, Int) -> Unit): IntegritySummary {
        read(); return delegate.checkIntegrity(progress)
    }
    override suspend fun removeCorrupted(id: Long) { write("notes.corrupt.remove"); delegate.removeCorrupted(id) }
    override suspend fun emptyTrashAfterConfirmation() { write("notes.trash.clear"); delegate.emptyTrashAfterConfirmation() }
    override suspend fun list(): List<NoteRead> { read(); return delegate.list() }
    override suspend fun save(note: TrackNote) { write(); delegate.save(note) }
    override suspend fun delete(id: Long) { write(); delegate.delete(id) }
    override suspend fun restore(id: Long) { write(); delegate.restore(id) }
    override suspend fun purgeTrash() { if (runCatching { write() }.isSuccess) delegate.purgeTrash() }
    override suspend fun export(): ByteArray { read("notes.export"); return delegate.export() }
    override suspend fun importDocument(document: ByteArray) { write("notes.import"); delegate.importDocument(document) }
    override suspend fun saveDraft(note: TrackNote) { write(); delegate.saveDraft(note) }
    override suspend fun draft(id: Long): TrackNote? { read(); return delegate.draft(id) }
    override suspend fun resetAfterConfirmation() { write("notes.reset"); delegate.resetAfterConfirmation() }
    override suspend fun clearDraft(id: Long) { write(); delegate.clearDraft(id) }
}
