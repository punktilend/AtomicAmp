package com.atomic.atomicamp.app.library.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dateCreatedMs: Long,
)

/**
 * One entry in a playlist, identified by the track's document URI.
 *
 * Deliberately **not** a foreign key onto `tracks`. A rescan prunes rows for files it can't see,
 * and on a head unit that routinely means "the USB stick isn't plugged in right now". A cascade
 * there would silently delete the user's playlists the first time they drove without the stick.
 * Keeping the URI means the entry simply resolves to nothing until the media returns.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("trackUri")],
)
data class PlaylistTrack(
    val playlistId: Long,
    val position: Int,
    val trackUri: String,
)

/** A playlist plus how many of its entries currently resolve to present media. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
)
