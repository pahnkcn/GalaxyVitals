package app.galaxyvitals.ui

import app.galaxyvitals.data.protocol.EcgFounderLabels
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.data.protocol.LiveBpmSummarizer
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.EcgSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun formatDate(pattern: String, epochMs: Long): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))

fun EcgSession.dayLabel(): String = formatDate("EEE d MMM", tsStartMs)

fun EcgSession.timeLabel(): String = formatDate("HH:mm", tsStartMs)

fun EcgSession.stampLabel(): String = formatDate("d MMM yyyy · HH:mm", tsStartMs)

fun EcgSession.durationLabel(): String {
    val total = if (durationSec.isFinite()) durationSec.roundToInt().coerceAtLeast(0) else 0
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun EcgSession.primaryHrMedian(): Double? =
    if (liveBpmAlgorithmId == LiveBpmSummarizer.SAMSUNG_PRIMARY_ALGORITHM_ID) {
        liveBpmMedian ?: ecgHrMedian ?: hrMedian
    } else {
        ecgHrMedian ?: hrMedian
    }

fun EcgSession.hrLabel(): String = primaryHrMedian()?.let { "${it.roundToInt()}" } ?: "—"

fun EcgSession.hrSourceLabel(): String = when {
    liveBpmAlgorithmId == LiveBpmSummarizer.SAMSUNG_PRIMARY_ALGORITHM_ID &&
        liveBpmMedian != null -> "Samsung processed heart-rate median bpm"
    ecgHrMedian != null -> "ECG-derived median bpm"
    else -> "legacy median bpm"
}

fun EcgSession.naoTitle(): String {
    if (analysisStatus == AnalysisStatus.LOW_QUALITY) return "Low quality"
    if (analysisStatus == AnalysisStatus.PENDING) return "Analysing…"
    if (analysisStatus == AnalysisStatus.FAILED) return "Not analysed"
    if (analysisStatus == AnalysisStatus.INDETERMINATE) return "Indeterminate"
    if (analysisStatus != AnalysisStatus.OK) return "—"
    val parsed = naoLabel?.let { runCatching { NaoLabel.valueOf(it) }.getOrNull() }
    return when (parsed) {
            NaoLabel.N -> "Normal"
            NaoLabel.A -> "Possible AF"
            NaoLabel.O -> "Other rhythm"
            null -> "—"
    }
}

fun EcgSession.naoConfidenceLabel(): String {
    if (analysisStatus != AnalysisStatus.OK) return ""
    val value = naoConfidence ?: return ""
    if (!value.isFinite()) return ""
    return "${(value * 100).toInt()}%"
}

fun EcgSession.findingRows(): List<Pair<String, String>> {
    if (analysisStatus != AnalysisStatus.OK) return emptyList()
    return EcgFounderLabels.decodeFindings(findings).map { item ->
        item.name.lowercase().replaceFirstChar { it.titlecase() } to
            "${(item.score * 100).toInt()}%"
    }
}
