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
import com.atomic.atomicamp.engine.PlaybackPreferences
import com.atomic.atomicamp.engine.PlaybackService
import com.atomic.atomicamp.engine.dsp.EqPresets
import com.atomic.atomicamp.engine.dsp.GraphicEqualizerAudioProcessor
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.atomic.atomicamp.app.library.data.Track as LibraryTrack

data class Track(
    /** Library track id, which for a cue-split rip is not the same as the file [uri]. */
    val id: String,
    val uri: Uri,
    val title: String,
    val subtitle: String? = null,
    /**
     * Artwork as a `content://` URI string rather than a filesystem path, so the same value works
     * both here and for consumers outside this process. Coil loads content URIs directly.
     */
    val albumArtUri: String? = null,
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
    /** Volume leveling between tracks. */
    val levelerEnabled: Boolean = false,
    /** Start playing again by itself when the unit powers up. */
    val resumeOnBoot: Boolean = true,
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
            attachArtworkBytesToCurrentItem()
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
        _uiState.value = _uiState.value.copy(
            resumeOnBoot = PlaybackPreferences.resumeOnBoot(getApplication()),
        )
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
                id = item.mediaId,
                uri = item.localConfiguration?.uri ?: Uri.parse(item.mediaId),
                title = metadata.title?.toString() ?: item.mediaId,
                subtitle = listOfNotNull(artist, album).takeIf { it.isNotEmpty() }?.joinToString(" • "),
                // Keep the whole URI: its path component alone is a provider-relative path, not
                // something that can be opened.
                albumArtUri = metadata.artworkUri?.toString(),
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
                    levelerEnabled = extras.getBoolean(PlaybackService.EXTRA_LEVELER_ENABLED),
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
        // Ad-hoc files picked outside the library: the file is the whole track, so its uri
        // doubles as its id.
        val newTracks = uris.map { uri -> Track(id = uri.toString(), uri = uri, title = queryDisplayName(uri)) }
        val mediaItems = newTracks.map {
            MediaItem.Builder().setUri(it.uri).setMediaId(it.id).build()
        }
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
            // Must be a content:// URI, not file://: consumers outside this process load artwork
            // themselves and cannot open our private storage.
            AlbumArtUris.contentUriFor(getApplication(), track.albumArtPath)
                ?.let(metadataBuilder::setArtworkUri)

            val builder = MediaItem.Builder()
                .setUri(track.uri)
                // The id, not the uri: a cue-split album has many tracks in one file.
                .setMediaId(track.id)
                .setMediaMetadata(metadataBuilder.build())

            track.clipStartMs?.let { start ->
                // Plays a slice of a longer rip. No end position means run to the end of the file,
                // which is right for the last track on a cue sheet.
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start)
                        .apply { track.clipEndMs?.let(::setEndPositionMs) }
                        .build(),
                )
            }
            builder.build()
        }

        c.setMediaItems(mediaItems, startIndex.coerceIn(0, tracks.lastIndex), 0L)
        c.prepare()
        c.play()

        _uiState.value = _uiState.value.copy(
            queue = tracks.map {
                Track(
                    id = it.id,
                    uri = Uri.parse(it.uri),
                    title = it.title,
                    subtitle = "${it.artist} • ${it.album}",
                    albumArtUri = AlbumArtUris.contentUriFor(getApplication(), it.albumArtPath)?.toString(),
                )
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

    /**
     * Puts the current track's artwork into its metadata as raw bytes.
     *
     * A `content://` URI is enough for anything running in this process, but the system media
     * notification, lock screen, and a head unit's own now-playing panel each load artwork in
     * *their* process, and a non-exported FileProvider gives them nothing to open. Bytes travel
     * with the metadata, so no cross-process file access is needed.
     *
     * Applied to one item at a time rather than baked into every queue entry on purpose: metadata
     * crosses a Binder transaction with a hard size limit, and a few hundred covers embedded at
     * once would exceed it. Only the playing item is ever displayed externally.
     */
    private fun attachArtworkBytesToCurrentItem() {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index !in 0 until c.mediaItemCount) return

        val item = c.getMediaItemAt(index)
        val metadata = item.mediaMetadata
        if (metadata.artworkData != null) return
        val artUri = metadata.artworkUri ?: return

        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(artUri)?.use { it.readBytes() }
                }.getOrNull()
            } ?: return@launch

            val current = controller ?: return@launch
            // The track may have moved on while the bytes were being read.
            if (current.currentMediaItemIndex != index) return@launch

            val updated = item.buildUpon()
                .setMediaMetadata(
                    metadata.buildUpon()
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build(),
                )
                .build()
            current.replaceMediaItem(index, updated)
        }
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

    fun setLevelerEnabled(enabled: Boolean) {
        val c = controller ?: return
        val args = Bundle().apply { putBoolean(PlaybackService.EXTRA_LEVELER_ENABLED, enabled) }
        c.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_SET_LEVELER, Bundle.EMPTY), args)
        _uiState.value = _uiState.value.copy(levelerEnabled = enabled)
    }

    fun setResumeOnBoot(enabled: Boolean) {
        PlaybackPreferences.setResumeOnBoot(getApplication(), enabled)
        _uiState.value = _uiState.value.copy(resumeOnBoot = enabled)
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
