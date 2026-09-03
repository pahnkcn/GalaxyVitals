package app.galaxyvitals.ui

import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.galaxyvitals.GalaxyVitalsApp
import app.galaxyvitals.R
import app.galaxyvitals.ui.bp.BloodPressureScreen
import app.galaxyvitals.ui.detail.EcgDetailScreen
import app.galaxyvitals.ui.history.HistoryScreen
import app.galaxyvitals.ui.home.HomeScreen
import app.galaxyvitals.ui.settings.SettingsScreen

internal fun ownsPhoneTopBar(route: Route): Boolean =
    route is Route.EcgDetail || route is Route.BloodPressure

sealed interface Route {
    data object Home : Route
    data object History : Route
    data object Settings : Route
    data class EcgDetail(val sessionId: String) : Route
    data object BloodPressure : Route
}

private data class Tab(val route: Route, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, R.string.tab_home, Icons.Outlined.Home),
    Tab(Route.History, R.string.tab_history, Icons.Outlined.Timeline),
    Tab(Route.Settings, R.string.tab_settings, Icons.Outlined.Settings),
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
    val ownsTopBar = ownsPhoneTopBar(current)
    val home by viewModel.home.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val calibration = remember(context) {
        (context.applicationContext as GalaxyVitalsApp).container.ecgScaleCalibration
    }
    val exportFailed = stringResource(R.string.export_failed)

    // The chooser is fired from the screen that asked for it, and the intent is
    // consumed straight away so a rotation cannot re-open the share sheet.
    LaunchedEffect(export.share) {
        val intent = export.share ?: return@LaunchedEffect
        context.startActivity(intent)
        viewModel.consumeShare()
    }
    LaunchedEffect(export.failed) {
        if (!export.failed) return@LaunchedEffect
        snackbar.showSnackbar(exportFailed)
        viewModel.consumeExportFailure()
    }

    LaunchedEffect(home.message) {
        val msg = home.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = if (ownsTopBar) {
            ScaffoldDefaults.contentWindowInsets.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            )
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
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
                            icon = {
                                Icon(tab.icon, contentDescription = stringResource(tab.label))
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
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
                            onSync = viewModel::requestSync,
                            onOpenBp = { backStack.add(Route.BloodPressure) },
                        )
                    }
                    Route.History -> NavEntry(key) {
                        HistoryScreen(
                            sessions = sessions,
                            onOpen = { id -> backStack.add(Route.EcgDetail(id)) },
                            watchLinked = home.wear.available,
                            onClearWatchHistory = viewModel::clearWatchHistory,
                        )
                    }
                    Route.Settings -> NavEntry(key) {
                        LaunchedEffect(Unit) { viewModel.refreshWear() }
                        SettingsScreen(wear = home.wear, calibration = calibration)
                    }
                    is Route.EcgDetail -> NavEntry(key) {
                        val mine = detail.sessionId == key.sessionId
                        EcgDetailScreen(
                            report = if (mine) detail.report else null,
                            loading = !mine || detail.loading,
                            sessionId = key.sessionId,
                            bandwidth = detail.bandwidth,
                            calibration = calibration,
                            exporting = export.running,
                            onLoad = viewModel::loadSamples,
                            onBandwidth = viewModel::setBandwidth,
                            onExport = { format, note, text, chooserTitle ->
                                viewModel.exportSession(
                                    sessionId = key.sessionId,
                                    format = format,
                                    note = note,
                                    text = text,
                                    chooserTitle = chooserTitle,
                                )
                            },
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
