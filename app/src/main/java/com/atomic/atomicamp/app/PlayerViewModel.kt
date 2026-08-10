package com.atomic.atomicamp.app

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.atomic.atomicamp.engine.PlaybackService
import com.atomic.atomicamp.engine.dsp.EqPresets
import com.atomic.atomicamp.engine.dsp.GraphicEqualizerAudioProcessor
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.atomic.atomicamp.app.library.data.Track as LibraryTrack

data class Track(
    val uri: Uri,
    val title: String,
    val subtitle: String? = null,
    val albumArtPath: String? = null,
)

data class PlayerUiState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val eqEnabled: Boolean = true,
    val preampDb: Float = 0f,
    val bandGainsDb: FloatArray = FloatArray(GraphicEqualizerAudioProcessor.BAND_COUNT),
    val presetName: String = EqPresets.FLAT.name,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var controller: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _uiState.value = _uiState.value.copy(shuffleEnabled = shuffleModeEnabled)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _uiState.value = _uiState.value.copy(repeatMode = repeatMode)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _uiState.value = _uiState.value.copy(
                currentIndex = controller?.currentMediaItemIndex ?: -1,
                durationMs = controller?.duration?.coerceAtLeast(0) ?: 0L,
            )
        }

        /**
         * The queue itself changed — items added or removed, or shuffle reordering it. Rebuild
         * from the player rather than trying to mirror each mutation locally and drift out of sync.
         */
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            syncQueueFromEngine()
        }
    }

    init {
        val app = getApplication<Application>()
        val sessionToken = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(app, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                controller = controllerFuture.get()
                controller?.addListener(playerListener)
                syncEqStateFromEngine()
                syncQueueFromEngine()
                syncModesFromEngine()
                startPositionUpdates()
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * Rebuilds the visible queue from whatever the player already holds. The service restores a
     * persisted queue on startup and outlives this UI, so the player -- not the ViewModel -- knows
     * what is actually loaded; without this a resumed session would read "Nothing queued".
     */
    private fun syncQueueFromEngine() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return

        val restored = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            val metadata = item.mediaMetadata
            val artist = metadata.artist?.toString()
            val album = metadata.albumTitle?.toString()
            Track(
                uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId),
                title = metadata.title?.toString() ?: item.mediaId,
                subtitle = listOfNotNull(artist, album).takeIf { it.isNotEmpty() }?.joinToString(" • "),
                albumArtPath = metadata.artworkUri?.path,
            )
        }

        _uiState.value = _uiState.value.copy(
            queue = restored,
            currentIndex = c.currentMediaItemIndex,
            durationMs = c.duration.coerceAtLeast(0),
            positionMs = c.currentPosition.coerceAtLeast(0),
        )
    }

    /** Shuffle/repeat live on the player and persist independently of the queue. */
    private fun syncModesFromEngine() {
        val c = controller ?: return
        _uiState.value = _uiState.value.copy(
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
        )
    }

    /**
     * Pulls the equalizer's actual state out of the service. The engine is the source of truth --
     * it restores persisted settings on startup and may already be applying a curve before this UI
     * ever exists, so assuming zeros here would show sliders that disagree with what's audible.
     */
    private fun syncEqStateFromEngine() {
        val c = controller ?: return
        val future = c.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_GET_EQ_STATE, Bundle.EMPTY),
            Bundle.EMPTY,
        )
        future.addListener(
            {
                val extras = runCatching { future.get() }.getOrNull()?.extras ?: return@addListener
                val gains = extras.getFloatArray(PlaybackService.EXTRA_BAND_GAINS)
                    ?: FloatArray(GraphicEqualizerAudioProcessor.BAND_COUNT)
                _uiState.value = _uiState.value.copy(
                    bandGainsDb = gains,
                    preampDb = extras.getFloat(PlaybackService.EXTRA_GAIN_DB),
                    eqEnabled = extras.getBoolean(PlaybackService.EXTRA_ENABLED, true),
                    presetName = extras.getString(PlaybackService.EXTRA_PRESET_NAME)
                        ?: EqPresets.FLAT.name,
                )
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun startPositionUpdates() {
        viewModelScope.launch {
            while (true) {
                controller?.let { c ->
                    _uiState.value = _uiState.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = c.duration.coerceAtLeast(0),
                        isPlaying = c.isPlaying,
                    )
                }
                delay(500)
            }
        }
    }

    fun addTracks(uris: List<Uri>) {
        val c = controller ?: return
        val newTracks = uris.map { uri -> Track(uri, queryDisplayName(uri)) }
        val mediaItems = newTracks.map { MediaItem.fromUri(it.uri) }
        val wasEmpty = _uiState.value.queue.isEmpty()
        c.addMediaItems(mediaItems)
        c.prepare()
        if (wasEmpty) {
            c.seekTo(0, 0)
            c.play()
        }
        _uiState.value = _uiState.value.copy(queue = _uiState.value.queue + newTracks)
    }

    /** Replaces the queue with [tracks] and starts playback at [startIndex] -- used when playing from the library. */
    fun playFromLibrary(tracks: List<LibraryTrack>, startIndex: Int) {
        val c = controller ?: return
        if (tracks.isEmpty()) return

        val mediaItems = tracks.map { track ->
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
            track.albumArtPath?.let { path -> metadataBuilder.setArtworkUri(Uri.fromFile(File(path))) }
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaId(track.uri)
                .setMediaMetadata(metadataBuilder.build())
                .build()
        }

        c.setMediaItems(mediaItems, startIndex.coerceIn(0, tracks.lastIndex), 0L)
        c.prepare()
        c.play()

        _uiState.value = _uiState.value.copy(
            queue = tracks.map {
                Track(Uri.parse(it.uri), it.title, "${it.artist} • ${it.album}", it.albumArtPath)
            },
            currentIndex = startIndex,
        )
    }

    private fun queryDisplayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment ?: uri.toString()
        } catch (e: SecurityException) {
            uri.lastPathSegment ?: uri.toString()
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun skipNext() {
        controller?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    /** Jumps straight to a queue entry, e.g. from the queue list. */
    fun playQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
        // onTimelineChanged rebuilds the visible queue.
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Cycles off -> all -> one, the order every other player uses. */
    fun cycleRepeatMode() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        val c = controller ?: return
        val args = Bundle().apply {
            putInt(PlaybackService.EXTRA_BAND_INDEX, bandIndex)
            putFloat(PlaybackService.EXTRA_GAIN_DB, gainDb)
        }
        c.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_SET_BAND_GAIN, Bundle.EMPTY), args)
        val gains = _uiState.value.bandGainsDb.copyOf()
        gains[bandIndex] = gainDb
        _uiState.value = _uiState.value.copy(
            bandGainsDb = gains,
            // Mirrors the engine's own rule so the highlighted chip stays truthful.
            presetName = EqPresets.matching(gains)?.name ?: EqPresets.CUSTOM,
        )
    }

    fun applyPreset(preset: com.atomic.atomicamp.engine.dsp.EqPreset) {
        val c = controller ?: return
        val args = Bundle().apply { putString(PlaybackService.EXTRA_PRESET_NAME, preset.name) }
        c.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_APPLY_PRESET, Bundle.EMPTY), args)
        _uiState.value = _uiState.value.copy(
            bandGainsDb = preset.gainsDb,
            presetName = preset.name,
        )
    }

    fun setPreamp(gainDb: Float) {
        val c = controller ?: return
        val args = Bundle().apply { putFloat(PlaybackService.EXTRA_GAIN_DB, gainDb) }
        c.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_SET_PREAMP, Bundle.EMPTY), args)
        _uiState.value = _uiState.value.copy(preampDb = gainDb)
    }

    fun setEqEnabled(enabled: Boolean) {
        val c = controller ?: return
        val args = Bundle().apply { putBoolean(PlaybackService.EXTRA_ENABLED, enabled) }
        c.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_SET_EQ_ENABLED, Bundle.EMPTY), args)
        _uiState.value = _uiState.value.copy(eqEnabled = enabled)
    }

    override fun onCleared() {
        controller?.release()
        controller = null
        super.onCleared()
    }
}
