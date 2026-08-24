package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.wear.sensors.EcgBatch

enum class BpmSource {
    ECG,
    ECG_PPG_CORROBORATED,
}

data class BpmEstimate(
    val bpm: Double,
    val source: BpmSource,
    val bSqi: Double,
    val rrCount: Int,
    val updatedAtElapsedMs: Long,
)

enum class LiveBpmAvailability {
    COLLECTING,
    RELIABLE,
    UNRELIABLE,
}

data class LiveBpmState(
    val availability: LiveBpmAvailability,
    val estimate: BpmEstimate? = null,
    val reason: String? = null,
)

data class LivePpgPoint(
    val ecgSampleIndex: Long,
    val rawValue: Int,
)

/** Sliding live ECG/PPG windows: 3 s filtered display, 10 s raw analysis, sparse PPG. */
class LiveEcgProcessor(
    signFactor: Int = 1,
    private val srHz: Int = EcgWearContract.DEFAULT_SR_HZ,
) {
    var signFactor: Int = signFactor
        private set

    private val display = ArrayList<Float>(DISPLAY_WINDOW_SAMPLES)
    private val analysis = ArrayList<Float>(ANALYSIS_WINDOW_SAMPLES)
    private val ppgPoints = ArrayList<LivePpgPoint>(ANALYSIS_WINDOW_SAMPLES / 5)
    var nextEcgSampleIndex: Long = 0L
        private set
    private var liveHpPrev = Float.NaN
    private var liveHpState = 0f
    private var liveLpState = 0f

    val displaySamples: List<Float> get() = display.toList()

    val analysisSamples: FloatArray
        get() {
            val out = FloatArray(analysis.size)
            for (index in analysis.indices) out[index] = analysis[index]
            return out
        }

    val livePpg: List<LivePpgPoint> get() = ppgPoints.toList()
    val analysisSampleCount: Int get() = analysis.size

    fun reset(signFactor: Int = this.signFactor) {
        this.signFactor = signFactor
        display.clear()
        analysis.clear()
        ppgPoints.clear()
        nextEcgSampleIndex = 0L
        liveHpPrev = Float.NaN
        liveHpState = 0f
        liveLpState = 0f
    }

    fun append(batch: EcgBatch) {
        val batchStartIndex = nextEcgSampleIndex
        batch.ppgGreen?.let { ppg ->
            for (i in ppg.values.indices) {
                ppgPoints += LivePpgPoint(
                    ecgSampleIndex = batchStartIndex + ppg.ecgSampleOffsets[i],
                    rawValue = ppg.values[i],
                )
            }
        }
        for (value in batch.samplesMv) {
            analysis += value
            display += filterDisplay(value * signFactor)
        }
        nextEcgSampleIndex += batch.samplesMv.size
        trim()
    }

    fun estimate(nowMs: Long): BpmEstimate? = LiveBpmEstimator.estimate(
        rawWindow = analysisSamples,
        livePpg = livePpg,
        signFactor = signFactor,
        nowMs = nowMs,
        srHz = srHz,
    )

    private fun filterDisplay(oriented: Float): Float {
        if (liveHpPrev.isNaN()) {
            liveHpPrev = oriented
            liveHpState = 0f
            liveLpState = 0f
            return 0f
        }
        val highPass = LIVE_HP_ALPHA * (liveHpState + oriented - liveHpPrev)
        liveHpState = highPass
        liveHpPrev = oriented
        liveLpState += LIVE_LP_ALPHA * (highPass - liveLpState)
        return liveLpState
    }

    private fun trim() {
        val extraDisplay = display.size - DISPLAY_WINDOW_SAMPLES
        if (extraDisplay > 0) display.subList(0, extraDisplay).clear()
        val extraAnalysis = analysis.size - ANALYSIS_WINDOW_SAMPLES
        if (extraAnalysis > 0) analysis.subList(0, extraAnalysis).clear()
        val minIndex = nextEcgSampleIndex - analysis.size
        if (ppgPoints.isNotEmpty()) {
            ppgPoints.removeAll { point ->
                point.ecgSampleIndex < minIndex || point.ecgSampleIndex >= nextEcgSampleIndex
            }
        }
    }

    companion object {
        const val DISPLAY_WINDOW_SAMPLES = 1_500
        const val ANALYSIS_WINDOW_SAMPLES = 5_000
        private const val LIVE_HP_ALPHA = 0.9937605f
        private const val LIVE_LP_ALPHA = 0.3344278f
    }
}
