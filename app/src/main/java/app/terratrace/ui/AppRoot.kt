package app.terratrace.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.terratrace.app
import app.terratrace.io.TrackParseException
import app.terratrace.ui.screens.MapScreen
import app.terratrace.ui.screens.OfflineMapsScreen
import app.terratrace.ui.screens.SettingsScreen
import app.terratrace.ui.screens.TripDetailScreen
import app.terratrace.ui.screens.TripsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** App-wide actions shared by screens. */
class AppActions(
    val snackbar: SnackbarHostState,
    val openTrip: (Long) -> Unit,
    val importTrack: () -> Unit,
    val showMessage: (String) -> Unit,
    val goTo: (String) -> Unit,
)

val LocalActions = staticCompositionLocalOf<AppActions> { error("No AppActions") }

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("map", "Bản đồ", Icons.Outlined.Map, Icons.Filled.Map),
    Tab("trips", "Hành trình", Icons.Outlined.Route, Icons.Filled.Route),
    Tab("offline", "Offline", Icons.Outlined.CloudDownload, Icons.Filled.CloudDownload),
    Tab("settings", "Cài đặt", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun AppRoot(incomingFile: MutableStateFlow<Uri?>) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    fun import(uri: Uri) {
        scope.launch {
            try {
                val id = context.app.repo.importTrack(uri, displayName(uri))
                nav.navigate("trip/$id")
                snackbar.showSnackbar("Đã nhập lộ trình và ghim lên bản đồ")
            } catch (e: TrackParseException) {
                snackbar.showSnackbar(e.message ?: "File không hợp lệ")
            } catch (e: Exception) {
                snackbar.showSnackbar("Không đọc được file: ${e.message ?: "lỗi không xác định"}")
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::import) }

    val incoming by incomingFile.collectAsStateWithLifecycle()
    LaunchedEffect(incoming) {
        incoming?.let { import(it); incomingFile.value = null }
    }

    val actions = remember {
        AppActions(
            snackbar = snackbar,
            openTrip = { nav.navigate("trip/$it") },
            importTrack = { picker.launch(arrayOf("application/gpx+xml", "application/vnd.google-earth.kml+xml", "application/xml", "text/xml", "application/octet-stream", "*/*")) },
            showMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            goTo = { route -> nav.navigateTab(route) },
        )
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in tabs.map { it.route }

    CompositionLocalProvider(LocalActions provides actions) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (showBar) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        tabs.forEach { t ->
                            val selected = route == t.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { nav.navigateTab(t.route) },
                                icon = { Icon(if (selected) t.selectedIcon else t.icon, t.label) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavHost(nav, startDestination = "map") {
                    composable("map") { MapScreen() }
                    composable("trips") { TripsScreen() }
                    composable("offline") { OfflineMapsScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                        TripDetailScreen(tripId = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
