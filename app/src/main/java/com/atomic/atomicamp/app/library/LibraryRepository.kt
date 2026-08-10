package com.atomic.atomicamp.app.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.atomic.atomicamp.app.library.data.AlbumSummary
import com.atomic.atomicamp.app.library.data.ArtistSummary
import com.atomic.atomicamp.app.library.data.LibraryDatabase
import com.atomic.atomicamp.app.library.data.MusicFolder
import com.atomic.atomicamp.app.library.data.Playlist
import com.atomic.atomicamp.app.library.data.PlaylistSummary
import com.atomic.atomicamp.app.library.data.Track
import com.atomic.atomicamp.app.library.scan.LibraryScanner
import com.atomic.atomicamp.app.library.scan.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Owns the local library database and the SAF scanner. This is Poweramp's model: an independent
 * library built from folders the user explicitly grants, not Android's `MediaStore`.
 */
class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = LibraryDatabase.get(appContext)
    private val trackDao = db.trackDao()
    private val folderDao = db.musicFolderDao()
    private val playlistDao = db.playlistDao()
    private val scanner = LibraryScanner(appContext, trackDao)

    val folders: Flow<List<MusicFolder>> = folderDao.all()
    val allTracks: Flow<List<Track>> = trackDao.allTracks()
    val albums: Flow<List<AlbumSummary>> = trackDao.albums()
    val artists: Flow<List<ArtistSummary>> = trackDao.artists()

    fun tracksByAlbum(album: String, albumArtist: String): Flow<List<Track>> =
        trackDao.tracksByAlbum(album, albumArtist)

    fun tracksByArtist(artist: String): Flow<List<Track>> = trackDao.tracksByArtist(artist)

    fun tracksInDir(dir: String): Flow<List<Track>> = trackDao.tracksInDir(dir)

    /**
     * Substring search over title/artist/album. Escapes SQL wildcards in [query] so a literal `%`
     * typed by the user matches a `%` rather than everything.
     */
    fun search(query: String, limit: Int = SEARCH_RESULT_LIMIT): Flow<List<Track>> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return trackDao.search("%$escaped%", limit)
    }

    // -- Playlists --

    val playlists: Flow<List<PlaylistSummary>> = playlistDao.playlists()

    fun tracksInPlaylist(playlistId: Long): Flow<List<Track>> = playlistDao.tracksInPlaylist(playlistId)

    suspend fun createPlaylist(name: String, trackUris: List<String> = emptyList()): Long =
        withContext(Dispatchers.IO) {
            val id = playlistDao.insertPlaylist(
                Playlist(name = name.trim(), dateCreatedMs = System.currentTimeMillis()),
            )
            if (trackUris.isNotEmpty()) playlistDao.appendTracks(id, trackUris)
            id
        }

    suspend fun deletePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addToPlaylist(playlistId: Long, trackUris: List<String>) = withContext(Dispatchers.IO) {
        playlistDao.appendTracks(playlistId, trackUris)
    }

    suspend fun removeFromPlaylist(playlistId: Long, trackId: String) = withContext(Dispatchers.IO) {
        playlistDao.removeTrackAndCompact(playlistId, trackId)
    }

    private companion object {
        const val SEARCH_RESULT_LIMIT = 500
    }

    fun relativeDirsUnder(prefix: String): Flow<List<String>> =
        trackDao.relativeDirsUnder(prefix, likePattern = if (prefix.isEmpty()) "%" else "$prefix/%")

    /** Immediate child folder names under [prefix] (""=root), derived from the cached directory set. */
    fun childFolderNames(prefix: String, dirsAtOrUnderPrefix: List<String>): List<String> {
        val children = sortedSetOf<String>()
        for (dir in dirsAtOrUnderPrefix) {
            if (dir == prefix) continue
            val remainder = if (prefix.isEmpty()) dir else dir.removePrefix("$prefix/")
            val nextSegment = remainder.substringBefore('/')
            if (nextSegment.isNotEmpty()) children += nextSegment
        }
        return children.toList()
    }

    suspend fun addFolder(
        treeUri: Uri,
        displayName: String,
        onProgress: (ScanProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        appContext.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        folderDao.insert(
            MusicFolder(uri = treeUri.toString(), displayName = displayName, dateAddedMs = System.currentTimeMillis()),
        )
        scanner.scan(treeUri, onProgress)
    }

    suspend fun rescanAll(onProgress: (ScanProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        // Counts continue across folders so the user sees one total, not a per-folder reset.
        var alreadyScanned = 0
        for (folder in folderDao.allOnce()) {
            var lastInFolder = 0
            scanner.scan(Uri.parse(folder.uri)) { progress ->
                lastInFolder = progress.filesScanned
                onProgress(progress.copy(filesScanned = alreadyScanned + progress.filesScanned))
            }
            alreadyScanned += lastInFolder
        }
    }

    suspend fun removeFolder(folder: MusicFolder) = withContext(Dispatchers.IO) {
        trackDao.deleteByFolder(folder.uri)
        folderDao.delete(folder)
    }
}
