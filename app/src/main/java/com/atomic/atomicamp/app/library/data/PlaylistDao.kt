package com.atomic.atomicamp.app.library.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    /**
     * Counts only entries whose media is actually present, so a playlist doesn't advertise tracks
     * that can't be played because the stick is unplugged.
     */
    @Query(
        """
        SELECT p.id AS id,
               p.name AS name,
               (SELECT COUNT(*) FROM playlist_tracks pt
                  JOIN tracks t ON t.uri = pt.trackUri
                 WHERE pt.playlistId = p.id) AS trackCount
        FROM playlists p
        ORDER BY p.name COLLATE NOCASE
        """
    )
    fun playlists(): Flow<List<PlaylistSummary>>

    /**
     * Inner join drops entries whose media is missing rather than surfacing dead rows: the entry
     * stays in the table and reappears when the media does.
     */
    @Query(
        """
        SELECT t.* FROM playlist_tracks pt
        JOIN tracks t ON t.uri = pt.trackUri
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.position
        """
    )
    fun tracksInPlaylist(playlistId: Long): Flow<List<Track>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistTrack>)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackUri = :trackUri")
    suspend fun removeEntry(playlistId: Long, trackUri: String)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position")
    suspend fun entries(playlistId: Long): List<PlaylistTrack>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: Long)

    /** Appends [trackUris], keeping positions contiguous from the current end. */
    @Transaction
    suspend fun appendTracks(playlistId: Long, trackUris: List<String>) {
        val start = nextPosition(playlistId)
        insertEntries(trackUris.mapIndexed { i, uri -> PlaylistTrack(playlistId, start + i, uri) })
    }

    /**
     * Rewrites positions so they stay contiguous after a removal. Without this, positions develop
     * gaps and later appends can collide with the composite primary key.
     */
    @Transaction
    suspend fun removeTrackAndCompact(playlistId: Long, trackUri: String) {
        removeEntry(playlistId, trackUri)
        val remaining = entries(playlistId)
        clearEntries(playlistId)
        insertEntries(remaining.mapIndexed { i, e -> e.copy(position = i) })
    }
}
