package com.atomic.atomicamp.app.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.atomic.atomicamp.app.library.tags.FlacTags

/**
 * Edits the tags of one track, writing them into the file itself.
 *
 * Only FLAC files reachable by path can be edited. A track held through a SAF grant cannot: those
 * grants are taken read-only, and asking for write access is a different permission conversation
 * than the one the user had when they added the folder. Rather than fail at save time, the screen
 * says so up front and offers nothing but a way back.
 */
@Composable
fun TagEditorScreen(
    viewModel: LibraryViewModel,
    trackId: String,
    trackTitle: String,
    onDone: () -> Unit,
) {
    var loaded by remember { mutableStateOf(false) }
    var editable by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val fields = remember {
        mutableStateOf(
            linkedMapOf(
                FlacTags.TITLE to "",
                FlacTags.ARTIST to "",
                FlacTags.ALBUM to "",
                FlacTags.ALBUM_ARTIST to "",
                FlacTags.GENRE to "",
                FlacTags.DATE to "",
                FlacTags.TRACK_NUMBER to "",
                FlacTags.DISC_NUMBER to "",
            ),
        )
    }

    LaunchedEffect(trackId) {
        val existing = viewModel.readTags(trackId)
        if (existing == null) {
            editable = false
        } else {
            fields.value = LinkedHashMap(fields.value).apply {
                keys.toList().forEach { key -> this[key] = existing[key].orEmpty() }
            }
        }
        loaded = true
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Edit tags", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDone) { Text("Cancel") }
                Button(
                    enabled = editable && loaded && !saving,
                    onClick = {
                        saving = true
                        error = null
                        viewModel.saveTags(trackId, fields.value) { ok ->
                            saving = false
                            if (ok) onDone() else error = "Could not write to this file."
                        }
                    },
                ) { Text(if (saving) "Saving..." else "Save") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = trackTitle,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(16.dp))

        when {
            !loaded -> Text("Reading tags...", style = MaterialTheme.typography.bodyLarge)

            !editable -> Text(
                "This track can't be edited here. Tag editing rewrites the file, which needs " +
                    "direct access to it — tracks added through the system folder picker are " +
                    "granted read-only, and files that aren't FLAC aren't supported yet.",
                style = MaterialTheme.typography.bodyLarge,
            )

            else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                fields.value.keys.toList().forEach { key ->
                    OutlinedTextField(
                        value = fields.value[key].orEmpty(),
                        onValueChange = { updated ->
                            fields.value = LinkedHashMap(fields.value).apply { this[key] = updated }
                        },
                        label = { Text(labelFor(key)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Blank fields are removed from the file. The audio is never rewritten, and " +
                        "artwork is kept.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun labelFor(key: String): String = when (key) {
    FlacTags.TITLE -> "Title"
    FlacTags.ARTIST -> "Artist"
    FlacTags.ALBUM -> "Album"
    FlacTags.ALBUM_ARTIST -> "Album artist"
    FlacTags.GENRE -> "Genre"
    FlacTags.DATE -> "Year"
    FlacTags.TRACK_NUMBER -> "Track number"
    FlacTags.DISC_NUMBER -> "Disc number"
    else -> key
}
