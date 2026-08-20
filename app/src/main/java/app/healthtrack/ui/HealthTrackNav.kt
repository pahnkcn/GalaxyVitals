package app.healthtrack.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.healthtrack.domain.EcgSession
import app.healthtrack.ui.bp.BloodPressureScreen
import app.healthtrack.ui.detail.EcgDetailScreen
import app.healthtrack.ui.history.HistoryScreen
import app.healthtrack.ui.home.HomeScreen
import app.healthtrack.ui.settings.SettingsScreen

sealed interface Route {
    data object Home : Route
    data object History : Route
    data object Settings : Route
    data class EcgDetail(val sessionId: String) : Route
    data object BloodPressure : Route
}

private data class Tab(val route: Route, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Home", Icons.Outlined.Home),
    Tab(Route.History, "History", Icons.Outlined.Timeline),
    Tab(Route.Settings, "Settings", Icons.Outlined.Settings),
)

@Composable
fun HealthTrackRoot(
    viewModel: HealthTrackViewModel,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = remember { mutableStateListOf<Route>(Route.Home) }
    val current = backStack.last()
    val showBar = current is Route.Home || current is Route.History || current is Route.Settings
    val home by viewModel.home.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val detailSamples by viewModel.detailSamples.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(home.message) {
        val msg = home.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                backStack.clear()
                                backStack.add(Route.Home)
                                if (tab.route !is Route.Home) backStack.add(tab.route)
                                if (tab.route is Route.Home) viewModel.refreshWear()
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.padding(padding),
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryProvider = { key ->
                when (key) {
                    Route.Home -> NavEntry(key) {
                        HomeScreen(
                            state = home.copy(
                                latest = sessions.firstOrNull(),
                                count = sessions.size,
                            ),
                            onOpenEcg = { id -> backStack.add(Route.EcgDetail(id)) },
                            onOpenHistory = {
                                backStack.clear()
                                backStack.add(Route.Home)
                                backStack.add(Route.History)
                            },
                            onImport = onImport,
                            onLoadDemo = viewModel::loadDemo,
                            onSync = viewModel::requestSync,
                            onOpenBp = { backStack.add(Route.BloodPressure) },
                        )
                    }
                    Route.History -> NavEntry(key) {
                        HistoryScreen(
                            sessions = sessions,
                            onOpen = { id -> backStack.add(Route.EcgDetail(id)) },
                        )
                    }
                    Route.Settings -> NavEntry(key) {
                        LaunchedEffect(Unit) { viewModel.refreshWear() }
                        SettingsScreen(wear = home.wear)
                    }
                    is Route.EcgDetail -> NavEntry(key) {
                        val session: EcgSession? = sessions.firstOrNull { it.sessionId == key.sessionId }
                        EcgDetailScreen(
                            session = session,
                            samples = if (detailSamples.sessionId == key.sessionId) {
                                detailSamples.samples
                            } else {
                                emptyList()
                            },
                            onLoad = viewModel::loadSamples,
                            onBack = { backStack.removeLastOrNull() },
                            onDelete = viewModel::delete,
                        )
                    }
                    Route.BloodPressure -> NavEntry(key) {
                        BloodPressureScreen(onBack = { backStack.removeLastOrNull() })
                    }
                }
            },
        )
    }
}
