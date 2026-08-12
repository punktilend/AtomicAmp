package com.atomic.atomicamp.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.atomic.atomicamp.app.diagnostics.CrashLog
import com.atomic.atomicamp.app.diagnostics.DiagnosticsScreen
import android.net.Uri
import com.atomic.atomicamp.app.library.ui.FolderPickerScreen
import com.atomic.atomicamp.app.library.ui.LibraryScreen
import com.atomic.atomicamp.app.library.ui.LibraryViewModel
import com.atomic.atomicamp.app.library.ui.TagEditorScreen
import androidx.compose.runtime.collectAsState
import com.atomic.atomicamp.app.ui.theme.AtomicAmpTheme

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_NOW_PLAYING = "nowPlaying"
private const val ROUTE_DIAGNOSTICS = "diagnostics"
private const val ROUTE_FOLDER_PICKER = "folderPicker"
private const val ROUTE_FULLSCREEN_ART = "fullscreenArt"
private const val ROUTE_TAG_EDITOR = "tagEditor"

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    // Activity-scoped so Now Playing can save the queue as a playlist against the same library
    // state the Library destination is showing.
    private val libraryViewModel: LibraryViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Installed first so it catches anything that fails during start-up too.
        CrashLog.install(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            requestStoragePermission.launch("android.permission.READ_MEDIA_AUDIO")
        } else {
            requestStoragePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        setContent {
            AtomicAmpTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = ROUTE_LIBRARY) {
                        composable(ROUTE_LIBRARY) {
                            LibraryScreen(
                                libraryViewModel = libraryViewModel,
                                playerViewModel = playerViewModel,
                                onNavigateToNowPlaying = { navController.navigate(ROUTE_NOW_PLAYING) },
                                onNavigateToDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                                onNavigateToFolderPicker = { navController.navigate(ROUTE_FOLDER_PICKER) },
                            )
                        }
                        composable(ROUTE_FOLDER_PICKER) {
                            FolderPickerScreen(
                                onFolderChosen = { dir ->
                                    libraryViewModel.addFolder(Uri.fromFile(dir))
                                    navController.popBackStack()
                                },
                                onCancel = { navController.popBackStack() },
                            )
                        }
                        composable(ROUTE_NOW_PLAYING) {
                            PlayerScreen(
                                viewModel = playerViewModel,
                                onNavigateToLibrary = { navController.popBackStack() },
                                onSaveQueueAsPlaylist = { name, uris ->
                                    libraryViewModel.createPlaylist(name, uris)
                                },
                                onShowFullscreenArt = { navController.navigate(ROUTE_FULLSCREEN_ART) },
                                onEditTags = { navController.navigate(ROUTE_TAG_EDITOR) },
                            )
                        }
                        composable(ROUTE_TAG_EDITOR) {
                            val state = playerViewModel.uiState.collectAsState().value
                            val current = state.queue.getOrNull(state.currentIndex)
                            if (current == null) {
                                navController.popBackStack()
                            } else {
                                TagEditorScreen(
                                    viewModel = libraryViewModel,
                                    trackId = current.id,
                                    trackTitle = current.title,
                                    onDone = { navController.popBackStack() },
                                )
                            }
                        }
                        composable(ROUTE_FULLSCREEN_ART) {
                            FullscreenArtScreen(
                                viewModel = playerViewModel,
                                onExit = { navController.popBackStack() },
                            )
                        }
                        composable(ROUTE_DIAGNOSTICS) {
                            DiagnosticsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
