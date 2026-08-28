package com.atomic.atomicamp.engine.cloud

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * On-disk cache for streamed audio.
 *
 * Without this every play is a fresh download, which in a car means paying for the same album
 * repeatedly over cellular and losing the music entirely the moment signal drops. With it, a track
 * played once is played from disk after that, and the drive through a dead patch is uneventful.
 *
 * A process-wide singleton because [SimpleCache] refuses more than one instance per directory, and
 * this process has two potential users -- the player and, later, anything that pre-fetches.
 *
 * It lives in `filesDir` rather than `cacheDir` on purpose: the system may clear `cacheDir` at any
 * time, which is exactly the wrong behaviour for content someone kept deliberately for a journey.
 */
object MediaCache {

    /** Roughly a hundred albums of FLAC. Evicts least-recently-used beyond that. */
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: SimpleCache(
                File(context.applicationContext.filesDir, DIRECTORY),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { instance = it }
        }
    }

    /** Bytes currently held, for the diagnostics screen. */
    fun sizeBytes(context: Context): Long = runCatching { get(context).cacheSpace }.getOrDefault(0L)

    private const val DIRECTORY = "media-cache"
}
