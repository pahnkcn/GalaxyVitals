package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgWaveformGeometry
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.data.protocol.WaveformPoint
import app.galaxyvitals.data.protocol.WaveformScale
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.wear.sensors.EcgBatch
import kotlin.math.abs

enum class BpmSource {
    ECG,
    ECG_PPG_CORROBORATED,
}

enum class SensorRun { PREFLIGHT, CAPTURE }

enum class BpmEpoch { PREFLIGHT, CAPTURE }

data class BpmEstimate(
    val bpm: Double,
    val source: BpmSource,
    val epoch: BpmEpoch,
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

    private val display = ArrayList<WaveformPoint>(DISPLAY_WINDOW_SAMPLES)
    private val analysis = ArrayList<Float>(ANALYSIS_WINDOW_SAMPLES)
    private val ppgPoints = ArrayList<LivePpgPoint>(ANALYSIS_WINDOW_SAMPLES / 5)
    private val displayFilter = CausalSosFilter(DISPLAY_SOS_500)
    private var scale = WaveformScale.Default
    var nextEcgSampleIndex: Long = 0L
        private set
    private var lastSequence = -1

    val displaySamples: List<Float> get() = display.map { it.valueMv }

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
        lastSequence = -1
        displayFilter.reset()
        scale = WaveformScale.Default
    }

    fun beginSettledWindow(signFactor: Int) {
        reset(signFactor)
    }

    fun beginCaptureWindow(signFactor: Int) {
        reset(signFactor)
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
        val sequenceBreak = lastSequence >= 0 &&
            batch.sequence != ((lastSequence + 1) and 0xff)
        val gapFlags = EcgSampleFlags.TIMESTAMP_GAP or EcgSampleFlags.SEQUENCE_GAP
        for (index in batch.samplesMv.indices) {
            val flags = batch.sampleFlags[index]
            var startsNew = display.isEmpty()
            if (index == 0 && sequenceBreak) startsNew = true
            if (flags and gapFlags != 0) startsNew = true
            if (startsNew) displayFilter.reset()
            analysis += batch.samplesMv[index]
            display += WaveformPoint(
                sampleIndex = batchStartIndex + index,
                valueMv = displayFilter.filter(batch.samplesMv[index] * signFactor),
                startsNewSegment = startsNew,
            )
        }
        lastSequence = batch.sequence
        nextEcgSampleIndex += batch.samplesMv.size
        trim()
    }

    fun waveformFrame(deltaMs: Long): LiveWaveformFrame {
        val last = (nextEcgSampleIndex - 1L).coerceAtLeast(0L)
        val first = last - (DISPLAY_WINDOW_SAMPLES - 1L)
        val points = when {
            display.isEmpty() -> emptyList()
            !display[0].startsNewSegment -> {
                val copy = ArrayList(display)
                copy[0] = copy[0].copy(startsNewSegment = true)
                copy
            }
            else -> display.toList()
        }
        scale = EcgWaveformGeometry.nextScale(points, scale, deltaMs)
        return LiveWaveformFrame(
            points = points,
            firstSampleIndex = first,
            lastSampleIndex = last,
            scale = scale,
        )
    }

    fun estimate(nowMs: Long, epoch: BpmEpoch = BpmEpoch.CAPTURE): BpmEstimate? =
        LiveBpmEstimator.estimate(
            rawWindow = analysisSamples,
            livePpg = livePpg,
            signFactor = signFactor,
            nowMs = nowMs,
            srHz = srHz,
            epoch = epoch,
        )

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

        /** SciPy `butter(4, [0.5, 40], btype="bandpass", fs=500, output="sos")` — causal only. */
        private val DISPLAY_SOS_500 = arrayOf(
            doubleArrayOf(
                0.0021387987326912015, 0.004277597465382403, 0.0021387987326912015,
                1.0, -1.22787587909828, 0.39352306024517103,
            ),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.486663673168146, 0.6949675580253452),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9882154714982394, 0.9882564156624591),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.995246749028118, 0.9952864068099667),
        )
    }
}

/** Direct-form II transposed SOS; DC-primes so a constant electrode offset does not spike. */
private class CausalSosFilter(private val sos: Array<DoubleArray>) {
    private val z0 = DoubleArray(sos.size)
    private val z1 = DoubleArray(sos.size)
    private var primed = false

    fun reset() {
        z0.fill(0.0)
        z1.fill(0.0)
        primed = false
    }

    fun filter(input: Float): Float {
        var x = input.toDouble()
        if (!primed) {
            prime(x)
            primed = true
        }
        for (sectionIndex in sos.indices) {
            val section = sos[sectionIndex]
            val a0 = if (abs(section[3]) < 1e-12) 1.0 else section[3]
            val b0 = section[0] / a0
            val b1 = section[1] / a0
            val b2 = section[2] / a0
            val a1 = section[4] / a0
            val a2 = section[5] / a0
            val y = b0 * x + z0[sectionIndex]
            z0[sectionIndex] = b1 * x - a1 * y + z1[sectionIndex]
            z1[sectionIndex] = b2 * x - a2 * y
            x = y
        }
        return x.toFloat()
    }

    private fun prime(x0: Double) {
        var x = x0
        for (sectionIndex in sos.indices) {
            val section = sos[sectionIndex]
            val a0 = if (abs(section[3]) < 1e-12) 1.0 else section[3]
            val b0 = section[0] / a0
            val b1 = section[1] / a0
            val b2 = section[2] / a0
            val a1 = section[4] / a0
            val a2 = section[5] / a0
            val denominator = 1.0 + a1 + a2
            val gain = if (abs(denominator) > 1e-12) (b0 + b1 + b2) / denominator else 0.0
            val y = gain * x
            z1[sectionIndex] = b2 * x - a2 * y
            z0[sectionIndex] = b1 * x - a1 * y + z1[sectionIndex]
            x = y
        }
    }
}
