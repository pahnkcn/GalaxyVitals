package app.galaxyvitals.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation3.SwipeDismissableSceneStrategy

sealed interface WearRoute {
    data object Home : WearRoute
    data object Measure : WearRoute
    data object History : WearRoute
    data object Settings : WearRoute
}

@Composable
fun WearRoot() {
    val backStack = remember { mutableStateListOf<WearRoute>(WearRoute.Home) }
    val homeVm: HomeViewModel = viewModel()
    // Navigation 3 entry decorators do not provide APPLICATION_KEY in their
    // CreationExtras. Resolve every AndroidViewModel from the activity-owned
    // store and pass it into the entry instead of calling viewModel() inside
    // a route, otherwise non-home screens crash as soon as they are opened.
    val measureVm: MeasureViewModel = viewModel()
    val historyVm: HistoryViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()
    val current = backStack.last()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, current) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && current is WearRoute.Measure) {
                measureVm.cancelRecording()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(current) {
        if (current is WearRoute.Home) homeVm.refresh()
    }
    AppScaffold {
        NavDisplay(
            backStack = backStack,
            onBack = {
                if (backStack.lastOrNull() is WearRoute.Measure) measureVm.cancelRecording()
                if (backStack.size > 1) backStack.removeLastOrNull()
            },
            sceneStrategy = SwipeDismissableSceneStrategy(),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = { key ->
                when (key) {
                    WearRoute.Home -> NavEntry(key) {
                        val state by homeVm.state.collectAsStateWithLifecycle()
                        HomeScreen(
                            state = state,
                            onStart = {
                                measureVm.startSamsung()
                                backStack.add(WearRoute.Measure)
                            },
                            onHistory = { backStack.add(WearRoute.History) },
                            onSettings = { backStack.add(WearRoute.Settings) },
                            onRefresh = homeVm::refresh,
                        )
                    }
                    WearRoute.Measure -> NavEntry(key) {
                        val state by measureVm.state.collectAsStateWithLifecycle()
                        MeasureScreen(
                            state = state,
                            onRetry = measureVm::retry,
                            onCancel = measureVm::cancelRecording,
                            onDone = {
                                backStack.removeLastOrNull()
                                homeVm.refresh()
                            },
                        )
                    }
                    WearRoute.History -> NavEntry(key) {
                        val sessions by historyVm.sessions.collectAsStateWithLifecycle()
                        HistoryScreen(sessions = sessions, onRefresh = historyVm::refresh)
                    }
                    WearRoute.Settings -> NavEntry(key) {
                        val wrist by settingsVm.wrist.collectAsStateWithLifecycle()
                        val note by settingsVm.sensorNote.collectAsStateWithLifecycle()
                        SettingsScreen(
                            wrist = wrist,
                            sensorNote = note,
                            onWrist = settingsVm::setWrist,
                            onProbe = settingsVm::probeSensor,
                        )
                    }
                }
            },
        )
    }
}
