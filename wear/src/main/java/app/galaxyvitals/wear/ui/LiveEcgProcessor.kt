package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.CausalSosFilter
import app.galaxyvitals.data.protocol.DelayedMedianBaseline
import app.galaxyvitals.data.protocol.EcgCausalConditioning
import app.galaxyvitals.data.protocol.EcgLineNoise
import app.galaxyvitals.data.protocol.EcgSignalChain
import app.galaxyvitals.data.protocol.EcgWaveformGeometry
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.data.protocol.WaveformPoint
import app.galaxyvitals.data.protocol.WaveformScale
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.wear.sensors.EcgBatch

enum class BpmSource {
    SAMSUNG_PROCESSED_HR,
    APP_ECG_RR,
    APP_ECG_RR_PPG_CORROBORATED,
}

enum class SensorRun { PREFLIGHT, CAPTURE }

enum class BpmEpoch { PREFLIGHT, CAPTURE }

enum class BpmAbstainReason {
    INSUFFICIENT_RR,
    LOW_BSQI,
    OUT_OF_RANGE,
}

/**
 * What a second sensor had to say about the ECG estimate.
 *
 * Annotation only. ECG R-peak timing is the better measurement by a wide margin,
 * so a wrist PPG that disagrees is a reason to record lower confidence, never a
 * reason to publish nothing: a Samsung preflight PPG reading 83 bpm against 62
 * bpm from ECG used to suppress an entire capture.
 */
enum class BpmCorroboration {
    UNAVAILABLE,
    AGREES,
    DISAGREES,
}

data class BpmEstimate(
    val bpm: Double,
    val source: BpmSource,
    val epoch: BpmEpoch,
    val bSqi: Double? = null,
    val rrCount: Int? = null,
    val updatedAtElapsedMs: Long,
    val corroboration: BpmCorroboration = BpmCorroboration.UNAVAILABLE,
)

data class BpmAssessment(
    val estimate: BpmEstimate?,
    val reason: BpmAbstainReason? = null,
    val bSqi: Double = 0.0,
    val rrCount: Int = 0,
    val rawBpm: Double? = null,
    val corroboration: BpmCorroboration = BpmCorroboration.UNAVAILABLE,
    /** BPM the corroborating sensor reported, when there was one. */
    val corroboratingBpm: Double? = null,
)

enum class LiveBpmAvailability {
    COLLECTING,
    RELIABLE,
    TRANSITIONING,
    UNRELIABLE,
}

data class LiveBpmState(
    val availability: LiveBpmAvailability,
    val estimate: BpmEstimate? = null,
    val reason: String? = null,
    val estimateAgeMs: Long = 0L,
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
    private val conditioned = ArrayList<Float>(ANALYSIS_WINDOW_SAMPLES)
    private val ppgPoints = ArrayList<LivePpgPoint>(ANALYSIS_WINDOW_SAMPLES / 5)
    private val displayBaseline = DelayedMedianBaseline(srHz.toDouble())
    private val displayLowPass = EcgCausalConditioning.lowPass(srHz)
    private val analysisFilter = EcgCausalConditioning.bandpass(srHz)
    private var lineNotch: CausalSosFilter? = null
    private var analysisNotch: CausalSosFilter? = null
    private var lineEstimateAttempted = false
    private var scale = WaveformScale.Default

    // Display conditioning lags the stream by the median cascade's lookahead and
    // holds back the head of every segment, so the newest drawable sample is not
    // the newest one received.
    private var streamOpen = false
    private var pendingSegmentStart = true
    private var displayWarmupLeft = DISPLAY_WARMUP_SAMPLES
    private var lastEmittedIndex = -1L

    // One timestamp per batch is a real clock observation; the rest of the batch
    // repeats it. Kept across a capture window so the measured rate sharpens
    // instead of restarting at the declared 500 Hz.
    private val clockSampleIndices = LongArray(MAX_CLOCK_OBSERVATIONS)
    private val clockTimestampsMs = LongArray(MAX_CLOCK_OBSERVATIONS)
    private var clockObservationCount = 0
    private var lastClockTimestampMs = -1L
    private var cachedEffectiveSrHz = srHz.toDouble()
    private var cachedEffectiveSrAtCount = -1

    /** Powerline interference measured on this run, once enough samples exist. */
    var lineNoise: EcgLineNoise? = null
        private set
    var nextEcgSampleIndex: Long = 0L
        private set
    private var lastSequence = -1

    val displaySamples: List<Float> get() = display.map { it.valueMv }

    /** Copies of the detector's analysis window handed out, for cadence tests. */
    internal var analysisWindowCopyCount: Int = 0
        private set

    val analysisSamples: FloatArray
        get() {
            val out = FloatArray(analysis.size)
            for (index in analysis.indices) out[index] = analysis[index]
            return out
        }

    val livePpg: List<LivePpgPoint> get() = ppgPoints.toList()
    val analysisSampleCount: Int get() = analysis.size

    /**
     * The analysis window after causal 0.5-40 Hz conditioning - the trace the
     * beat detector reads. Wrist baseline wander is larger than the QRS riding
     * on it, so a detector fed [analysisSamples] measures the wander instead of
     * the beat; this is the same passband the offline path applies zero-phase.
     */
    val conditionedSamples: FloatArray
        get() {
            analysisWindowCopyCount++
            val out = FloatArray(conditioned.size)
            for (index in conditioned.indices) out[index] = conditioned[index]
            return out
        }

    /**
     * Sample rate measured from the Samsung `DataPoint` timestamps, or the
     * declared rate until enough batches have arrived to fit one. Galaxy Watch
     * runs near 501.67 Hz, which biases every live RR interval by 0.33% when
     * the declared 500 is used instead.
     */
    val effectiveSrHz: Double
        get() {
            if (cachedEffectiveSrAtCount != clockObservationCount) {
                cachedEffectiveSrHz = EcgSignalChain.estimateSampleRateHz(
                    clockSampleIndices,
                    clockTimestampsMs,
                    clockObservationCount,
                    srHz,
                )
                cachedEffectiveSrAtCount = clockObservationCount
            }
            return cachedEffectiveSrHz
        }

    fun reset(signFactor: Int = this.signFactor) {
        this.signFactor = signFactor
        display.clear()
        analysis.clear()
        conditioned.clear()
        ppgPoints.clear()
        nextEcgSampleIndex = 0L
        lastSequence = -1
        analysisWindowCopyCount = 0
        displayBaseline.reset()
        displayLowPass.reset()
        analysisFilter.reset()
        lineNotch = null
        analysisNotch = null
        lineNoise = null
        lineEstimateAttempted = false
        restartDisplayWindow()
        resetClock()
    }

    /**
     * Drop everything the display path knows about where it is in the stream.
     *
     * The filters themselves are re-armed by the discontinuity branch of [append],
     * which [streamOpen] forces on the next sample.
     */
    private fun restartDisplayWindow() {
        streamOpen = false
        pendingSegmentStart = true
        displayWarmupLeft = DISPLAY_WARMUP_SAMPLES
        lastEmittedIndex = -1L
        scale = WaveformScale.Default
    }

    private fun resetClock() {
        clockObservationCount = 0
        lastClockTimestampMs = -1L
        cachedEffectiveSrHz = srHz.toDouble()
        cachedEffectiveSrAtCount = -1
    }

    fun beginSettledWindow(signFactor: Int) {
        reset(signFactor)
    }

    /**
     * Open the recording window on the sensor session that has just restarted.
     *
     * Samsung caps a listener at 30 s, so a capture always runs on a session the
     * probe did not: its AFE re-polarises from its own offset and the display
     * conditioning has nothing valid to carry across - warm-starting it there put
     * a step the size of the whole electrode offset through the filters. The
     * measured sample clock and the mains estimate do survive, because neither
     * restarts with the listener.
     */
    fun beginCaptureWindow(signFactor: Int) {
        if (signFactor != this.signFactor) {
            reset(signFactor)
            return
        }
        display.clear()
        analysis.clear()
        conditioned.clear()
        ppgPoints.clear()
        // The sensor clock does not restart with the window, and the probe has
        // already collected observations worth keeping.
        rebaseClock(nextEcgSampleIndex)
        nextEcgSampleIndex = 0L
        lastSequence = -1
        analysisWindowCopyCount = 0
        restartDisplayWindow()
    }

    /** Re-express the retained clock observations against the new sample origin. */
    private fun rebaseClock(origin: Long) {
        for (index in 0 until clockObservationCount) {
            clockSampleIndices[index] -= origin
        }
        cachedEffectiveSrAtCount = -1
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
            var discontinuity = !streamOpen
            if (index == 0 && sequenceBreak) discontinuity = true
            if (flags and gapFlags != 0) discontinuity = true
            if (discontinuity) {
                displayBaseline.reset()
                displayLowPass.reset()
                analysisFilter.reset()
                lineNotch?.reset()
                analysisNotch?.reset()
                displayWarmupLeft = DISPLAY_WARMUP_SAMPLES
                pendingSegmentStart = true
            }
            streamOpen = true
            val raw = batch.samplesMv[index]
            analysis += raw
            // Conditioned on the unoriented sample: the chain is linear, so the
            // caller's sign factor still means what it means downstream.
            conditioned += analysisFilter.filter(analysisNotch?.filter(raw) ?: raw)
            val oriented = raw * signFactor
            val notched = lineNotch?.filter(oriented) ?: oriented
            emitDisplaySample(displayBaseline.push(notched), batchStartIndex + index)
            recordClockObservation(batchStartIndex + index, batch.sensorTimestampsMs.getOrNull(index))
        }
        lastSequence = batch.sequence
        nextEcgSampleIndex += batch.samplesMv.size
        trim()
        configureLineNotch()
    }

    /**
     * Low-pass [detrended] and add it to the display window.
     *
     * [detrended] belongs to the sample [DelayedMedianBaseline.lookaheadSamples]
     * before [pushedIndex], and is null until the cascade has seen enough of the
     * stream to centre a window. The first [DISPLAY_WARMUP_SAMPLES] of a segment
     * are still filtered - the low-pass has to see them - but not drawn: they are
     * the tail of the electrode ramp, and at 20x the size of a QRS they would set
     * the autoscale for the next fifteen seconds.
     */
    private fun emitDisplaySample(detrended: Float?, pushedIndex: Long) {
        if (detrended == null) return
        val filtered = displayLowPass.filter(detrended)
        if (displayWarmupLeft > 0) {
            displayWarmupLeft--
            return
        }
        val sampleIndex = pushedIndex - displayBaseline.lookaheadSamples
        display += WaveformPoint(
            sampleIndex = sampleIndex,
            valueMv = filtered,
            startsNewSegment = pendingSegmentStart,
        )
        pendingSegmentStart = false
        lastEmittedIndex = sampleIndex
    }

    /**
     * Samsung streams raw ECG with no anti-mains filter, and a 40 Hz low-pass
     * alone leaves the fundamental about the size of a P wave. The grid frequency
     * is measured once, from the first [LINE_ESTIMATE_SAMPLES] samples of the
     * pre-capture probe, and the resulting notch is carried into the recording so
     * the live trace never restarts unfiltered.
     */
    private fun configureLineNotch() {
        if (lineEstimateAttempted || analysis.size < LINE_ESTIMATE_SAMPLES) return
        lineEstimateAttempted = true
        val window = DoubleArray(LINE_ESTIMATE_SAMPLES) { index ->
            analysis[analysis.size - LINE_ESTIMATE_SAMPLES + index].toDouble()
        }
        val line = EcgSignalChain.estimateLineNoise(window, srHz.toDouble()) ?: return
        lineNoise = line
        val sections = EcgCausalConditioning.notchSos(line.frequencyHz, srHz.toDouble())
        if (sections.isEmpty()) return
        lineNotch = CausalSosFilter(sections)
        analysisNotch = CausalSosFilter(sections)
    }

    /**
     * One distinct `DataPoint` timestamp per batch is a real observation of the
     * sensor clock; the other nine samples repeat it and carry no information.
     */
    private fun recordClockObservation(sampleIndex: Long, timestampMs: Long?) {
        if (timestampMs == null || timestampMs < 0L || timestampMs == lastClockTimestampMs) return
        lastClockTimestampMs = timestampMs
        if (clockObservationCount >= MAX_CLOCK_OBSERVATIONS) {
            // Drop the oldest half rather than stop observing: the fit stays
            // anchored to the most recent, longest-span stretch of the capture.
            val keep = MAX_CLOCK_OBSERVATIONS / 2
            val from = clockObservationCount - keep
            System.arraycopy(clockSampleIndices, from, clockSampleIndices, 0, keep)
            System.arraycopy(clockTimestampsMs, from, clockTimestampsMs, 0, keep)
            clockObservationCount = keep
        }
        clockSampleIndices[clockObservationCount] = sampleIndex
        clockTimestampsMs[clockObservationCount] = timestampMs
        clockObservationCount++
        cachedEffectiveSrAtCount = -1
    }

    fun waveformFrame(deltaMs: Long): LiveWaveformFrame {
        // Anchored on the newest drawable sample, not the newest received one:
        // display conditioning runs a fixed lookahead behind the stream, and
        // anchoring ahead of it would park a permanent gap at the right edge.
        val last = lastEmittedIndex.coerceAtLeast(0L)
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

    fun estimate(nowMs: Long, epoch: BpmEpoch = BpmEpoch.CAPTURE): BpmAssessment =
        LiveBpmEstimator.estimate(
            analysisWindow = conditionedSamples,
            livePpg = livePpg,
            signFactor = signFactor,
            nowMs = nowMs,
            srHz = srHz,
            effectiveSrHz = effectiveSrHz,
            epoch = epoch,
        )

    private fun trim() {
        val extraDisplay = display.size - DISPLAY_WINDOW_SAMPLES
        if (extraDisplay > 0) display.subList(0, extraDisplay).clear()
        val extraAnalysis = analysis.size - ANALYSIS_WINDOW_SAMPLES
        if (extraAnalysis > 0) analysis.subList(0, extraAnalysis).clear()
        val extraConditioned = conditioned.size - ANALYSIS_WINDOW_SAMPLES
        if (extraConditioned > 0) conditioned.subList(0, extraConditioned).clear()
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

        /** 3 s is enough to place a Q=20 notch well inside its own bandwidth. */
        const val LINE_ESTIMATE_SAMPLES = 1_500

        /**
         * Held back at the head of every display segment.
         *
         * The median cascade absorbs the electrode-polarisation step but cannot
         * centre a window on samples that have nothing before them, so the first
         * conditioned samples still carry part of the ramp.
         */
        const val DISPLAY_WARMUP_SAMPLES = 100

        /** [DelayedMedianBaseline.lookaheadSamples] at the declared 500 Hz. */
        const val DISPLAY_BASELINE_LOOKAHEAD_SAMPLES = 200

        /** One clock observation per batch; 60 s of 10-sample batches at 500 Hz. */
        internal const val MAX_CLOCK_OBSERVATIONS = 3_000
    }
}

