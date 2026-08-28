package com.atomic.atomicamp.app.library.art

import android.content.Context
import android.net.Uri
import com.atomic.atomicamp.app.library.data.Track
import com.atomic.atomicamp.engine.cloud.B2Client
import com.atomic.atomicamp.engine.cloud.B2Settings
import com.atomic.atomicamp.engine.cloud.B2Uris
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches album art for cloud tracks, once per album, the first time it is wanted.
 *
 * The scan knows where every cover is -- the same flat listing that finds the audio finds the
 * jpegs beside it -- so it records the art's `b2://` path and downloads nothing. Downloading it
 * there instead would mean thousands of images pulled before a note is played, most of them for
 * albums nobody opens.
 *
 * So the scan stores a pointer and this resolves it on demand: one download for an album someone
 * actually listens to, cached under the same artist+album key the local scanner uses, and the row
 * rewritten to point at the local file so it is never fetched twice.
 */
object CloudArt {

    fun isRemote(albumArtPath: String?): Boolean =
        albumArtPath != null && B2Uris.isB2(albumArtPath)

    /**
     * Returns a local path for [track]'s art, downloading it if the stored path is still remote.
     * Null when there is no art or the fetch fails; callers simply show no art.
     */
    fun ensureLocal(context: Context, track: Track): String? {
        val remote = track.albumArtPath ?: return null
        if (!B2Uris.isB2(remote)) return remote

        val target = AlbumArtCache.fileFor(context, track.albumArtist, track.album)
        if (target.exists() && target.length() > 0) return target.absolutePath

        val client = B2Client(B2Settings(context), context)
        val url = client.signedUrl(B2Uris.pathOf(Uri.parse(remote))) ?: return null

        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val bytes = try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
            if (bytes.isEmpty()) return null
            AlbumArtCache.writeBounded(bytes, target)
            target.takeIf { it.exists() && it.length() > 0 }?.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private const val TIMEOUT_MS = 15_000
}
