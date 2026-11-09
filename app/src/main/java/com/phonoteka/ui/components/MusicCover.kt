package com.phonoteka.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val coverColors = listOf(
    Color(0xFF6849B8) to Color(0xFFB369C7),
    Color(0xFF147D83) to Color(0xFF64B3A8),
    Color(0xFFB34E64) to Color(0xFFF2A86E),
    Color(0xFF334B8C) to Color(0xFF7EA1D4),
    Color(0xFF865629) to Color(0xFFD6A765)
)

data class AlbumArtwork(val artist: String, val album: String, val releaseGroupId: String) {
    val imageUrl: String get() = "https://coverartarchive.org/release-group/$releaseGroupId/front-250"
}

/** Verified release-group front covers from MusicBrainz Cover Art Archive. */
private val albums = listOf(
    AlbumArtwork("Daft Punk", "Random Access Memories", "aa997ea0-2936-40bd-884d-3af8a0e064dc"),
    AlbumArtwork("Daft Punk", "Discovery", "48117b90-a16e-34ca-a514-19c702df1158"),
    AlbumArtwork("Nirvana", "Nevermind", "1b022e01-4da6-387b-8658-8678046e4cef"),
    AlbumArtwork("The Beatles", "Abbey Road", "9162580e-5df4-32de-80cc-f45a8d8a9b1d"),
    AlbumArtwork("The Weeknd", "After Hours", "cb5edcad-4ff0-4250-a845-e5aaa47a1b7b")
)

fun albumArtworkFor(id: Int): AlbumArtwork = albums[(id - 1).mod(albums.size)]

/** Real album cover with a local fallback for offline use. */
@Composable
fun MusicCover(id: Int, modifier: Modifier = Modifier) {
    val album = albumArtworkFor(id)
    SubcomposeAsyncImage(
        model = album.imageUrl,
        contentDescription = "Обложка альбома ${album.album} — ${album.artist}",
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop
    ) {
        when (painter.state) {
            is coil.compose.AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            else -> OfflineMusicCover(id, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun OfflineMusicCover(id: Int, modifier: Modifier = Modifier) {
    val palette = coverColors[(id - 1).mod(coverColors.size)]
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .background(Brush.linearGradient(listOf(palette.first, palette.second))),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.34f
            drawCircle(Color.White.copy(alpha = 0.82f), radius, style = Stroke(width = size.minDimension * 0.035f))
            drawCircle(Color.White.copy(alpha = 0.46f), radius * 0.65f, style = Stroke(width = size.minDimension * 0.016f))
            drawCircle(Color.White.copy(alpha = 0.88f), radius * 0.23f)
        }
        Text("♪", color = Color.White, fontSize = 17.sp)
    }
}
