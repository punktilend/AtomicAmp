package com.atomic.atomicamp.engine

import android.content.Context
import android.net.Uri
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
        const val FIELD_TITLE = "title"
        const val FIELD_ARTIST = "artist"
        const val FIELD_ALBUM = "album"
        const val FIELD_ARTWORK = "artwork"
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

        val array = JSONArray()
        for (i in 0 until count) {
            val item = player.getMediaItemAt(i)
            val metadata = item.mediaMetadata
            array.put(
                JSONObject().apply {
                    put(FIELD_URI, item.localConfiguration?.uri?.toString() ?: item.mediaId)
                    putOpt(FIELD_TITLE, metadata.title?.toString())
                    putOpt(FIELD_ARTIST, metadata.artist?.toString())
                    putOpt(FIELD_ALBUM, metadata.albumTitle?.toString())
                    putOpt(FIELD_ARTWORK, metadata.artworkUri?.toString())
                },
            )
        }

        prefs.edit()
            .putString(KEY_QUEUE, array.toString())
            .putInt(KEY_INDEX, player.currentMediaItemIndex)
            .putLong(KEY_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            .apply()
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
                items += MediaItem.Builder()
                    .setUri(uri)
                    .setMediaId(uri)
                    .setMediaMetadata(metadata)
                    .build()
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
