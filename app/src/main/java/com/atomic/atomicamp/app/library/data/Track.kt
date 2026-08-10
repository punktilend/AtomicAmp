package com.atomic.atomicamp.app.library.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One scanned audio file. [uri] is the SAF document uri and doubles as the primary key --
 * re-scanning the same file just replaces the row instead of duplicating it.
 *
 * [relativeDir] is the *directory* portion of the file's path within its granted [folderUri]
 * tree (no filename, "" for files directly under the granted root). It drives the Folders tab:
 * child folders/files under a given directory are derived from this column rather than walking
 * SAF live, so folder browsing works from the cached scan.
 */
@Entity(tableName = "tracks")
data class Track(
    /**
     * Identity of the track, which is no longer the same as the file it lives in.
     *
     * An album ripped as one FLAC with a cue sheet yields many tracks sharing a single [uri], so
     * the file path cannot be the key. For an ordinary file this is just the uri; for a cue track
     * it also carries the start offset. See [Companion.idFor].
     */
    @PrimaryKey val id: String,
    /** The media file to open. Shared by every cue track cut from the same rip. */
    val uri: String,
    val folderUri: String,
    val relativeDir: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val composer: String,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val durationMs: Long,
    val albumArtPath: String?,
    val dateAddedMs: Long,
    /**
     * True when at least one of title/artist/album/track came from the file path rather than a
     * tag. Surfaced in the UI so a guess is never presented as fact.
     */
    val metadataInferred: Boolean = false,
    /**
     * Where this track starts within [uri], for a track cut from a longer rip. Null for an
     * ordinary file, which is played whole.
     */
    val clipStartMs: Long? = null,
    /** Where it ends; null means play to the end of the file — including the last cue track. */
    val clipEndMs: Long? = null,
) {
    val isCueTrack: Boolean get() = clipStartMs != null

    companion object {
        /**
         * A file alone can't identify a track, since a cue rip puts many in one file. Including
         * the start offset keeps them distinct and stable across rescans.
         */
        fun idFor(uri: String, clipStartMs: Long?): String =
            if (clipStartMs == null) uri else "$uri#$clipStartMs"
    }
}
