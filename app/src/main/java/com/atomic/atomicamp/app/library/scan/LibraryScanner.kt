package com.atomic.atomicamp.app.library.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.atomic.atomicamp.engine.cloud.B2Client
import com.atomic.atomicamp.engine.cloud.B2Settings
import com.atomic.atomicamp.engine.cloud.B2Uris
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

        /** Longest edge kept for cached art. Ample for a thumbnail or the Now Playing pane. */
        const val MAX_ART_EDGE_PX = 512
        const val ART_JPEG_QUALITY = 85

        const val SCHEME_FILE = "file"
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
        val batch = mutableListOf<Track>()
        val seenIds = mutableSetOf<String>()
        val counter = ScanCounter(startedAtMs, onProgress)

        // The ATOTO's AICE firmware ships no DocumentsUI at all -- measured on the unit, where
        // OPEN_DOCUMENT_TREE resolves to nothing -- so on that device SAF is not a slow path but
        // an absent one. A file:// root walks the filesystem directly instead. Everything past
        // the walk is shared: tags, art and cue sheets are read through Uri either way.
        if (B2Uris.isB2(treeUri)) {
            walkB2(treeUri, batch, seenIds, counter)
        } else if (treeUri.scheme == SCHEME_FILE) {
            treeUri.path?.let { path ->
                walkFiles(File(path), relativeDir = "", treeUri, batch, seenIds, counter)
            }
        } else {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            walk(treeUri, rootDocId, relativeDir = "", batch, seenIds, counter)
        }

        if (batch.isNotEmpty()) {
            trackDao.upsert(batch.toList())
        }
        pruneMissing(treeUri.toString(), seenIds)
        counter.emit(force = true)
    }

    /**
     * Walks a bucket rather than a directory tree.
     *
     * Two things make this different from the local walks rather than a third copy of them.
     *
     * The listing is flat: one paginated request returns every object under the prefix, so there
     * is no recursion and no request per folder. Against a library this size that is the
     * difference between one scan and thousands of round trips.
     *
     * And nothing is opened. Reading tags means fetching each file's header over the network, and
     * at sixteen thousand files that is a scan measured in hours for information the path usually
     * already carries. Metadata is inferred from the path and the rows are marked as inferred, so
     * the UI can say so honestly rather than presenting a guess as a fact.
     */
    private suspend fun walkB2(
        treeUri: Uri,
        batch: MutableList<Track>,
        seenIds: MutableSet<String>,
        counter: ScanCounter,
    ) {
        val client = B2Client(B2Settings(context), context)
        if (!client.isConfigured) return

        val bucket = B2Uris.bucketOf(treeUri)
        val prefix = B2Uris.pathOf(treeUri).let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
        val objects = client.listAll(prefix)
        val now = System.currentTimeMillis()

        // The listing already contains the jpegs sitting beside the audio, so the cover for every
        // album is known here for free. Only its location is recorded; downloading thousands of
        // images before a note is played would be the expensive way to get the same result.
        val artByFolder = HashMap<String, String>()
        for (obj in objects) {
            val name = obj.path.substringAfterLast('/')
            val base = name.substringBeforeLast('.').lowercase()
            val extension = name.substringAfterLast('.', "").lowercase()
            if (base in COVER_BASE_NAMES && extension in IMAGE_EXTENSIONS) {
                artByFolder.putIfAbsent(obj.path.substringBeforeLast('/', ""), obj.path)
            }
        }

        for (obj in objects) {
            val name = obj.path.substringAfterLast('/')
            if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) continue

            val relativeDir = obj.path
                .removePrefix(prefix)
                .substringBeforeLast('/', "")
            val fileUri = B2Uris.forPath(bucket, obj.path)
            val guess = PathMetadataInference.infer(relativeDir, name)

            val id = Track.idFor(fileUri, null)
            seenIds += id
            batch += Track(
                id = id,
                uri = fileUri,
                folderUri = treeUri.toString(),
                relativeDir = relativeDir,
                title = guess.title ?: name.substringBeforeLast('.'),
                artist = guess.artist ?: "Unknown Artist",
                album = guess.album ?: "Unknown Album",
                albumArtist = guess.artist ?: "Unknown Artist",
                genre = "",
                composer = "",
                year = 0,
                trackNumber = guess.trackNumber ?: 0,
                discNumber = 0,
                // Unknown until the file is opened. Media3 reports the real duration on play.
                durationMs = 0L,
                // A b2:// path rather than a local one: resolved to a cached file the first
                // time this album is played. See CloudArt.
                albumArtPath = artByFolder[obj.path.substringBeforeLast('/', "")]
                    ?.let { B2Uris.forPath(bucket, it) },
                dateAddedMs = now,
                metadataInferred = true,
            )
            counter.increment()
            if (batch.size >= BATCH_SIZE) {
                trackDao.upsert(batch.toList())
                batch.clear()
            }
        }
    }

    /** Filesystem twin of [walk], for roots reached without SAF. */
    private suspend fun walkFiles(
        dir: File,
        relativeDir: String,
        treeUri: Uri,
        batch: MutableList<Track>,
        seenIds: MutableSet<String>,
        counter: ScanCounter,
    ) {
        // Sorted so a scan is reproducible; listFiles order is filesystem-dependent.
        val children = runCatching { dir.listFiles() }.getOrNull()?.sortedBy { it.name } ?: return

        val folderArtUri = children.firstOrNull { child ->
            !child.isDirectory &&
                child.name.substringBeforeLast('.').lowercase() in COVER_BASE_NAMES &&
                child.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        }?.let { Uri.fromFile(it) }

        val cueSplit = readSingleFileCueFrom(children)

        for (child in children) {
            if (child.isDirectory) {
                val childRelativeDir =
                    if (relativeDir.isEmpty()) child.name else "$relativeDir/${child.name}"
                walkFiles(child, childRelativeDir, treeUri, batch, seenIds, counter)
            } else if (child.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                val fileUri = Uri.fromFile(child)

                val cueTracks = if (cueSplit != null && cueSplit.matches(child.name)) {
                    extractCueTracks(fileUri, treeUri, relativeDir, cueSplit.sheet, folderArtUri)
                } else {
                    null
                }

                if (cueTracks != null) {
                    cueTracks.forEach { seenIds += it.id }
                    batch += cueTracks
                } else {
                    seenIds += Track.idFor(fileUri.toString(), null)
                    extractTrack(fileUri, treeUri, relativeDir, child.name, folderArtUri)?.let { batch += it }
                }
                counter.increment()
                if (batch.size >= BATCH_SIZE) {
                    trackDao.upsert(batch.toList())
                    batch.clear()
                }
            }
        }
    }

    /** Filesystem twin of [readSingleFileCue]; same multi-FILE rule applies. */
    private fun readSingleFileCueFrom(children: List<File>): CueSplit? {
        val cue = children.firstOrNull {
            !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() == "cue"
        } ?: return null
        val text = runCatching { cue.readText() }.getOrNull() ?: return null
        val sheet = CueParser.parse(text)
        return if (sheet.isSingleFile && sheet.tracks.isNotEmpty()) CueSplit(sheet) else null
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
    private suspend fun pruneMissing(folderUri: String, seenIds: Set<String>) {
        val stale = trackDao.trackIdsInFolder(folderUri).filterNot { it in seenIds }
        stale.chunked(SQLITE_VARIABLE_LIMIT).forEach { trackDao.deleteByIds(it) }
    }

    private suspend fun walk(
        treeUri: Uri,
        parentDocId: String,
        relativeDir: String,
        batch: MutableList<Track>,
        seenIds: MutableSet<String>,
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

        // An album ripped as one continuous file is described entirely by its cue sheet; without
        // reading it the whole album indexes as a single hour-long entry.
        val cueSplit = readSingleFileCue(treeUri, children)

        for ((docId, name, mime) in children) {
            if (mime == Document.MIME_TYPE_DIR) {
                val childRelativeDir = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
                walk(treeUri, docId, childRelativeDir, batch, seenIds, counter)
            } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                val cueTracks = if (cueSplit != null && cueSplit.matches(name)) {
                    extractCueTracks(fileUri, treeUri, relativeDir, cueSplit.sheet, folderArtUri)
                } else {
                    null
                }

                if (cueTracks != null) {
                    cueTracks.forEach { seenIds += it.id }
                    batch += cueTracks
                } else {
                    seenIds += Track.idFor(fileUri.toString(), null)
                    extractTrack(fileUri, treeUri, relativeDir, name, folderArtUri)?.let { batch += it }
                }
                counter.increment()
                if (batch.size >= BATCH_SIZE) {
                    trackDao.upsert(batch.toList())
                    batch.clear()
                }
            }
        }
    }

    /** A cue sheet in this directory that describes one continuous audio file. */
    private class CueSplit(val sheet: CueSheet) {
        /** The cue names its audio file; only that file gets split by it. */
        fun matches(fileName: String): Boolean =
            sheet.audioFileName?.equals(fileName, ignoreCase = true) == true
    }

    /**
     * Reads a cue sheet from this directory, if one describes a single continuous file.
     *
     * Sheets naming several files describe an album that is already one file per track, with times
     * restarting at zero for each — splitting on those would stack every track at 0:00, so they are
     * ignored and their files indexed normally.
     */
    private fun readSingleFileCue(
        treeUri: Uri,
        children: List<Triple<String, String, String>>,
    ): CueSplit? {
        val cueDocId = children.firstOrNull { (_, name, mime) ->
            mime != Document.MIME_TYPE_DIR && name.substringAfterLast('.', "").lowercase() == "cue"
        }?.first ?: return null

        val cueUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cueDocId)
        val text = try {
            context.contentResolver.openInputStream(cueUri)?.use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            null
        } ?: return null

        val sheet = CueParser.parse(text)
        return if (sheet.isSingleFile && sheet.tracks.isNotEmpty()) CueSplit(sheet) else null
    }

    /**
     * Turns one audio file into the tracks its cue sheet describes.
     *
     * The file is opened once for its duration and art; per-track metadata comes from the sheet,
     * not from re-reading the file for every track.
     */
    private fun extractCueTracks(
        fileUri: Uri,
        treeUri: Uri,
        relativeDir: String,
        sheet: CueSheet,
        folderArtUri: Uri?,
    ): List<Track>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, fileUri)
            val fileDurationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val albumArtist = sheet.albumPerformer ?: "Unknown Artist"
            val album = sheet.albumTitle ?: "Unknown Album"
            val albumArtPath = cacheAlbumArt(retriever, albumArtist, album, folderArtUri)
            val now = System.currentTimeMillis()

            sheet.tracks.map { cue ->
                // The sheet has no end for the final track; it runs to the end of the file.
                val endMs = cue.endMs
                val durationMs = (endMs ?: fileDurationMs) - cue.startMs
                Track(
                    id = Track.idFor(fileUri.toString(), cue.startMs),
                    uri = fileUri.toString(),
                    folderUri = treeUri.toString(),
                    relativeDir = relativeDir,
                    title = cue.title,
                    artist = cue.performer ?: albumArtist,
                    album = album,
                    albumArtist = albumArtist,
                    genre = "",
                    composer = "",
                    year = sheet.year ?: 0,
                    trackNumber = cue.trackNumber,
                    discNumber = 0,
                    durationMs = durationMs.coerceAtLeast(0L),
                    albumArtPath = albumArtPath,
                    dateAddedMs = now,
                    // Everything came from the sheet, which is a real source, not a guess.
                    metadataInferred = false,
                    clipStartMs = cue.startMs,
                    clipEndMs = endMs,
                )
            }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
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

            // Only the fields that identify the track count as "guessed". A file tagged properly
            // but missing just a track number is not uncertain in any way the user cares about,
            // and flagging it would make the badge meaningless through overuse.
            val metadataInferred = (taggedTitle == null && guess.title != null) ||
                (taggedArtist == null && guess.artist != null) ||
                (taggedAlbum == null && guess.album != null)

            val albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: artist
            val genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
            val composer = tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: ""
            val year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull() ?: 0
            val discNumber = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ?.substringBefore('/')?.toIntOrNull() ?: 0
            val durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val albumArtPath = cacheAlbumArt(retriever, albumArtist, album, folderArtUri)

            Track(
                id = Track.idFor(fileUri.toString(), null),
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
            val source: ByteArray? = retriever.embeddedPicture
                ?: folderArtUri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
            if (source == null) return null
            writeBoundedArt(source, artFile)
            artFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downscales art before caching. Covers are routinely 1000px+ and hundreds of KB, but this is
     * only ever shown as a thumbnail or a ~400dp pane. Bounding it keeps the cache small, makes
     * list scrolling cheaper, and — the reason it matters — keeps the bytes small enough to hand
     * to the media session, which crosses a Binder transaction with a hard size limit.
     */
    private fun writeBoundedArt(source: ByteArray, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)

        val largestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            // Power-of-two subsampling during decode, so the full-size bitmap never exists.
            inSampleSize = generateSequence(1) { it * 2 }
                .first { largestEdge / it <= MAX_ART_EDGE_PX }
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
        if (bitmap == null) {
            // Undecodable: keep the original rather than losing the art entirely.
            FileOutputStream(target).use { it.write(source) }
            return
        }
        try {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, ART_JPEG_QUALITY, out)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
