package com.atomic.atomicamp.app.library.scan

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.atomic.atomicamp.app.library.data.Track
import com.atomic.atomicamp.app.library.data.TrackDao
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Walks a granted SAF tree, extracts tags via [MediaMetadataRetriever], and upserts [Track] rows
 * into [trackDao] in small batches so the library UI fills in progressively rather than waiting
 * for the whole scan to finish -- mirroring Poweramp's own chunked scanning.
 *
 * Enumerates children via [DocumentsContract] queries directly rather than
 * `androidx.documentfile.provider.DocumentFile`, which issues an extra round-trip per entry and
 * is noticeably slower over large trees.
 */
class LibraryScanner(private val context: Context, private val trackDao: TrackDao) {

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "oga", "m4b")

        /** Filenames conventionally used for album art sitting beside the tracks. */
        val COVER_BASE_NAMES = setOf("cover", "folder", "album", "front", "albumart")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        const val BATCH_SIZE = 50

        /** SQLite allows 999 bound variables per statement; stay comfortably under it. */
        const val SQLITE_VARIABLE_LIMIT = 500
    }

    /**
     * Scans [treeUri], reporting a running count to [onProgress] as files are processed.
     *
     * Reading tags means opening every audio file, so a large library on slow removable storage
     * takes real time. Reporting progress is the difference between "working" and "hung" from the
     * driver's seat.
     */
    suspend fun scan(treeUri: Uri, onProgress: (ScanProgress) -> Unit = {}) {
        val startedAtMs = System.currentTimeMillis()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val batch = mutableListOf<Track>()
        val seenUris = mutableSetOf<String>()
        val counter = ScanCounter(startedAtMs, onProgress)

        walk(treeUri, rootDocId, relativeDir = "", batch, seenUris, counter)
        if (batch.isNotEmpty()) {
            trackDao.upsert(batch.toList())
        }
        pruneMissing(treeUri.toString(), seenUris)
        counter.emit(force = true)
    }

    /** Throttles progress emissions so a fast scan doesn't spend its time recomposing the UI. */
    private class ScanCounter(
        private val startedAtMs: Long,
        private val onProgress: (ScanProgress) -> Unit,
    ) {
        var filesScanned = 0
            private set
        private var lastEmitMs = 0L

        fun increment() {
            filesScanned++
            emit(force = false)
        }

        fun emit(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmitMs < PROGRESS_INTERVAL_MS) return
            lastEmitMs = now
            onProgress(ScanProgress(filesScanned, now - startedAtMs))
        }

        private companion object {
            const val PROGRESS_INTERVAL_MS = 250L
        }
    }

    /**
     * Drops rows for files that were in this tree before but are gone now -- deleted tracks, or a
     * USB stick reorganized between scans. Without this, a rescan only ever adds, leaving entries
     * that fail on play.
     *
     * Deletes the stale set rather than clearing the folder and reinserting: an interrupted scan
     * then costs nothing, and the UI never blinks through an empty library. Chunked to stay under
     * SQLite's bound-variable limit.
     */
    private suspend fun pruneMissing(folderUri: String, seenUris: Set<String>) {
        val stale = trackDao.urisInFolder(folderUri).filterNot { it in seenUris }
        stale.chunked(SQLITE_VARIABLE_LIMIT).forEach { trackDao.deleteByUris(it) }
    }

    private suspend fun walk(
        treeUri: Uri,
        parentDocId: String,
        relativeDir: String,
        batch: MutableList<Track>,
        seenUris: MutableSet<String>,
        counter: ScanCounter,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
        )

        val children = mutableListOf<Triple<String, String, String>>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val docIdIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                children += Triple(cursor.getString(docIdIdx), cursor.getString(nameIdx), cursor.getString(mimeIdx))
            }
        }

        // Plenty of libraries keep art as cover.jpg beside the tracks rather than embedded in
        // every file, so find it once per directory and use it when a file has no embedded art.
        val folderArtUri = children.firstOrNull { (_, name, mime) ->
            mime != Document.MIME_TYPE_DIR &&
                name.substringBeforeLast('.').lowercase() in COVER_BASE_NAMES &&
                name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        }?.let { (docId, _, _) -> DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) }

        for ((docId, name, mime) in children) {
            if (mime == Document.MIME_TYPE_DIR) {
                val childRelativeDir = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
                walk(treeUri, docId, childRelativeDir, batch, seenUris, counter)
            } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                seenUris += fileUri.toString()
                extractTrack(fileUri, treeUri, relativeDir, name, folderArtUri)?.let { batch += it }
                counter.increment()
                if (batch.size >= BATCH_SIZE) {
                    trackDao.upsert(batch.toList())
                    batch.clear()
                }
            }
        }
    }

    /** Broad catch is intentional: these are arbitrary user files, and one bad file must not abort the scan. */
    private fun extractTrack(
        fileUri: Uri,
        treeUri: Uri,
        relativeDir: String,
        fileName: String,
        folderArtUri: Uri?,
    ): Track? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri)
            fun tag(key: Int): String? = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

            val taggedTitle = tag(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val taggedArtist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val taggedAlbum = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val taggedTrackNumber = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.toIntOrNull()

            // Only consulted where a tag is missing -- a real tag always wins over a guess.
            val guess = if (taggedTitle == null || taggedArtist == null ||
                taggedAlbum == null || taggedTrackNumber == null
            ) {
                PathMetadataInference.infer(relativeDir, fileName)
            } else {
                InferredMetadata()
            }

            val title = taggedTitle ?: guess.title ?: fileName.substringBeforeLast('.')
            val artist = taggedArtist ?: guess.artist ?: "Unknown Artist"
            val album = taggedAlbum ?: guess.album ?: "Unknown Album"
            val trackNumber = taggedTrackNumber ?: guess.trackNumber ?: 0

            val metadataInferred = (taggedTitle == null && guess.title != null) ||
                (taggedArtist == null && guess.artist != null) ||
                (taggedAlbum == null && guess.album != null) ||
                (taggedTrackNumber == null && guess.trackNumber != null)

            val albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: artist
            val genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
            val composer = tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: ""
            val year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0
            val discNumber = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ?.substringBefore('/')?.toIntOrNull() ?: 0
            val durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val albumArtPath = cacheAlbumArt(retriever, albumArtist, album, folderArtUri)

            Track(
                uri = fileUri.toString(),
                folderUri = treeUri.toString(),
                relativeDir = relativeDir,
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                genre = genre,
                composer = composer,
                year = year,
                trackNumber = trackNumber,
                discNumber = discNumber,
                durationMs = durationMs,
                albumArtPath = albumArtPath,
                dateAddedMs = System.currentTimeMillis(),
                metadataInferred = metadataInferred,
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Album art is cached once per artist+album, not once per track. Prefers art embedded in the
     * file and falls back to a cover image sitting in the same folder.
     */
    private fun cacheAlbumArt(
        retriever: MediaMetadataRetriever,
        albumArtist: String,
        album: String,
        folderArtUri: Uri?,
    ): String? {
        val key = MessageDigest.getInstance("MD5")
            .digest("$albumArtist|$album".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val artDir = File(context.filesDir, "album_art").apply { mkdirs() }
        val artFile = File(artDir, "$key.jpg")
        if (artFile.exists()) return artFile.absolutePath

        return try {
            val embedded = retriever.embeddedPicture
            if (embedded != null) {
                FileOutputStream(artFile).use { it.write(embedded) }
                artFile.absolutePath
            } else if (folderArtUri != null) {
                context.contentResolver.openInputStream(folderArtUri)?.use { input ->
                    FileOutputStream(artFile).use { output -> input.copyTo(output) }
                }
                artFile.takeIf { it.exists() }?.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
