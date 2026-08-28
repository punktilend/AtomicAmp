package com.atomic.atomicamp.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the play queue and position so playback resumes where it left off.
 *
 * On a head unit the process is killed at every ignition-off, so without this you lose your place
 * in an album on every short trip. Lives in `:engine` alongside [EqualizerSettingsStore] because
 * the service owns the player and keeps running after the UI is gone -- persisting from the app
 * would miss any progress made while no UI existed.
 *
 * Queue entries are stored as JSON in [android.content.SharedPreferences]: a few dozen rows of
 * strings, written a handful of times per session. Room would mean crossing a module boundary
 * (the library database lives in `:app`) for no benefit at this size.
 */
internal class PlaybackStateStore(context: Context) {

    private companion object {
        const val PREFS_NAME = "atomicamp_playback"
        const val KEY_QUEUE = "queue_json"
        const val KEY_INDEX = "current_index"
        const val KEY_POSITION_MS = "position_ms"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_REPEAT_MODE = "repeat_mode"

        const val FIELD_URI = "uri"
        const val FIELD_MEDIA_ID = "mediaId"
        const val FIELD_TITLE = "title"
        const val FIELD_ARTIST = "artist"
        const val FIELD_ALBUM = "album"
        const val FIELD_ARTWORK = "artwork"

        // A cue-split track is a slice of a longer file; without these it would resume as the
        // whole album from the beginning.
        const val FIELD_CLIP_START = "clipStart"
        const val FIELD_CLIP_END = "clipEnd"

        /** Tracks kept either side of the current one. See the note in [save]. */
        const val MAX_PERSISTED_QUEUE = 1000
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(player: Player) {
        // Shuffle/repeat are preferences, not queue contents -- keep them even with an empty queue.
        prefs.edit()
            .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            .putInt(KEY_REPEAT_MODE, player.repeatMode)
            .apply()

        val count = player.mediaItemCount
        if (count == 0) {
            clear()
            return
        }

        // Persist a window around the current track rather than the whole queue.
        //
        // Measured, not guessed: adding a cloud library and tapping a song queued 16,398 tracks,
        // and serialising that to JSON asked for an 18 MB allocation and killed the app with an
        // OutOfMemoryError on a timeline change. A 3,350-track local library never came close.
        //
        // A window is also honest about what resume is for. Coming back after an ignition-off
        // means continuing roughly where you were, not restoring an entire library's play order,
        // and this many either side is days of continuous listening.
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        var start = (current - MAX_PERSISTED_QUEUE / 2).coerceAtLeast(0)
        val end = (start + MAX_PERSISTED_QUEUE).coerceAtMost(count)
        start = (end - MAX_PERSISTED_QUEUE).coerceAtLeast(0)

        val array = JSONArray()
        for (i in start until end) {
            val item = player.getMediaItemAt(i)
            val metadata = item.mediaMetadata
            array.put(
                JSONObject().apply {
                    put(FIELD_URI, item.localConfiguration?.uri?.toString() ?: item.mediaId)
                    put(FIELD_MEDIA_ID, item.mediaId)
                    putOpt(FIELD_TITLE, metadata.title?.toString())
                    putOpt(FIELD_ARTIST, metadata.artist?.toString())
                    putOpt(FIELD_ALBUM, metadata.albumTitle?.toString())
                    putOpt(FIELD_ARTWORK, metadata.artworkUri?.toString())
                    val clipping = item.clippingConfiguration
                    if (clipping.startPositionMs > 0L) put(FIELD_CLIP_START, clipping.startPositionMs)
                    if (clipping.endPositionMs != C.TIME_END_OF_SOURCE) {
                        put(FIELD_CLIP_END, clipping.endPositionMs)
                    }
                },
            )
        }

        prefs.edit()
            .putString(KEY_QUEUE, array.toString())
            // Relative to the window that was actually written.
            .putInt(KEY_INDEX, (current - start).coerceIn(0, (end - start - 1).coerceAtLeast(0)))
            .putLong(KEY_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    /**
     * Checkpoints only *where* playback is, and does it synchronously.
     *
     * Two reasons this is not just [save]:
     *
     * Durability. `apply()` returns before the write reaches disk, which is fine for a graceful
     * exit and useless against an ignition-off, because that cuts power. Measured: after a reboot
     * the app resumed the right track at the beginning, having computed a five-second checkpoint
     * that never made it to storage. `commit()` blocks until it is written, so a checkpoint that
     * happened is a checkpoint that survives, and the worst case becomes losing five seconds
     * rather than the whole position.
     *
     * Cost. [save] re-serialises the entire queue to JSON. On a timer, against a library of
     * thousands of tracks, that is a large write every five seconds to record a number that fits
     * in a long. The queue is already saved by the events that change it.
     */
    /** Where playback was, as last checkpointed, or null when nothing is saved. */
    fun savedPosition(): SavedPosition? {
        if (prefs.getString(KEY_QUEUE, null) == null) return null
        return SavedPosition(
            index = prefs.getInt(KEY_INDEX, 0),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
        )
    }

    data class SavedPosition(val index: Int, val positionMs: Long)

    fun savePosition(player: Player) {
        if (player.mediaItemCount == 0) return
        // The index is only meaningful against the window save() wrote, so a checkpoint updates
        // the position and leaves the index alone.
        prefs.edit()
            .putLong(KEY_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            .commit()
    }

    /**
     * Restores the saved queue into [player], seeked to where it stopped but left **paused** --
     * powering on the head unit should not start blasting audio on its own.
     *
     * Returns false when there is nothing saved, or when the saved URIs are no longer readable
     * (a revoked SAF grant, or a USB stick that isn't plugged in this time).
     */
    fun restoreInto(player: Player): Boolean {
        // Applied before the early return: these persist even when no queue was saved.
        player.shuffleModeEnabled = prefs.getBoolean(KEY_SHUFFLE, false)
        player.repeatMode = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)

        val json = prefs.getString(KEY_QUEUE, null) ?: return false

        val items = mutableListOf<MediaItem>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uri = obj.optString(FIELD_URI).takeIf { it.isNotEmpty() } ?: continue
                val metadata = MediaMetadata.Builder()
                    .setTitle(obj.optString(FIELD_TITLE).takeIf { it.isNotEmpty() })
                    .setArtist(obj.optString(FIELD_ARTIST).takeIf { it.isNotEmpty() })
                    .setAlbumTitle(obj.optString(FIELD_ALBUM).takeIf { it.isNotEmpty() })
                    .setArtworkUri(obj.optString(FIELD_ARTWORK).takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .build()
                val builder = MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(obj.optString(FIELD_MEDIA_ID).takeIf { it.isNotEmpty() } ?: uri)
                    .setMediaMetadata(metadata)

                if (obj.has(FIELD_CLIP_START) || obj.has(FIELD_CLIP_END)) {
                    builder.setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(obj.optLong(FIELD_CLIP_START, 0L))
                            .setEndPositionMs(obj.optLong(FIELD_CLIP_END, C.TIME_END_OF_SOURCE))
                            .build(),
                    )
                }
                items += builder.build()
            }
        } catch (e: org.json.JSONException) {
            clear()
            return false
        }

        if (items.isEmpty()) return false

        val index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, items.lastIndex)
        player.setMediaItems(items, index, prefs.getLong(KEY_POSITION_MS, 0L))
        player.prepare()
        return true
    }

    fun clear() {
        prefs.edit().remove(KEY_QUEUE).remove(KEY_INDEX).remove(KEY_POSITION_MS).apply()
    }
}
