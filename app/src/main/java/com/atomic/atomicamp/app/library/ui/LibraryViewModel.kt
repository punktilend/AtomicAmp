package com.atomic.atomicamp.app.library.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atomic.atomicamp.app.library.LibraryRepository
import com.atomic.atomicamp.app.library.data.AlbumSummary
import com.atomic.atomicamp.app.library.data.ArtistSummary
import com.atomic.atomicamp.app.library.data.MusicFolder
import com.atomic.atomicamp.app.library.data.Track
import com.atomic.atomicamp.app.library.scan.ScanProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab { SONGS, ALBUMS, ARTISTS, FOLDERS }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }

    private val repository = LibraryRepository(application)
    private val whileUsed = SharingStarted.WhileSubscribed(5000)

    private val _tab = MutableStateFlow(LibraryTab.SONGS)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)
    val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Debounced so typing doesn't run a `LIKE` scan per keystroke, and blank-trimmed so a query of
     * only spaces doesn't match the entire library.
     */
    val searchResults: StateFlow<List<Track>> = _searchQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) flowOf(emptyList()) else repository.search(trimmed)
        }
        .stateIn(viewModelScope, whileUsed, emptyList())

    val songs: StateFlow<List<Track>> =
        repository.allTracks.stateIn(viewModelScope, whileUsed, emptyList())
    val albums: StateFlow<List<AlbumSummary>> =
        repository.albums.stateIn(viewModelScope, whileUsed, emptyList())
    val artists: StateFlow<List<ArtistSummary>> =
        repository.artists.stateIn(viewModelScope, whileUsed, emptyList())
    val folders: StateFlow<List<MusicFolder>> =
        repository.folders.stateIn(viewModelScope, whileUsed, emptyList())

    // -- Folders tab: browse by relativeDir, mirroring the real SAF folder structure --
    private val _currentFolder = MutableStateFlow("")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    val folderChildNames: StateFlow<List<String>> = _currentFolder
        .flatMapLatest { prefix ->
            repository.relativeDirsUnder(prefix).map { dirs -> repository.childFolderNames(prefix, dirs) }
        }
        .stateIn(viewModelScope, whileUsed, emptyList())

    val folderTracks: StateFlow<List<Track>> = _currentFolder
        .flatMapLatest { dir -> repository.tracksInDir(dir) }
        .stateIn(viewModelScope, whileUsed, emptyList())

    // -- Albums/Artists tabs: drill-down detail --
    private val _selectedAlbum = MutableStateFlow<AlbumSummary?>(null)
    val selectedAlbum: StateFlow<AlbumSummary?> = _selectedAlbum.asStateFlow()
    val albumTracks: StateFlow<List<Track>> = _selectedAlbum
        .flatMapLatest { album ->
            if (album == null) flowOf(emptyList()) else repository.tracksByAlbum(album.album, album.albumArtist)
        }
        .stateIn(viewModelScope, whileUsed, emptyList())

    private val _selectedArtist = MutableStateFlow<String?>(null)
    val selectedArtist: StateFlow<String?> = _selectedArtist.asStateFlow()
    val artistTracks: StateFlow<List<Track>> = _selectedArtist
        .flatMapLatest { artist -> if (artist == null) flowOf(emptyList()) else repository.tracksByArtist(artist) }
        .stateIn(viewModelScope, whileUsed, emptyList())

    fun selectTab(newTab: LibraryTab) {
        _tab.value = newTab
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    fun openFolder(name: String) {
        _currentFolder.value = if (_currentFolder.value.isEmpty()) name else "${_currentFolder.value}/$name"
    }

    fun goToParentFolder() {
        _currentFolder.value = _currentFolder.value.substringBeforeLast('/', "")
    }

    fun openAlbum(album: AlbumSummary) {
        _selectedAlbum.value = album
    }

    fun closeAlbum() {
        _selectedAlbum.value = null
    }

    fun openArtist(artist: String) {
        _selectedArtist.value = artist
    }

    fun closeArtist() {
        _selectedArtist.value = null
    }

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = null
            try {
                val displayName = uri.lastPathSegment?.substringAfterLast(':') ?: "Music folder"
                repository.addFolder(uri, displayName) { _scanProgress.value = it }
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun rescanAll() {
        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = null
            try {
                repository.rescanAll { _scanProgress.value = it }
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun removeFolder(folder: MusicFolder) {
        viewModelScope.launch { repository.removeFolder(folder) }
    }
}
