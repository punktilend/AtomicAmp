package com.atomic.atomicamp.app.diagnostics

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Answers two questions the head unit cannot be asked over ADB:
 *
 *  1. Does a document picker exist on this firmware at all? The library is built entirely on SAF
 *     folder grants, so if nothing handles [Intent.ACTION_OPEN_DOCUMENT_TREE] the design has no
 *     way in and needs a direct-filesystem fallback.
 *  2. If it doesn't, is a fallback even possible -- can the app read the mounted media itself?
 *
 * Both are reported rather than inferred, because vendor firmware is exactly where assumptions
 * about stock Android stop holding.
 */
object StorageProbe {

    private val AUDIO_EXTENSIONS = setOf("flac", "mp3", "m4a", "aac", "ogg", "opus", "wav", "wma")

    /** Walk limits: this runs on the UI thread and a mounted library can be enormous. */
    private const val MAX_DEPTH = 4
    private const val MAX_ENTRIES = 4000

    /** Whether SAF folder grants are possible at all here. False on the ATOTO. */
    fun hasDocumentPicker(context: Context): Boolean = runCatching {
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0)
            .isNotEmpty()
    }.getOrDefault(false)

    fun documentPickerLines(context: Context): List<String> {
        val actions = listOf(
            Intent.ACTION_OPEN_DOCUMENT_TREE to "OPEN_DOCUMENT_TREE (what Add folder uses)",
            Intent.ACTION_OPEN_DOCUMENT to "OPEN_DOCUMENT",
            Intent.ACTION_GET_CONTENT to "GET_CONTENT",
        )
        val lines = mutableListOf<String>()
        for ((action, label) in actions) {
            val intent = Intent(action).apply {
                if (action == Intent.ACTION_OPEN_DOCUMENT || action == Intent.ACTION_GET_CONTENT) {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
            }
            val matches = runCatching {
                context.packageManager.queryIntentActivities(intent, 0)
            }.getOrNull().orEmpty()

            if (matches.isEmpty()) {
                lines += "$label -> NOTHING HANDLES THIS"
            } else {
                lines += "$label -> ${matches.size} handler(s)"
                matches.forEach { lines += "    ${it.activityInfo?.packageName}/${it.activityInfo?.name}" }
            }
        }

        lines += ""
        for (pkg in listOf("com.android.documentsui", "com.google.android.documentsui")) {
            val state = runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                if (info.enabled) "installed, enabled" else "installed but DISABLED"
            }.getOrElse { "not installed" }
            lines += "$pkg: $state"
        }
        return lines
    }

    fun permissionLines(context: Context): List<String> {
        val names = buildList {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add("android.permission.READ_MEDIA_AUDIO")
        }
        return names.map { name ->
            val granted = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
            "${name.substringAfterLast('.')}: ${if (granted) "GRANTED" else "denied"}"
        }
    }

    /**
     * Counts audio files reachable with plain [File] calls under every root worth trying. A
     * non-zero count is the evidence that a direct-filesystem scanner would actually work here.
     */
    fun audioProbeLines(context: Context): List<String> {
        val roots = linkedSetOf<String>()

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        storageManager?.storageVolumes?.forEach { volume ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { volume.directory?.absolutePath }.getOrNull()?.let { roots += it }
            }
        }
        // Every app-visible external dir has the volume root four levels up:
        // <root>/Android/data/<pkg>/files
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            runCatching { dir.parentFile?.parentFile?.parentFile?.parentFile?.absolutePath }
                .getOrNull()?.let { roots += it }
        }
        File("/storage").list()?.forEach { name ->
            if (name != "self" && name != "emulated") roots += "/storage/$name"
        }
        roots += listOf("/storage/emulated/0", "/mnt/media_rw", "/mnt/usb", "/udisk", "/mnt/sdcard")

        return roots.map { path ->
            val dir = File(path)
            when {
                !dir.exists() -> "$path -> does not exist"
                !dir.canRead() -> "$path -> EXISTS but NOT READABLE"
                else -> {
                    val result = countAudio(dir)
                    "$path -> ${result.count} audio file(s)" +
                        (result.example?.let { ", e.g. $it" } ?: "") +
                        if (result.truncated) " (walk truncated)" else ""
                }
            }
        }
    }

    private class Count(var count: Int = 0, var example: String? = null, var truncated: Boolean = false)

    private fun countAudio(root: File): Count {
        val result = Count()
        var visited = 0

        fun walk(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || result.truncated) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (++visited > MAX_ENTRIES) { result.truncated = true; return }
                if (child.isDirectory) {
                    walk(child, depth + 1)
                    if (result.truncated) return
                } else if (child.extension.lowercase() in AUDIO_EXTENSIONS) {
                    result.count++
                    if (result.example == null) result.example = child.name
                }
            }
        }

        walk(root, 0)
        return result
    }
}
