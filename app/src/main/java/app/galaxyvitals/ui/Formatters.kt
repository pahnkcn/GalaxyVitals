package app.galaxyvitals.ui

import androidx.annotation.StringRes
import app.galaxyvitals.R
import app.galaxyvitals.data.protocol.LiveBpmSummarizer
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.EcgSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Session values in the shape a row or a card needs them.
 *
 * Anything with wording returns a string resource rather than a string, so these
 * stay pure Kotlin — testable off-device, and translated at the call site along
 * with the rest of the screen.
 */

private fun formatDate(pattern: String, epochMs: Long): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))

fun EcgSession.dayLabel(): String = formatDate("EEE d MMM", tsStartMs)

fun EcgSession.timeLabel(): String = formatDate("HH:mm", tsStartMs)

fun EcgSession.stampLabel(): String = formatDate("d MMM yyyy · HH:mm", tsStartMs)

private fun EcgSession.durationTotalSeconds(): Int =
    if (durationSec.isFinite()) durationSec.roundToInt().coerceAtLeast(0) else 0

fun EcgSession.durationMinutes(): Int = durationTotalSeconds() / 60

fun EcgSession.durationSeconds(): Int = durationTotalSeconds() % 60

private fun EcgSession.primaryHrMedian(): Double? =
    if (liveBpmAlgorithmId == LiveBpmSummarizer.SAMSUNG_PRIMARY_ALGORITHM_ID) {
        liveBpmMedian ?: ecgHrMedian ?: hrMedian
    } else {
        ecgHrMedian ?: hrMedian
    }

fun EcgSession.hrLabel(): String = primaryHrMedian()?.let { "${it.roundToInt()}" } ?: "—"

/**
 * The same rhythm vocabulary the detail screen and the exported report use.
 * A recording is described identically wherever it appears.
 */
@StringRes
fun EcgSession.naoTitleRes(): Int {
    if (analysisStatus == AnalysisStatus.LOW_QUALITY) return R.string.verdict_low_quality
    if (analysisStatus == AnalysisStatus.PENDING) return R.string.verdict_pending
    if (analysisStatus == AnalysisStatus.INDETERMINATE) return R.string.verdict_indeterminate
    if (analysisStatus != AnalysisStatus.OK) return R.string.verdict_not_analysed
    return when (naoLabel?.let { runCatching { NaoLabel.valueOf(it) }.getOrNull() }) {
        NaoLabel.N -> R.string.verdict_regular
        NaoLabel.A -> R.string.verdict_irregular
        NaoLabel.O -> R.string.verdict_inconclusive
        null -> R.string.verdict_indeterminate
    }
}

fun EcgSession.naoConfidenceLabel(): String {
    if (analysisStatus != AnalysisStatus.OK) return ""
    val value = naoConfidence ?: return ""
    if (!value.isFinite()) return ""
    return "${(value * 100).toInt()}%"
}
