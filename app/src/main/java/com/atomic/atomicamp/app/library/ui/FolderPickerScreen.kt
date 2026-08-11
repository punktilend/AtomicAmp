package com.atomic.atomicamp.app.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * A folder browser for devices with no document picker.
 *
 * The head unit's firmware ships no DocumentsUI, so `ACTION_OPEN_DOCUMENT_TREE` resolves to
 * nothing and the app has to provide the browsing UI itself. Rows are deliberately tall: at
 * density 1.0 on this panel a stock 48dp target is under 6mm, and this gets used at arm's length
 * in a parked car.
 */
@Composable
fun FolderPickerScreen(
    onFolderChosen: (File) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    // null means "showing the list of volumes" rather than a directory.
    var current by remember { mutableStateOf<File?>(null) }

    // Listing /storage alone is not enough: canRead() is a false negative on /storage/emulated
    // even where its children are readable. The app-visible external dirs are derived instead --
    // each sits at <volume>/Android/data/<pkg>/files, so the volume root is four levels up, and
    // that resolves a mounted USB stick even when the parent directory refuses to be listed.
    val roots = remember {
        buildList {
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                runCatching { dir.parentFile?.parentFile?.parentFile?.parentFile }
                    .getOrNull()?.let { add(it) }
            }
            add(File("/storage/emulated/0"))
            runCatching { File("/storage").listFiles() }.getOrNull().orEmpty()
                .filter { it.isDirectory && it.name != "self" && it.name != "emulated" }
                .forEach { add(it) }
        }
            .filter { it.exists() }
            .distinctBy { it.absolutePath }
            .sortedBy { it.absolutePath }
    }

    val entries = remember(current?.absolutePath) {
        current?.let { dir ->
            runCatching { dir.listFiles() }
                .getOrNull()
                .orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
        } ?: roots
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Choose a folder", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = current != null,
                    onClick = {
                        val parent = current?.parentFile
                        // Stop at the volume root rather than wandering up into /storage.
                        current = if (parent == null || roots.any { it.absolutePath == current?.absolutePath }) {
                            null
                        } else {
                            parent
                        }
                    },
                ) { Text("Up") }
                Button(onClick = onCancel) { Text("Cancel") }
                Button(
                    enabled = current != null,
                    onClick = { current?.let(onFolderChosen) },
                ) { Text("Use this folder") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = current?.absolutePath ?: "Storage volumes",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            Text(
                "No sub-folders here. \"Use this folder\" still scans this path.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries) { dir ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 76.dp)
                        .clickable { current = dir }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        text = if (current == null) dir.absolutePath else dir.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
