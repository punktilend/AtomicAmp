package com.atomic.atomicamp.app.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Read-only device report, plus shortcuts into system settings screens.
 *
 * Exists because the target head unit runs a vendor launcher (ATOTO "AICE UI") that hides
 * Developer options and exposes no network ADB, so none of this can be read over adb. The
 * settings buttons fire framework intents directly: vendor skins routinely hide the *entry*
 * while leaving the underlying activity intact and reachable.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val metrics = context.resources.displayMetrics

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Device diagnostics", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onBack) { Text("Back") }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { context.launchSetting(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }) {
                Text("Developer options")
            }
            Button(onClick = { context.launchSetting(Settings.ACTION_SETTINGS) }) {
                Text("Android Settings")
            }
            Button(onClick = { context.launchSetting(Settings.ACTION_DEVICE_INFO_SETTINGS) }) {
                Text("About device")
            }
            Button(onClick = { CrashLog.clear(context); onBack() }) { Text("Clear crash") }
        }

        Spacer(Modifier.height(12.dp))

        val lines = buildList {
            add("== BUILD ==")
            add("manufacturer: ${Build.MANUFACTURER}")
            add("brand:        ${Build.BRAND}")
            add("model:        ${Build.MODEL}")
            add("device:       ${Build.DEVICE}")
            add("product:      ${Build.PRODUCT}")
            add("hardware:     ${Build.HARDWARE}")
            add("fingerprint:  ${Build.FINGERPRINT}")
            add("display id:   ${Build.DISPLAY}")
            add("android:      ${Build.VERSION.RELEASE}  (API ${Build.VERSION.SDK_INT})")

            add("")
            add("== SCREEN ==")
            add("px:        ${metrics.widthPixels} x ${metrics.heightPixels}")
            add("dp:        ${configuration.screenWidthDp} x ${configuration.screenHeightDp}")
            add("density:   ${metrics.density}  (${metrics.densityDpi} dpi)")
            add("layout:    ${if (configuration.screenWidthDp > configuration.screenHeightDp) "landscape" else "portrait"}")

            add("")
            add("== STORAGE VOLUMES ==")
            addAll(context.storageVolumeLines())

            add("")
            add("== APP-VISIBLE EXTERNAL DIRS ==")
            // Non-null entries beyond the first usually indicate SD/USB media the app can reach
            // without SAF at all -- the fallback path if the document picker can't see them.
            context.getExternalFilesDirs(null).forEachIndexed { i, dir ->
                add("[$i] ${dir?.absolutePath ?: "(null)"}")
            }
            add("primary state: ${Environment.getExternalStorageState()}")

            add("")
            add("== MOUNT POINTS ==")
            addAll(commonMountPointLines())

            // The library is built entirely on SAF grants. If nothing here handles
            // OPEN_DOCUMENT_TREE then Add folder has no way in on this firmware, which is a
            // design question and not a bug to be retried.
            add("")
            add("== DOCUMENT PICKER (SAF) ==")
            addAll(StorageProbe.documentPickerLines(context))

            add("")
            add("== STORAGE PERMISSIONS ==")
            addAll(StorageProbe.permissionLines(context))

            // Evidence for whether a direct-filesystem fallback could work if SAF cannot.
            add("")
            add("== DIRECT FILE READ PROBE ==")
            addAll(StorageProbe.audioProbeLines(context))

            add("")
            add("== LAST CRASH ==")
            val crash = CrashLog.read(context)
            if (crash == null) {
                add("(none recorded)")
            } else {
                addAll(crash.trimEnd().lines())
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun Context.launchSetting(action: String) {
    try {
        startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // Vendor ROMs sometimes strip the activity outright; nothing to do but report it.
    }
}

private fun Context.storageVolumeLines(): List<String> {
    val storageManager = getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        ?: return listOf("(StorageManager unavailable)")
    val volumes = storageManager.storageVolumes
    if (volumes.isEmpty()) return listOf("(none reported)")

    return volumes.map { volume ->
        val description = runCatching { volume.getDescription(this) }.getOrNull() ?: "?"
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { volume.directory?.absolutePath }.getOrNull()
        } else {
            null
        }
        "desc=$description removable=${volume.isRemovable} primary=${volume.isPrimary} " +
            "state=${runCatching { volume.state }.getOrNull()} path=${path ?: "n/a"}"
    }
}

/** Where head-unit firmware typically mounts USB sticks and SD cards. */
private fun commonMountPointLines(): List<String> {
    val candidates = listOf("/storage", "/mnt/media_rw", "/mnt/usb", "/udisk", "/mnt/sdcard")
    return candidates.map { path ->
        val dir = File(path)
        val children = runCatching { dir.list()?.joinToString(", ") }.getOrNull()
        "$path exists=${dir.exists()} readable=${dir.canRead()} -> ${children ?: "(unreadable)"}"
    }
}
