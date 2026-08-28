package com.atomic.atomicamp.engine.cloud

import android.net.Uri

/**
 * The `b2://bucket/path` form the library stores for cloud tracks.
 *
 * A signed download URL carries an auth token that expires, so storing one in the database would
 * mean a queue that stops working overnight -- and this app persists its queue precisely so it can
 * come back after the ignition has been off for a week. The stored uri is therefore stable and
 * meaningless to the network; it is turned into a signed URL at the moment playback opens it.
 */
object B2Uris {

    const val SCHEME = "b2"

    fun forPath(bucket: String, path: String): String =
        Uri.Builder().scheme(SCHEME).authority(bucket).path(path).build().toString()

    fun isB2(uri: Uri): Boolean = uri.scheme == SCHEME

    fun isB2(uri: String): Boolean = uri.startsWith("$SCHEME://")

    /** The B2 file name, i.e. the uri path without its leading slash. */
    fun pathOf(uri: Uri): String = uri.path.orEmpty().removePrefix("/")

    fun bucketOf(uri: Uri): String = uri.authority.orEmpty()
}
