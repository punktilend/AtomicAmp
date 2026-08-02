package com.atomic.atomicamp.app.library.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracks: List<Track>)

    @Query("DELETE FROM tracks WHERE folderUri = :folderUri")
    suspend fun deleteByFolder(folderUri: String)

    @Query("SELECT uri FROM tracks WHERE folderUri = :folderUri")
    suspend fun urisInFolder(folderUri: String): List<String>

    @Query("DELETE FROM tracks WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("SELECT * FROM tracks ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, discNumber, trackNumber, title COLLATE NOCASE")
    fun allTracks(): Flow<List<Track>>

    @Query(
        """
        SELECT album, albumArtist, COUNT(*) AS trackCount, MIN(albumArtPath) AS albumArtPath, MIN(year) AS year
        FROM tracks
        GROUP BY album, albumArtist
        ORDER BY album COLLATE NOCASE
        """
    )
    fun albums(): Flow<List<AlbumSummary>>

    @Query(
        """
        SELECT artist, COUNT(*) AS trackCount, COUNT(DISTINCT album) AS albumCount
        FROM tracks
        GROUP BY artist
        ORDER BY artist COLLATE NOCASE
        """
    )
    fun artists(): Flow<List<ArtistSummary>>

    @Query(
        "SELECT * FROM tracks WHERE album = :album AND albumArtist = :albumArtist ORDER BY discNumber, trackNumber, title COLLATE NOCASE"
    )
    fun tracksByAlbum(album: String, albumArtist: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE artist = :artist ORDER BY album COLLATE NOCASE, discNumber, trackNumber")
    fun tracksByArtist(artist: String): Flow<List<Track>>

    /** All directories at or under [prefix]; the repository derives the immediate children from this. */
    @Query("SELECT DISTINCT relativeDir FROM tracks WHERE relativeDir = :prefix OR relativeDir LIKE :likePattern")
    fun relativeDirsUnder(prefix: String, likePattern: String): Flow<List<String>>

    @Query("SELECT * FROM tracks WHERE relativeDir = :dir ORDER BY discNumber, trackNumber, title COLLATE NOCASE")
    fun tracksInDir(dir: String): Flow<List<Track>>
}
