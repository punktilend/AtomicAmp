package com.atomic.atomicamp.engine.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/** One object in the bucket. [path] is the full B2 file name, e.g. `Music/Artist/Album/01.flac`. */
data class B2Object(val path: String, val sizeBytes: Long)

/**
 * Minimal Backblaze B2 client: authorise, list, and sign a download URL.
 *
 * Uses `HttpURLConnection` rather than pulling in an HTTP library. Media3 already carries its own
 * networking for the actual audio, and these are three request shapes -- a dependency would be
 * more code to keep current than this is to maintain.
 *
 * **Auth is refreshed rather than cached forever.** B2 tokens expire (24 hours for the API token),
 * and a head unit that has been sitting in a cold car for a week will come back to an expired one.
 * [authorizeIfNeeded] re-authorises on demand, and callers that get a 401 can force it.
 */
class B2Client(
    private val settings: B2Settings,
    private val context: Context? = null,
) {

    private data class Session(
        val apiUrl: String,
        val downloadUrl: String,
        val accountId: String,
        val token: String,
        val obtainedAtMs: Long,
    )

    @Volatile
    private var session: Session? = null

    @Volatile
    private var bucketId: String? = null

    val isConfigured: Boolean get() = settings.isConfigured

    /**
     * Whether the device has a usable network right now.
     *
     * Checked before any request because the alternative is measurable: offline, a cached track
     * took 24 seconds to start, all of it spent waiting for connection attempts to time out
     * before the cache was used. Asking first turns that into no delay at all.
     */
    private fun hasNetwork(): Boolean {
        val ctx = context ?: return true
        val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Synchronized
    fun authorizeIfNeeded(force: Boolean = false): Boolean {
        val current = session
        val fresh = current != null &&
            System.currentTimeMillis() - current.obtainedAtMs < TOKEN_LIFETIME_MS
        if (fresh && !force) return true
        if (!settings.isConfigured) return false
        // Offline, there is nothing to gain by trying; the cache is the only useful source.
        if (!hasNetwork()) return false

        return try {
            val credentials = "${settings.keyId}:${settings.appKey}"
            val encoded = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
            val json = request(
                url = "$AUTH_URL/b2api/v2/b2_authorize_account",
                authorization = "Basic $encoded",
                body = null,
            ) ?: return false

            session = Session(
                apiUrl = json.getString("apiUrl"),
                downloadUrl = json.getString("downloadUrl"),
                accountId = json.getString("accountId"),
                token = json.getString("authorizationToken"),
                obtainedAtMs = System.currentTimeMillis(),
            )
            bucketId = null
            true
        } catch (e: Exception) {
            session = null
            false
        }
    }

    /**
     * Resolves the bucket id by name.
     *
     * A key restricted to one bucket already carries its id, but a full-access key does not, and
     * the app has to work with either.
     */
    private fun bucketId(): String? {
        bucketId?.let { return it }
        val active = session ?: return null
        return try {
            val json = request(
                url = "${active.apiUrl}/b2api/v2/b2_list_buckets",
                authorization = active.token,
                body = JSONObject().put("accountId", active.accountId),
            ) ?: return null
            val buckets = json.getJSONArray("buckets")
            for (i in 0 until buckets.length()) {
                val bucket = buckets.getJSONObject(i)
                if (bucket.getString("bucketName") == settings.bucket) {
                    return bucket.getString("bucketId").also { bucketId = it }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Every object under [prefix], following pagination to the end.
     *
     * No delimiter, so this walks the whole subtree in one pass rather than a request per folder.
     * Against a library of this size that is the difference between one scan and thousands.
     */
    fun listAll(prefix: String, onProgress: (Int) -> Unit = {}): List<B2Object> {
        if (!authorizeIfNeeded()) return emptyList()
        val active = session ?: return emptyList()
        val bucket = bucketId() ?: return emptyList()

        val out = mutableListOf<B2Object>()
        var startFileName: String? = null
        do {
            val payload = JSONObject()
                .put("bucketId", bucket)
                .put("prefix", prefix)
                .put("maxFileCount", PAGE_SIZE)
            if (startFileName != null) payload.put("startFileName", startFileName)

            val json = request(
                url = "${active.apiUrl}/b2api/v2/b2_list_file_names",
                authorization = active.token,
                body = payload,
            ) ?: return out

            val files = json.getJSONArray("files")
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                if (file.optString("action") == "folder") continue
                out += B2Object(file.getString("fileName"), file.optLong("contentLength"))
            }
            onProgress(out.size)
            startFileName = if (json.isNull("nextFileName")) null else json.getString("nextFileName")
        } while (startFileName != null)

        return out
    }

    /**
     * A URL Media3 can GET directly, with the auth token in the query string.
     *
     * The bucket is private, so an unsigned URL 401s. Put in the query rather than a header
     * because this is handed to the player, which opens it itself.
     */
    fun signedUrl(path: String): String? {
        if (!authorizeIfNeeded()) return null
        val active = session ?: return null
        val encodedPath = path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val token = URLEncoder.encode(active.token, "UTF-8")
        return "${active.downloadUrl}/file/${settings.bucket}/$encodedPath?Authorization=$token"
    }

    private fun request(url: String, authorization: String, body: JSONObject?): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            setRequestProperty("Authorization", authorization)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = body != null
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val AUTH_URL = "https://api.backblazeb2.com"
        const val PAGE_SIZE = 1000
        /**
         * Short on purpose. These calls sit in front of playback starting, so a slow failure is
         * worse than a fast one -- the cache is right there behind them.
         */
        const val TIMEOUT_MS = 8_000

        /** B2 API tokens are good for 24 hours; refresh well inside that. */
        const val TOKEN_LIFETIME_MS = 12L * 60 * 60 * 1000
    }
}
