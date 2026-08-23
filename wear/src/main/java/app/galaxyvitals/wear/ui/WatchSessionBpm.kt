package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.ParsedEcgFile

/**
 * Watch history/home BPM.
 *
 * Hardware v2 capture leaves the `hr_bpm` column empty on purpose; the stored
 * waveform is the source of truth. Prefer a legacy CSV median when present,
 * otherwise estimate from ECG R-peaks.
 */
object WatchSessionBpm {
    fun displayBpm(parsed: ParsedEcgFile): Int? {
        parsed.hrMedian?.toInt()?.let { return it }
        if (parsed.samples.isEmpty()) return null
        return LiveBpmEstimator.estimateBpm(
            samples = parsed.samples.map { it.valueMv },
            srHz = parsed.srHz,
        )
    }

    fun historyLabel(parsed: ParsedEcgFile): String =
        displayBpm(parsed)?.let { "$it bpm" } ?: "— bpm"

    fun homeLabel(parsed: ParsedEcgFile?): String {
        if (parsed == null) return "No recordings"
        return historyLabel(parsed)
    }

    fun withDisplayBpm(parsed: ParsedEcgFile): ParsedEcgFile {
        val bpm = displayBpm(parsed) ?: return parsed
        return if (parsed.hrMedian != null) parsed else parsed.copy(hrMedian = bpm.toDouble())
    }
}
