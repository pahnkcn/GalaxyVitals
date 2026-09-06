package app.galaxyvitals.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
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
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.Spacing
import app.galaxyvitals.ui.theme.labelStyle
import app.galaxyvitals.ui.theme.mm

internal fun ownsPhoneTopBar(route: Route): Boolean =
    route is Route.EcgDetail || route is Route.BloodPressure

sealed interface Route {
    data object Home : Route
    data object History : Route
    data object Settings : Route
    data class EcgDetail(val sessionId: String) : Route
    data object BloodPressure : Route
}

private data class Tab(val route: Route, val label: Int)

private val tabs = listOf(
    Tab(Route.Home, R.string.tab_home),
    Tab(Route.History, R.string.tab_history),
    Tab(Route.Settings, R.string.tab_settings),
)

/**
 * Three words and a tick.
 *
 * The bar carries no icons: with three destinations named by nouns a person
 * already uses, a glyph beside each word would be a second, weaker label. The
 * mark for the current tab is a rule sitting on the layout grid, in the same
 * neutral as the rest of the chrome, because a coloured indicator here would
 * be colour that does not mean a verdict.
 */
@Composable
private fun TabBar(current: Route, onSelect: (Route) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            tabs.forEach { tab ->
                val selected = current == tab.route
                val label = stringResource(tab.label)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(tab.route) },
                        )
                        .padding(bottom = Spacing.item),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 3f.mm, height = 0.35f.mm)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.onBackground
                                } else {
                                    MaterialTheme.colorScheme.background
                                },
                            ),
                    )
                    Spacer(Modifier.height(Spacing.item))
                    Text(
                        text = label.uppercase(),
                        style = labelStyle(),
                        textAlign = TextAlign.Center,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

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
    val previews by viewModel.previews.collectAsStateWithLifecycle()
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
                TabBar(current) { route ->
                    backStack.clear()
                    backStack.add(Route.Home)
                    if (route !is Route.Home) backStack.add(route)
                    if (route is Route.Home) viewModel.refreshWear()
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
                            state = home,
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
                            previews = previews,
                            onRequestPreview = viewModel::requestPreview,
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
