package com.atomic.atomicamp.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.atomic.atomicamp.app.diagnostics.DiagnosticsScreen
import com.atomic.atomicamp.app.library.ui.LibraryScreen
import com.atomic.atomicamp.app.library.ui.LibraryViewModel

private const val ROUTE_LIBRARY = "library"
private const val ROUTE_NOW_PLAYING = "nowPlaying"
private const val ROUTE_DIAGNOSTICS = "diagnostics"

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels()

    // Activity-scoped so Now Playing can save the queue as a playlist against the same library
    // state the Library destination is showing.
    private val libraryViewModel: LibraryViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = ROUTE_LIBRARY) {
                        composable(ROUTE_LIBRARY) {
                            LibraryScreen(
                                libraryViewModel = libraryViewModel,
                                playerViewModel = playerViewModel,
                                onNavigateToNowPlaying = { navController.navigate(ROUTE_NOW_PLAYING) },
                                onNavigateToDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                            )
                        }
                        composable(ROUTE_NOW_PLAYING) {
                            PlayerScreen(
                                viewModel = playerViewModel,
                                onNavigateToLibrary = { navController.popBackStack() },
                                onSaveQueueAsPlaylist = { name, uris ->
                                    libraryViewModel.createPlaylist(name, uris)
                                },
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
