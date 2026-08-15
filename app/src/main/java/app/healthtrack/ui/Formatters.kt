package app.healthtrack.ui

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
