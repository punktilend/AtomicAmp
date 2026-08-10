package com.atomic.atomicamp.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.atomic.atomicamp.app.ui.VerticalSlider
import com.atomic.atomicamp.engine.dsp.EqPreset
import com.atomic.atomicamp.engine.dsp.EqPresets
import com.atomic.atomicamp.engine.dsp.GraphicEqualizerAudioProcessor
import java.io.File

/**
 * Press target for transport controls, sized for a moving vehicle.
 *
 * Larger than phone intuition suggests on purpose: the target head unit reports density 1.0 on a
 * ~210ppi panel, so a dp renders physically smaller there than the same dp on a phone.
 */
private val TRANSPORT_BUTTON_HEIGHT = 72.dp

/** Queue rows are denser than library rows -- more of the queue matters more than reach here. */
private val QUEUE_ROW_MIN_HEIGHT = 56.dp

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onNavigateToLibrary: () -> Unit = {},
    onSaveQueueAsPlaylist: (name: String, trackUris: List<String>) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addTracks(uris)
        }
    }

    var rightPane by rememberSaveable { mutableStateOf(RightPane.EQUALIZER) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The head unit is wide and short; phones in portrait are the opposite. Pick a layout from
        // the actual aspect ratio rather than assuming either one.
        val isWide = maxWidth > maxHeight

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Now Playing", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickerLauncher.launch(arrayOf("audio/*")) }) { Text("Add files") }
                    Button(onClick = onNavigateToLibrary) { Text("Library") }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
                        // Art takes whatever height is left after the controls have claimed
                        // theirs, and is squared off that height -- sizing it off width would
                        // overflow this short screen and push the transport buttons off-screen.
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AlbumArt(
                                path = uiState.queue.getOrNull(uiState.currentIndex)?.albumArtPath,
                                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TrackTitles(uiState)
                        SeekRow(uiState = uiState, onSeek = viewModel::seekTo)
                        Spacer(Modifier.height(8.dp))
                        TransportControls(
                            isPlaying = uiState.isPlaying,
                            shuffleEnabled = uiState.shuffleEnabled,
                            repeatMode = uiState.repeatMode,
                            onPlayPause = viewModel::togglePlayPause,
                            onNext = viewModel::skipNext,
                            onPrevious = viewModel::skipPrevious,
                            onToggleShuffle = viewModel::toggleShuffle,
                            onCycleRepeat = viewModel::cycleRepeatMode,
                        )
                    }

                    Spacer(Modifier.width(24.dp))

                    Column(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                        // 660dp of height can't hold both a fader row and a queue list, so the
                        // pane switches. Without this the queue is unreachable in landscape --
                        // which is the only orientation the head unit has.
                        SegmentedToggle(
                            options = RightPane.entries,
                            selected = rightPane,
                            label = { it.label },
                            onSelect = { rightPane = it },
                        )
                        Spacer(Modifier.height(8.dp))

                        when (rightPane) {
                            RightPane.EQUALIZER -> EqualizerPanel(
                                eqEnabled = uiState.eqEnabled,
                                preampDb = uiState.preampDb,
                                bandGainsDb = uiState.bandGainsDb,
                                presetName = uiState.presetName,
                                onEqEnabledChange = viewModel::setEqEnabled,
                                onPreampChange = viewModel::setPreamp,
                                onBandChange = viewModel::setBandGain,
                                onPresetSelected = viewModel::applyPreset,
                                vertical = true,
                                modifier = Modifier.weight(1f),
                            )

                            RightPane.QUEUE -> QueuePanel(
                                queue = uiState.queue,
                                currentIndex = uiState.currentIndex,
                                onPlayIndex = viewModel::playQueueItem,
                                onRemoveIndex = viewModel::removeFromQueue,
                                onSaveAsPlaylist = onSaveQueueAsPlaylist,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            } else {
                TrackTitles(uiState)
                SeekRow(uiState = uiState, onSeek = viewModel::seekTo)
                Spacer(Modifier.height(8.dp))
                TransportControls(
                    isPlaying = uiState.isPlaying,
                    shuffleEnabled = uiState.shuffleEnabled,
                    repeatMode = uiState.repeatMode,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::skipNext,
                    onPrevious = viewModel::skipPrevious,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onCycleRepeat = viewModel::cycleRepeatMode,
                )
                Spacer(Modifier.height(16.dp))
                EqualizerPanel(
                    eqEnabled = uiState.eqEnabled,
                    preampDb = uiState.preampDb,
                    bandGainsDb = uiState.bandGainsDb,
                    presetName = uiState.presetName,
                    onEqEnabledChange = viewModel::setEqEnabled,
                    onPreampChange = viewModel::setPreamp,
                    onBandChange = viewModel::setBandGain,
                    onPresetSelected = viewModel::applyPreset,
                    vertical = false,
                )
                Spacer(Modifier.height(16.dp))
                Text("Queue", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(uiState.queue) { index, track ->
                        Text(
                            text = track.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (index == uiState.currentIndex) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** What the right-hand pane shows in the wide layout. */
private enum class RightPane(val label: String) {
    EQUALIZER("Equalizer"),
    QUEUE("Queue"),
}

@Composable
private fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val modifier = Modifier.weight(1f).height(44.dp)
            if (isSelected) {
                Button(onClick = { onSelect(option) }, modifier = modifier) { Text(label(option)) }
            } else {
                OutlinedButton(onClick = { onSelect(option) }, modifier = modifier) { Text(label(option)) }
            }
        }
    }
}

@Composable
private fun QueuePanel(
    queue: List<Track>,
    currentIndex: Int,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onSaveAsPlaylist: (name: String, trackUris: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Queue is empty.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    var showSave by remember { mutableStateOf(false) }

    // Building a queue by browsing and then naming it beats adding tracks one at a time, which is
    // the last thing you want to be doing at the wheel.
    Button(
        onClick = { showSave = true },
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) { Text("Save queue as playlist") }
    Spacer(Modifier.height(8.dp))

    if (showSave) {
        SaveQueueDialog(
            onDismiss = { showSave = false },
            onSave = { name ->
                onSaveAsPlaylist(name, queue.map { it.uri.toString() })
                showSave = false
            },
        )
    }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        itemsIndexed(queue) { index, track ->
            val isCurrent = index == currentIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayIndex(index) }
                    .heightIn(min = QUEUE_ROW_MIN_HEIGHT)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    )
                    track.subtitle?.let {
                        Text(
                            it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                // Removing the playing track would restart playback elsewhere unexpectedly, so
                // it's the one entry without a remove action.
                if (!isCurrent) {
                    TextButton(onClick = { onRemoveIndex(index) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun SaveQueueDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save queue as playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AlbumArt(path: String?, modifier: Modifier = Modifier) {
    if (path != null) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun TrackTitles(uiState: PlayerUiState) {
    val current = uiState.queue.getOrNull(uiState.currentIndex)
    Text(
        current?.title ?: "Nothing queued",
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    current?.subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SeekRow(uiState: PlayerUiState, onSeek: (Long) -> Unit) {
    val duration = uiState.durationMs.coerceAtLeast(1L)
    var sliderPosition by remember { mutableStateOf(uiState.positionMs.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.positionMs) {
        if (!isDragging) sliderPosition = uiState.positionMs.toFloat()
    }

    Slider(
        value = sliderPosition.coerceIn(0f, duration.toFloat()),
        valueRange = 0f..duration.toFloat(),
        onValueChange = {
            isDragging = true
            sliderPosition = it
        },
        onValueChangeFinished = {
            isDragging = false
            onSeek(sliderPosition.toLong())
        },
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatMs(uiState.positionMs), style = MaterialTheme.typography.labelMedium)
        Text(formatMs(uiState.durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            val buttonModifier = Modifier.weight(1f).height(TRANSPORT_BUTTON_HEIGHT)
            val padding = ButtonDefaults.ContentPadding
            Button(onClick = onPrevious, modifier = buttonModifier, contentPadding = padding) { Text("Prev") }
            Button(onClick = onPlayPause, modifier = buttonModifier, contentPadding = padding) {
                Text(if (isPlaying) "Pause" else "Play")
            }
            Button(onClick = onNext, modifier = buttonModifier, contentPadding = padding) { Text("Next") }
        }

        Spacer(Modifier.height(8.dp))

        // Modes read as toggles, so filled = on and outlined = off, rather than relying on an icon
        // glance the driver has to interpret.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            val modeModifier = Modifier.weight(1f).height(TRANSPORT_BUTTON_HEIGHT)
            ModeButton(
                label = if (shuffleEnabled) "Shuffle On" else "Shuffle Off",
                active = shuffleEnabled,
                onClick = onToggleShuffle,
                modifier = modeModifier,
            )
            ModeButton(
                label = when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> "Repeat All"
                    Player.REPEAT_MODE_ONE -> "Repeat One"
                    else -> "Repeat Off"
                },
                active = repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onCycleRepeat,
                modifier = modeModifier,
            )
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val padding = ButtonDefaults.ContentPadding
    if (active) {
        Button(onClick = onClick, modifier = modifier, contentPadding = padding) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding) { Text(label) }
    }
}

@Composable
private fun EqualizerPanel(
    eqEnabled: Boolean,
    preampDb: Float,
    bandGainsDb: FloatArray,
    presetName: String,
    onEqEnabledChange: (Boolean) -> Unit,
    onPreampChange: (Float) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onPresetSelected: (EqPreset) -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val range = GraphicEqualizerAudioProcessor.MIN_GAIN_DB..GraphicEqualizerAudioProcessor.MAX_GAIN_DB

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Equalizer", style = MaterialTheme.typography.titleMedium)
            Switch(checked = eqEnabled, onCheckedChange = onEqEnabledChange)
        }

        PresetChips(presetName = presetName, onPresetSelected = onPresetSelected)
        Spacer(Modifier.height(4.dp))

        if (vertical) {
            // Preamp sits alongside the bands as just another column, so the whole EQ reads as one
            // row of faders.
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EqFaderColumn(
                    label = "Pre",
                    gainDb = preampDb,
                    range = range,
                    onChange = onPreampChange,
                    modifier = Modifier.weight(1f),
                )
                GraphicEqualizerAudioProcessor.BAND_CENTER_FREQUENCIES_HZ.forEachIndexed { index, freqHz ->
                    EqFaderColumn(
                        label = formatFreqLabel(freqHz),
                        gainDb = bandGainsDb.getOrElse(index) { 0f },
                        range = range,
                        onChange = { onBandChange(index, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preamp", modifier = Modifier.width(56.dp))
                Slider(
                    value = preampDb,
                    valueRange = range,
                    onValueChange = onPreampChange,
                    modifier = Modifier.weight(1f),
                )
                Text("${preampDb.toInt()}dB", modifier = Modifier.width(48.dp))
            }

            GraphicEqualizerAudioProcessor.BAND_CENTER_FREQUENCIES_HZ.forEachIndexed { index, freqHz ->
                val gain = bandGainsDb.getOrElse(index) { 0f }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatFreqLabel(freqHz), modifier = Modifier.width(56.dp))
                    Slider(
                        value = gain,
                        valueRange = range,
                        onValueChange = { onBandChange(index, it) },
                        modifier = Modifier.weight(1f),
                    )
                    Text("${gain.toInt()}dB", modifier = Modifier.width(48.dp))
                }
            }
        }
    }
}

/**
 * Horizontally scrollable preset row. Shows a non-selectable "Custom" chip only once the user has
 * moved a band off every built-in curve, so the row always reflects what is actually applied.
 */
@Composable
private fun PresetChips(presetName: String, onPresetSelected: (EqPreset) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        if (presetName == EqPresets.CUSTOM) {
            item {
                FilterChip(
                    selected = true,
                    onClick = { },
                    label = { Text(EqPresets.CUSTOM) },
                )
            }
        }
        items(EqPresets.ALL) { preset ->
            FilterChip(
                selected = preset.name == presetName,
                onClick = { onPresetSelected(preset) },
                label = { Text(preset.name) },
            )
        }
    }
}

/** One fader: gain readout on top, vertical slider filling the available height, label beneath. */
@Composable
private fun EqFaderColumn(
    label: String,
    gainDb: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${gainDb.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
        VerticalSlider(
            value = gainDb,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatFreqLabel(freqHz: Float): String =
    if (freqHz >= 1000f) "${(freqHz / 1000f).toInt()}k" else "${freqHz.toInt()}"

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
