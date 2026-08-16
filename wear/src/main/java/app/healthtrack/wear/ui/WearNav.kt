package app.healthtrack.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
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
    AppScaffold {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            sceneStrategy = SwipeDismissableSceneStrategy(),
            entryProvider = { key ->
                when (key) {
                    WearRoute.Home -> NavEntry(key) {
                        val state by homeVm.state.collectAsStateWithLifecycle()
                        HomeScreen(
                            state = state,
                            onStart = { backStack.add(WearRoute.Measure) },
                            onHistory = { backStack.add(WearRoute.History) },
                            onSettings = { backStack.add(WearRoute.Settings) },
                            onRefresh = homeVm::refresh,
                        )
                    }
                    WearRoute.Measure -> NavEntry(key) {
                        val measureVm: MeasureViewModel = viewModel()
                        val state by measureVm.state.collectAsStateWithLifecycle()
                        MeasureScreen(
                            state = state,
                            onStartSamsung = measureVm::startSamsung,
                            onStartDemo = measureVm::startDemo,
                            onDone = {
                                backStack.removeLastOrNull()
                                homeVm.refresh()
                            },
                        )
                    }
                    WearRoute.History -> NavEntry(key) {
                        val historyVm: HistoryViewModel = viewModel()
                        val sessions by historyVm.sessions.collectAsStateWithLifecycle()
                        HistoryScreen(sessions = sessions, onRefresh = historyVm::refresh)
                    }
                    WearRoute.Settings -> NavEntry(key) {
                        val settingsVm: SettingsViewModel = viewModel()
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
