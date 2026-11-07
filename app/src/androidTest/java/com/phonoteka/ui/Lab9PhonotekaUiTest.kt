package com.phonoteka.ui

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.phonoteka.data.LocalImportReport
import com.phonoteka.data.Track
import com.phonoteka.data.TrackCandidate
import com.phonoteka.data.TrackLibrary
import com.phonoteka.data.TrackStatus
import com.phonoteka.ui.screens.TrackListScreen
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.TrackListViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class Lab9PhonotekaUiTest {
    @get:Rule val compose = createComposeRule()

    private fun render(
        library: UiTestLibrary,
        awaitDraftDebounce: suspend () -> Unit = { delay(400) }
    ): TrackListViewModel {
        val vm = TrackListViewModel(library)
        compose.setContent { PhonotekaTheme { TrackListScreen(vm, onTrackClick = {}, onNavigateToSettings = {}, awaitDraftDebounce = awaitDraftDebounce) } }
        return vm
    }

    @Test fun listRendersStableTrackRowAndStatus() {
        val library = UiTestLibrary(mutableListOf(Track(1, "Radiohead", "Creep", TrackStatus.DONE)))
        render(library)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("track-row-1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("track-row-1").assertExists()
        compose.onNodeWithText("Creep").assertExists()
    }

    @Test fun addTrackDialogPersistsNewLibraryItem() {
        val library = UiTestLibrary(mutableListOf())
        render(library)
        compose.onNodeWithTag("add-track-open").performClick()
        compose.onNodeWithTag("add-track-artist").performTextInput("Muse")
        compose.onNodeWithTag("add-track-title").performTextInput("Starlight")
        compose.onNodeWithText("Добавить").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("track-row-1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Starlight").assertExists()
        assertEquals(1, library.items.value.size)
    }

    @Test fun searchFiltersRowsAfterDebounce() {
        val library = UiTestLibrary(mutableListOf(
            Track(1, "Radiohead", "Creep", TrackStatus.DONE),
            Track(2, "Muse", "Starlight", TrackStatus.DONE)
        ))
        render(library)
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("track-row-1")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("track-row-2")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("track-search").performTextInput("Muse")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("track-row-2")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("track-row-1")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("track-row-2").assertExists()
        compose.onNodeWithTag("track-row-1").assertDoesNotExist()
    }

    @Test fun expandingTrackShowsItsDetails() {
        val library = UiTestLibrary(mutableListOf(Track(1, "Radiohead", "Creep", TrackStatus.DONE)))
        render(library)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("track-expand-1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("track-expand-1").performClick()
        compose.onNodeWithText("Источник").assertExists()
    }

    @Test fun addTrackDraftWaitsForControlledDebounceBeforeSaving() {
        val gate = CompletableDeferred<Unit>()
        val library = UiTestLibrary(mutableListOf())
        val viewModel = render(library) { gate.await() }
        try {
            compose.onNodeWithTag("add-track-open").performClick()
            compose.onNodeWithTag("add-track-artist").performTextInput("Muse")
            compose.onNodeWithTag("add-track-title").performTextInput("Starlight")
            compose.runOnIdle {
                assertEquals("", viewModel.addTrackDraft.value.artist)
                assertEquals("", viewModel.addTrackDraft.value.title)
            }
            compose.onNodeWithTag("add-track-draft-saved").assertDoesNotExist()
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasTestTag("add-track-draft-saved"))
                    .fetchSemanticsNodes().isNotEmpty() &&
                    viewModel.addTrackDraft.value.artist == "Muse" &&
                    viewModel.addTrackDraft.value.title == "Starlight"
            }
            compose.runOnIdle {
                assertEquals("Muse", viewModel.addTrackDraft.value.artist)
                assertEquals("Starlight", viewModel.addTrackDraft.value.title)
            }
        } finally { gate.complete(Unit) }
    }

}

private class UiTestLibrary(initial: MutableList<Track>) : TrackLibrary {
    val items = MutableStateFlow(initial.toList())
    override fun getAllTracks(): Flow<List<Track>> = items
    override fun totalCount(): Flow<Int> = items.map { it.size }
    override fun doneCount(): Flow<Int> = items.map { list -> list.count { it.status == TrackStatus.DONE } }
    override fun errorCount(): Flow<Int> = items.map { list -> list.count { it.status == TrackStatus.ERROR } }
    override fun reviewCount(): Flow<Int> = items.map { list -> list.count { it.status == TrackStatus.REVIEW } }
    override suspend fun importFromAssets(): Int = 0
    override suspend fun importLocalFiles(uris: List<Uri>) = LocalImportReport(0, 0, 0)
    override fun getCandidates(trackId: Long): Flow<List<TrackCandidate>> = MutableStateFlow(emptyList())
    override suspend fun searchTrack(trackId: Long, source: String) = emptyList<TrackCandidate>()
    override suspend fun selectCandidate(trackId: Long, candidateId: String) = Unit
    override suspend fun autoSelectCandidate(trackId: Long) = Unit
    override suspend fun downloadTrack(trackId: Long, onProgress: (Long) -> Unit) = File("unused")
    override suspend fun resetErrorsToPending() = Unit
    override suspend fun addTrack(artist: String, title: String): Long {
        val id = (items.value.maxOfOrNull { it.id } ?: 0) + 1
        items.value = items.value + Track(id, artist, title)
        return id
    }
    override suspend fun importTextFile(uri: Uri) = 0
    override suspend fun clearAllTracks() { items.value = emptyList() }
}
