package app.healthtrack.ui

import app.healthtrack.data.protocol.EcgFounderLabels
import app.healthtrack.data.protocol.NaoLabel
import app.healthtrack.domain.AnalysisStatus
import app.healthtrack.domain.EcgSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val stampFormat = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())

fun EcgSession.dayLabel(): String = dayFormat.format(Date(tsStartMs))

fun EcgSession.timeLabel(): String = timeFormat.format(Date(tsStartMs))

fun EcgSession.stampLabel(): String = stampFormat.format(Date(tsStartMs))

fun EcgSession.durationLabel(): String {
    val total = durationSec.toInt().coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

fun EcgSession.hrLabel(): String = hrMedian?.let { "${it.toInt()}" } ?: "—"

fun EcgSession.naoTitle(): String {
    val parsed = naoLabel?.let { runCatching { NaoLabel.valueOf(it) }.getOrNull() }
    return when {
        parsed != null -> when (parsed) {
            NaoLabel.N -> "Normal"
            NaoLabel.A -> "Possible AF"
            NaoLabel.O -> "Other rhythm"
        }
        analysisStatus == AnalysisStatus.PENDING -> "Analysing…"
        analysisStatus == AnalysisStatus.FAILED -> "Not analysed"
        else -> "—"
    }
}

fun EcgSession.naoConfidenceLabel(): String {
    val value = naoConfidence ?: return ""
    return "${(value * 100).toInt()}%"
}

fun EcgSession.findingRows(): List<Pair<String, String>> {
    return EcgFounderLabels.decodeFindings(findings).map { item ->
        item.name.lowercase().replaceFirstChar { it.titlecase() } to
            "${(item.score * 100).toInt()}%"
    }
}
