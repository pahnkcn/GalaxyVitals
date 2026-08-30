package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.LiveBpmSummarizer
import app.galaxyvitals.data.protocol.ParsedEcgFile
import kotlin.math.roundToInt

/**
 * Watch history/home BPM.
 *
 * Historical Samsung-primary captures keep their processed-HR median. Current
 * captures use stored legacy HR when present, then ECG R-peak analysis.
 */
object WatchSessionBpm {
    fun displayBpm(parsed: ParsedEcgFile): Int? {
        if (parsed.liveBpmAlgorithmId == LiveBpmSummarizer.SAMSUNG_PRIMARY_ALGORITHM_ID) {
            parsed.liveBpmMedian?.roundToInt()?.let { return it }
        }
        parsed.hrMedian?.roundToInt()?.let { return it }
        if (parsed.samples.isEmpty()) return null
        return EcgBeatAnalyzer.analyze(parsed).bpmMedian?.roundToInt()
    }

    fun historyLabel(parsed: ParsedEcgFile): String =
        displayBpm(parsed)?.let { "$it bpm" } ?: "— bpm"

    fun homeLabel(parsed: ParsedEcgFile?): String {
        if (parsed == null) return "No recordings"
        return historyLabel(parsed)
    }

    fun withDisplayBpm(parsed: ParsedEcgFile): ParsedEcgFile {
        val bpm = displayBpm(parsed) ?: return parsed
        val samsungPrimary =
            parsed.liveBpmAlgorithmId == LiveBpmSummarizer.SAMSUNG_PRIMARY_ALGORITHM_ID &&
                parsed.liveBpmMedian != null
        return if (parsed.hrMedian != null && !samsungPrimary) {
            parsed
        } else {
            parsed.copy(hrMedian = bpm.toDouble())
        }
    }
}
