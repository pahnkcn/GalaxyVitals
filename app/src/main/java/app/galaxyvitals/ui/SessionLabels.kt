package app.galaxyvitals.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.galaxyvitals.R
import app.galaxyvitals.domain.EcgSession

/**
 * Session labels that need wording, and therefore a composition to resolve it.
 * The arithmetic behind them stays in [Formatters.kt], where it can be tested.
 */
@Composable
fun EcgSession.durationLabel(): String {
    val minutes = durationMinutes()
    val seconds = durationSeconds()
    return if (minutes > 0) {
        stringResource(R.string.duration_minutes_seconds, minutes, seconds)
    } else {
        stringResource(R.string.duration_seconds, seconds)
    }
}
