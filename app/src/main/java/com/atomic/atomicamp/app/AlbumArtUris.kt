package com.atomic.atomicamp.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Turns a cached album-art file path into a URI other processes can actually open.
 *
 * The art lives in app-private storage, so a `file://` URI is useless to anyone else — the system
 * media notification, lock screen, and a head unit's own now-playing panel all load artwork in
 * their own process and simply get ENOENT. A FileProvider `content://` URI is readable once the
 * permission is granted.
 */
object AlbumArtUris {

    private const val AUTHORITY_SUFFIX = ".albumart"

    /** Null when there is no art, or the file has since gone (a cleared cache, say). */
    fun contentUriFor(context: Context, path: String?): Uri? {
        val file = path?.let(::File) ?: return null
        if (!file.exists()) return null
        return try {
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        } catch (e: IllegalArgumentException) {
            // Outside the paths the provider is configured to serve.
            null
        }
    }
}
