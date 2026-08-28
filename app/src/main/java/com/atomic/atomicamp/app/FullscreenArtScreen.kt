package com.atomic.atomicamp.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atomic.atomicamp.app.library.lyrics.Lyrics
import com.atomic.atomicamp.app.library.lyrics.LyricsLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.activity.ComponentActivity
import coil.compose.AsyncImage

/**
 * The screen the car sits on while music plays.
 *
 * A head unit spends almost all of its time being glanced at, not operated, so the useful display
 * is the one that says what is playing from the passenger seat -- big art, big title, and a
 * progress line. The transport lives one tap away rather than on screen, because the steering
 * wheel controls and the notification already cover it while driving.
 *
 * The art is drawn twice: blurred and cropped to fill the panel, then sharp and square on top.
 * A cover is square and the panel is 16:9, so filling it any other way either pillarboxes onto
 * dead black or crops the art itself.
 */
@Composable
fun FullscreenArtScreen(viewModel: PlayerViewModel, onExit: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val current = uiState.queue.getOrNull(uiState.currentIndex)
    val artUri = current?.albumArtUri

    // Lyrics are looked up per track, off the main thread, and only for tracks that live on this
    // device. A sidecar .lrc beside a SAF document is not reachable without another grant, and one
    // beside a cloud track would be a network fetch -- neither belongs in a screen's first frame.
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(current?.uri) {
        lyrics = withContext(Dispatchers.IO) {
            val uri = current?.uri ?: return@withContext null
            if (uri.scheme != "file") return@withContext null
            uri.path?.let { LyricsLoader.forAudioFile(File(it)) }
        }
    }

    KeepScreenOn()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onExit,
            ),
    ) {
        if (artUri != null) {
            AsyncImage(
                model = artUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp),
            )
            // Without this the blurred fill is bright enough to wash out the title over it. Two
            // passes: a flat floor so no album can be bright enough to beat the text, and a
            // horizontal ramp that darkens hardest under the text column on the right.
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.1f), Color.Black.copy(alpha = 0.72f)),
                    ),
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            ) {
                if (artUri != null) {
                    AsyncImage(
                        model = artUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                current?.subtitle?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.height(28.dp))

                val duration = uiState.durationMs.coerceAtLeast(1L)
                LinearProgressIndicator(
                    progress = { (uiState.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        formatClock(uiState.positionMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        formatClock(uiState.durationMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }

                val currentLine = lyrics?.let { it.indexAt(uiState.positionMs) } ?: -1
                if (lyrics != null && currentLine >= 0) {
                    Spacer(Modifier.height(24.dp))
                    // The line being sung, with the next one dimmed beneath it. Enough to follow
                    // at a glance without turning the screen into a page of text to read.
                    Text(
                        text = lyrics!!.lines[currentLine].text,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    lyrics!!.lines.getOrNull(currentLine + 1)?.let { next ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = next.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    "Tap anywhere for controls",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
        }
    }
}

/** A display you glance at should not time out while it is the thing being displayed. */
@Composable
private fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
