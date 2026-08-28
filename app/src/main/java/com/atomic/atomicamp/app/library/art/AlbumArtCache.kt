package com.atomic.atomicamp.app.library.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The on-disk cache of album art, keyed by artist and album rather than by track.
 *
 * Shared because two things now write into it: the scanner, which pulls art out of local files as
 * it walks them, and the cloud fetcher, which downloads a cover the first time an album is played.
 * Keying them the same way is what lets the second one fill in gaps left by the first.
 */
object AlbumArtCache {

    /** Longest edge kept. Ample for a thumbnail or the Now Playing pane. */
    const val MAX_EDGE_PX = 512
    private const val JPEG_QUALITY = 85

    fun fileFor(context: Context, albumArtist: String, album: String): File {
        val key = MessageDigest.getInstance("MD5")
            .digest("$albumArtist|$album".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "album_art").apply { mkdirs() }
        return File(dir, "$key.jpg")
    }

    /**
     * Downscales art before caching. Covers are routinely 1000px+ and hundreds of KB, but this is
     * only ever shown as a thumbnail or a ~400dp pane. Bounding it keeps the cache small, makes
     * list scrolling cheaper, and -- the reason it matters -- keeps the bytes small enough to hand
     * to the media session, which crosses a Binder transaction with a hard size limit.
     */
    fun writeBounded(source: ByteArray, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)

        val largestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            // Power-of-two subsampling during decode, so the full-size bitmap never exists.
            inSampleSize = generateSequence(1) { it * 2 }
                .first { largestEdge / it <= MAX_EDGE_PX }
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size, options)
        if (bitmap == null) {
            // Undecodable: keep the original rather than losing the art entirely.
            FileOutputStream(target).use { it.write(source) }
            return
        }
        try {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
