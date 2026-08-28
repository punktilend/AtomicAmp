package com.atomic.atomicamp.engine.cloud

import android.content.Context

/**
 * Where the cloud library's credentials live.
 *
 * Deliberately **not** compiled into the app. The build seeds these once from local.properties so
 * there is nothing to type on first run, but from then on they are settings, which matters for
 * two reasons: the key can be replaced without a rebuild, and a key that lives in preferences can
 * be a *different, weaker* key than whatever was on the build machine.
 *
 * That second point is not theoretical here. The key this was seeded from is not scoped to one
 * bucket -- it can read every bucket on the account -- so anything shipped to another person
 * should be pointed at a key restricted to this bucket with only listFiles and readFiles.
 */
class B2Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var keyId: String
        get() = prefs.getString(KEY_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ID, value).apply()

    var appKey: String
        get() = prefs.getString(KEY_APP_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_APP_KEY, value).apply()

    var bucket: String
        get() = prefs.getString(KEY_BUCKET, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BUCKET, value).apply()

    var prefix: String
        get() = prefs.getString(KEY_PREFIX, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PREFIX, value).apply()

    val isConfigured: Boolean
        get() = keyId.isNotBlank() && appKey.isNotBlank() && bucket.isNotBlank()

    /** Fills in anything still blank. Never overwrites a value the user has changed. */
    fun seedIfBlank(keyId: String, appKey: String, bucket: String, prefix: String) {
        if (this.keyId.isBlank() && keyId.isNotBlank()) this.keyId = keyId
        if (this.appKey.isBlank() && appKey.isNotBlank()) this.appKey = appKey
        if (this.bucket.isBlank() && bucket.isNotBlank()) this.bucket = bucket
        if (this.prefix.isBlank() && prefix.isNotBlank()) this.prefix = prefix
    }

    private companion object {
        const val PREFS_NAME = "atomicamp_cloud"
        const val KEY_ID = "b2_key_id"
        const val KEY_APP_KEY = "b2_app_key"
        const val KEY_BUCKET = "b2_bucket"
        const val KEY_PREFIX = "b2_prefix"
    }
}
