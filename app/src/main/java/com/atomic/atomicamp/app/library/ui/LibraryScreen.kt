package com.atomic.atomicamp.app.library.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.atomic.atomicamp.app.PlayerViewModel
import com.atomic.atomicamp.app.library.data.AlbumSummary
import com.atomic.atomicamp.app.library.data.Track
import java.io.File

/**
 * Minimum row height for use in a moving vehicle.
 *
 * Deliberately well above the 48dp platform minimum. The target head unit reports density 1.0
 * while its panel is actually ~210ppi, so every dp renders physically *smaller* there than the
 * same number would on a phone — 48dp lands under 6mm. Sizing by phone intuition produces targets
 * that are genuinely hard to hit while driving.
 */
private val ROW_MIN_HEIGHT = 76.dp

/**
 * Minimum column width for list grids. Yields 3 columns on the 1280dp head unit, 2 on a 1024dp
 * screen, and 1 in phone portrait — without hardcoding a count per device.
 */
private val LIST_COLUMN_MIN_WIDTH = 380.dp

@Composable
fun LibraryScreen(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onNavigateToNowPlaying: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {},
) {
    val tab by libraryViewModel.tab.collectAsState()
    val isScanning by libraryViewModel.isScanning.collectAsState()
    val scanProgress by libraryViewModel.scanProgress.collectAsState()
    val searchQuery by libraryViewModel.searchQuery.collectAsState()
    val searchResults by libraryViewModel.searchResults.collectAsState()
    val isSearching = searchQuery.isNotBlank()

    val addFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { libraryViewModel.addFolder(it) } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Wide + short (the in-car head unit) gets multiple columns; a portrait phone gets one.
        val isWide = maxWidth > maxHeight

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Library", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onNavigateToDiagnostics) { Text("Info") }
                    Button(onClick = { libraryViewModel.rescanAll() }) { Text("Rescan") }
                    Button(onClick = { addFolderLauncher.launch(null) }) { Text("Add folder") }
                    Button(onClick = onNavigateToNowPlaying) { Text("Now Playing") }
                }
            }

            if (isScanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                // Total file count isn't known until the walk finishes, so report what has been
                // done rather than a percentage that would have to lie.
                val progress = scanProgress
                Text(
                    text = when {
                        progress == null -> "Scanning…"
                        progress.filesPerSecond == null -> "Scanning… ${progress.filesScanned} tracks"
                        else -> "Scanning… ${progress.filesScanned} tracks " +
                            "(%.0f/sec)".format(progress.filesPerSecond)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            SearchField(
                query = searchQuery,
                onQueryChange = libraryViewModel::setSearchQuery,
                onClear = libraryViewModel::clearSearch,
            )

            Spacer(Modifier.height(8.dp))

            // Searching spans the whole library, so the tab strip would be misleading while it is
            // active -- results replace it rather than sitting inside one tab.
            if (isSearching) {
                SearchResults(
                    results = searchResults,
                    isWide = isWide,
                    onPlay = { index ->
                        playerViewModel.playFromLibrary(searchResults, index)
                        onNavigateToNowPlaying()
                    },
                )
                return@Column
            }

            TabRow(selectedTabIndex = tab.ordinal) {
                LibraryTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { libraryViewModel.selectTab(entry) },
                        text = { Text(entry.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (tab) {
                LibraryTab.SONGS -> SongsTab(libraryViewModel, playerViewModel, onNavigateToNowPlaying, isWide)
                LibraryTab.ALBUMS -> AlbumsTab(libraryViewModel, playerViewModel, onNavigateToNowPlaying, isWide)
                LibraryTab.ARTISTS -> ArtistsTab(libraryViewModel, playerViewModel, onNavigateToNowPlaying, isWide)
                LibraryTab.FOLDERS -> FoldersTab(libraryViewModel, playerViewModel, onNavigateToNowPlaying, isWide)
                LibraryTab.PLAYLISTS -> PlaylistsTab(libraryViewModel, playerViewModel, onNavigateToNowPlaying, isWide)
            }
        }
    }
}

@Composable
private fun PlaylistsTab(
    vm: LibraryViewModel,
    player: PlayerViewModel,
    onPlay: () -> Unit,
    isWide: Boolean,
) {
    val selected by vm.selectedPlaylist.collectAsState()

    if (selected != null) {
        val tracks by vm.playlistTracks.collectAsState()
        val playlist = selected!!
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { vm.closePlaylist() }) { Text("Back") }
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (tracks.isNotEmpty()) {
                    Button(onClick = {
                        player.playFromLibrary(tracks, 0)
                        onPlay()
                    }) { Text("Play all") }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (tracks.isEmpty()) {
                // Entries can outlive their media -- an empty list here may mean the stick is out,
                // not that the playlist is empty.
                EmptyHint("Nothing playable here. If this playlist isn't empty, its media may be unavailable.")
                return@Column
            }

            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = LIST_COLUMN_MIN_WIDTH)) {
                gridItemsIndexed(tracks) { index, track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            TrackRow(track) {
                                player.playFromLibrary(tracks, index)
                                onPlay()
                            }
                        }
                        TextButton(onClick = { vm.removeFromPlaylist(playlist.id, track.uri) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
        return
    }

    val playlists by vm.playlists.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = { showCreate = true }) { Text("New playlist") }
        Spacer(Modifier.height(8.dp))

        if (playlists.isEmpty()) {
            EmptyHint("No playlists yet.")
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = LIST_COLUMN_MIN_WIDTH)) {
                gridItems(playlists) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.openPlaylist(playlist) }
                            .heightIn(min = ROW_MIN_HEIGHT)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                pluralize(playlist.trackCount, "track"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { vm.deletePlaylist(playlist.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NewPlaylistDialog(
            onDismiss = { showCreate = false },
            onCreate = { name ->
                vm.createPlaylist(name)
                showCreate = false
            },
        )
    }
}

@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search songs, artists, albums") },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        },
    )
}

@Composable
private fun SearchResults(results: List<Track>, isWide: Boolean, onPlay: (Int) -> Unit) {
    if (results.isEmpty()) {
        EmptyHint("No matches.")
        return
    }
    TrackList(results, isWide, onPlay)
}

/** Track lists go multi-column on wide screens so more is reachable without scrolling. */
@Composable
private fun TrackList(tracks: List<Track>, isWide: Boolean, onPlay: (Int) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = LIST_COLUMN_MIN_WIDTH)) {
        gridItemsIndexed(tracks) { index, track -> TrackRow(track) { onPlay(index) } }
    }
}

@Composable
private fun SongsTab(vm: LibraryViewModel, player: PlayerViewModel, onPlay: () -> Unit, isWide: Boolean) {
    val songs by vm.songs.collectAsState()
    if (songs.isEmpty()) {
        EmptyHint("No songs yet. Add a folder to scan.")
        return
    }
    TrackList(songs, isWide) { index ->
        player.playFromLibrary(songs, index)
        onPlay()
    }
}

@Composable
private fun AlbumsTab(vm: LibraryViewModel, player: PlayerViewModel, onPlay: () -> Unit, isWide: Boolean) {
    val selectedAlbum by vm.selectedAlbum.collectAsState()
    if (selectedAlbum != null) {
        val tracks by vm.albumTracks.collectAsState()
        Column(modifier = Modifier.fillMaxSize()) {
            Button(onClick = { vm.closeAlbum() }) { Text("Back to Albums") }
            Spacer(Modifier.height(8.dp))
            Text(selectedAlbum!!.album, style = MaterialTheme.typography.titleMedium)
            TrackList(tracks, isWide) { index ->
                player.playFromLibrary(tracks, index)
                onPlay()
            }
        }
        return
    }

    val albums by vm.albums.collectAsState()
    if (albums.isEmpty()) {
        EmptyHint("No albums yet. Add a folder to scan.")
        return
    }
    if (isWide) {
        // Art-forward tiles: the album cover is the fastest thing to recognize at a glance.
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 200.dp)) {
            gridItems(albums) { album -> AlbumTile(album) { vm.openAlbum(album) } }
        }
    } else {
        LazyColumn {
            items(albums) { album -> AlbumRow(album) { vm.openAlbum(album) } }
        }
    }
}

@Composable
private fun ArtistsTab(vm: LibraryViewModel, player: PlayerViewModel, onPlay: () -> Unit, isWide: Boolean) {
    val selectedArtist by vm.selectedArtist.collectAsState()
    if (selectedArtist != null) {
        val tracks by vm.artistTracks.collectAsState()
        Column(modifier = Modifier.fillMaxSize()) {
            Button(onClick = { vm.closeArtist() }) { Text("Back to Artists") }
            Spacer(Modifier.height(8.dp))
            Text(selectedArtist!!, style = MaterialTheme.typography.titleMedium)
            TrackList(tracks, isWide) { index ->
                player.playFromLibrary(tracks, index)
                onPlay()
            }
        }
        return
    }

    val artists by vm.artists.collectAsState()
    if (artists.isEmpty()) {
        EmptyHint("No artists yet. Add a folder to scan.")
        return
    }
    val artistRow: @Composable (com.atomic.atomicamp.app.library.data.ArtistSummary) -> Unit = { artist ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.openArtist(artist.artist) }
                .heightIn(min = ROW_MIN_HEIGHT)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(artist.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${pluralize(artist.albumCount, "album")} • ${pluralize(artist.trackCount, "track")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = LIST_COLUMN_MIN_WIDTH)) {
        gridItems(artists) { artist -> artistRow(artist) }
    }
}

@Composable
private fun FoldersTab(vm: LibraryViewModel, player: PlayerViewModel, onPlay: () -> Unit, isWide: Boolean) {
    val currentFolder by vm.currentFolder.collectAsState()
    val childNames by vm.folderChildNames.collectAsState()
    val tracks by vm.folderTracks.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (currentFolder.isNotEmpty()) {
                Button(onClick = { vm.goToParentFolder() }) { Text("Up") }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                currentFolder.ifEmpty { "Root" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))

        if (childNames.isEmpty() && tracks.isEmpty()) {
            EmptyHint("No folders yet. Add a folder to scan.")
            return@Column
        }

        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = LIST_COLUMN_MIN_WIDTH)) {
            gridItems(childNames) { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.openFolder(name) }
                        .heightIn(min = ROW_MIN_HEIGHT)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$name/", style = MaterialTheme.typography.bodyLarge)
                }
            }
            gridItemsIndexed(tracks) { index, track ->
                TrackRow(track) {
                    player.playFromLibrary(tracks, index)
                    onPlay()
                }
            }
        }
    }
}

/** Square, art-forward album tile for the wide grid. */
@Composable
private fun AlbumTile(album: AlbumSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        AlbumArtThumb(album.albumArtPath, size = 140.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            album.album,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            album.albumArtist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AlbumRow(album: AlbumSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtThumb(album.albumArtPath)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(album.album, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${album.albumArtist} • ${pluralize(album.trackCount, "track")}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArtThumb(track.albumArtPath)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${track.artist} • ${track.album}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.metadataInferred) {
                    // These details were guessed from the file path, not read from a tag. Say so
                    // rather than letting a guess pass as fact.
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "guessed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumArtThumb(path: String?, size: Dp = 48.dp) {
    if (path != null) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

private fun pluralize(count: Int, noun: String): String = "$count $noun${if (count == 1) "" else "s"}"

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
