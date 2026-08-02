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
    @PrimaryKey val uri: String,
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
)
