package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.EcgBeatDetectorConfig
import app.galaxyvitals.data.protocol.EcgBeatResult
import app.galaxyvitals.data.protocol.EcgDetectorInput
import app.galaxyvitals.data.protocol.EcgWearContract
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Live BPM publish helper.
 *
 * ECG is authoritative. A second opinion - Samsung's own per-beat `IBI_LIST`
 * where the tracker supplied one, otherwise the sparse green PPG that rides on
 * the ECG datapoint - only annotates the published estimate; it never vetoes it.
 */
object LiveBpmEstimator {
    fun estimate(
        analysisWindow: FloatArray,
        livePpg: List<LivePpgPoint>,
        signFactor: Int,
        nowMs: Long,
        srHz: Int = EcgWearContract.DEFAULT_SR_HZ,
        effectiveSrHz: Double = srHz.toDouble(),
        epoch: BpmEpoch = BpmEpoch.CAPTURE,
        samsungIbiMs: List<Int> = emptyList(),
        input: EcgDetectorInput = EcgDetectorInput.CONDITIONED,
    ): BpmAssessment {
        val ecg = EcgBeatAnalyzer.analyzeWindow(
            samplesMv = analysisWindow,
            srHz = srHz,
            signFactor = signFactor,
            config = EcgBeatDetectorConfig.DEFAULT,
            effectiveSrHz = effectiveSrHz,
            input = input,
        )
        val reference = samsungIbiBpm(samsungIbiMs) ?: estimateSparsePpgBpm(livePpg, srHz)
        return publish(ecg, reference, nowMs, epoch)
    }

    fun publish(
        ecg: EcgBeatResult,
        ppgBpm: Double?,
        nowMs: Long,
        epoch: BpmEpoch = BpmEpoch.CAPTURE,
    ): BpmAssessment {
        val bpmMedian = ecg.bpmMedian
        val rrCount = (ecg.matchedPeaks.size - 1).coerceAtLeast(0)
        val corroboration = corroborationOf(bpmMedian, ppgBpm)
        if (rrCount < MIN_RR_COUNT || bpmMedian == null) {
            return abstain(BpmAbstainReason.INSUFFICIENT_RR, ecg.bSqi, rrCount, bpmMedian, corroboration, ppgBpm)
        }
        if (bpmMedian < MIN_BPM || bpmMedian > MAX_BPM) {
            return abstain(BpmAbstainReason.OUT_OF_RANGE, ecg.bSqi, rrCount, bpmMedian, corroboration, ppgBpm)
        }
        // One bar, whether or not a second sensor happens to be reporting.
        // Requiring 0.90 without PPG and 0.80 with it made PPG availability
        // loosen the ECG bar, which is backwards.
        if (ecg.bSqi < BSQI_MIN) {
            return abstain(BpmAbstainReason.LOW_BSQI, ecg.bSqi, rrCount, bpmMedian, corroboration, ppgBpm)
        }
        val source = if (corroboration == BpmCorroboration.AGREES) {
            BpmSource.APP_ECG_RR_PPG_CORROBORATED
        } else {
            BpmSource.APP_ECG_RR
        }
        return assessed(bpmMedian, source, epoch, ecg.bSqi, rrCount, nowMs, corroboration, ppgBpm)
    }

    private fun corroborationOf(ecgBpm: Double?, referenceBpm: Double?): BpmCorroboration {
        if (ecgBpm == null || referenceBpm == null) return BpmCorroboration.UNAVAILABLE
        val allowedDiff = maxOf(PPG_ABS_BPM, ecgBpm * PPG_REL_FRACTION)
        return if (abs(referenceBpm - ecgBpm) > allowedDiff) {
            BpmCorroboration.DISAGREES
        } else {
            BpmCorroboration.AGREES
        }
    }

    /**
     * Median of Samsung's own per-beat intervals.
     *
     * `HEART_RATE_CONTINUOUS` already computes beat timings with a status flag,
     * which is a far better second opinion than re-deriving beats from two green
     * PPG points per batch. The tracker often supplies none - two of three
     * observed preflights returned `ibi_ms: []` - so the sparse-PPG estimator
     * stays as the fallback.
     */
    internal fun samsungIbiBpm(ibiMs: List<Int>): Double? {
        val valid = ibiMs.map(Int::toDouble).filter { it in MIN_RR_MS..MAX_RR_MS }
        if (valid.size < MIN_IBI_COUNT) return null
        val sorted = valid.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val bpm = 60_000.0 / median
        return if (bpm in MIN_BPM.toDouble()..MAX_BPM.toDouble()) bpm else null
    }

    private fun abstain(
        reason: BpmAbstainReason,
        bSqi: Double,
        rrCount: Int,
        rawBpm: Double?,
        corroboration: BpmCorroboration,
        corroboratingBpm: Double?,
    ) = BpmAssessment(
        estimate = null,
        reason = reason,
        bSqi = bSqi,
        rrCount = rrCount,
        rawBpm = rawBpm,
        corroboration = corroboration,
        corroboratingBpm = corroboratingBpm,
    )

    private fun assessed(
        bpm: Double,
        source: BpmSource,
        epoch: BpmEpoch,
        bSqi: Double,
        rrCount: Int,
        nowMs: Long,
        corroboration: BpmCorroboration,
        corroboratingBpm: Double?,
    ) = BpmAssessment(
        estimate = BpmEstimate(
            bpm = bpm,
            source = source,
            epoch = epoch,
            bSqi = bSqi,
            rrCount = rrCount,
            updatedAtElapsedMs = nowMs,
            corroboration = corroboration,
        ),
        bSqi = bSqi,
        rrCount = rrCount,
        rawBpm = bpm,
        corroboration = corroboration,
        corroboratingBpm = corroboratingBpm,
    )

    fun estimateSparsePpgBpm(
        livePpg: List<LivePpgPoint>,
        srHz: Int = EcgWearContract.DEFAULT_SR_HZ,
    ): Double? {
        if (srHz <= 0 || livePpg.size < MIN_PPG_POINTS) return null
        val runs = contiguousRuns(livePpg)
        val upright = rrFromRuns(runs, srHz, invert = false)
        val inverted = rrFromRuns(runs, srHz, invert = true)
        val rrMs = when {
            upright.size >= inverted.size && upright.size >= MIN_RR_COUNT -> upright
            inverted.size >= MIN_RR_COUNT -> inverted
            else -> return null
        }
        val valid = rrMs.filter { it in MIN_RR_MS..MAX_RR_MS }
        if (valid.size < MIN_RR_COUNT) return null
        val sorted = valid.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val bpm = 60_000.0 / median
        return if (bpm in MIN_BPM.toDouble()..MAX_BPM.toDouble()) bpm else null
    }

    private fun contiguousRuns(points: List<LivePpgPoint>): List<List<LivePpgPoint>> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.ecgSampleIndex }
        val runs = ArrayList<List<LivePpgPoint>>()
        var current = ArrayList<LivePpgPoint>(sorted.size)
        current += sorted[0]
        for (index in 1 until sorted.size) {
            val delta = sorted[index].ecgSampleIndex - sorted[index - 1].ecgSampleIndex
            if (delta in MIN_CADENCE_ECG..MAX_CADENCE_ECG) {
                current += sorted[index]
            } else {
                if (current.size >= MIN_RUN_POINTS) runs += current
                current = ArrayList()
                current += sorted[index]
            }
        }
        if (current.size >= MIN_RUN_POINTS) runs += current
        return runs
    }

    private fun rrFromRuns(
        runs: List<List<LivePpgPoint>>,
        srHz: Int,
        invert: Boolean,
    ): List<Double> {
        val rrMs = ArrayList<Double>()
        for (run in runs) {
            rrMs += rrFromRun(run, srHz, invert)
        }
        return rrMs
    }

    private fun rrFromRun(run: List<LivePpgPoint>, srHz: Int, invert: Boolean): List<Double> {
        if (run.size < MIN_RUN_POINTS) return emptyList()
        val deltas = DoubleArray(run.size - 1) { index ->
            (run[index + 1].ecgSampleIndex - run[index].ecgSampleIndex).toDouble()
        }
        val medianDelta = median(deltas)
        if (medianDelta < MIN_CADENCE_ECG) return emptyList()
        val fs = srHz / medianDelta
        val sign = if (invert) -1f else 1f
        val raw = FloatArray(run.size) { run[it].rawValue * sign }
        val filtered = bandpass05To5(raw, fs)
        val peaks = detectPeaks(filtered, run, fs, srHz)
        if (peaks.size < MIN_RR_COUNT + 1) return emptyList()
        val rr = ArrayList<Double>(peaks.size - 1)
        for (index in 1 until peaks.size) {
            val dtMs = (run[peaks[index]].ecgSampleIndex - run[peaks[index - 1]].ecgSampleIndex) * 1_000.0 / srHz
            if (dtMs in MIN_RR_MS..MAX_RR_MS) rr += dtMs
        }
        return rr
    }

    private fun bandpass05To5(values: FloatArray, fs: Double): FloatArray {
        val hpA = exp(-2.0 * Math.PI * HP_HZ / fs).toFloat()
        val lpA = (1.0 - exp(-2.0 * Math.PI * LP_HZ / fs)).toFloat()
        val out = FloatArray(values.size)
        var prevX = values[0]
        var prevHp = 0f
        var prevLp = 0f
        for (index in values.indices) {
            val hp = hpA * (prevHp + values[index] - prevX)
            prevX = values[index]
            prevHp = hp
            prevLp += lpA * (hp - prevLp)
            out[index] = prevLp
        }
        return out
    }

    private fun detectPeaks(
        filtered: FloatArray,
        run: List<LivePpgPoint>,
        fs: Double,
        srHz: Int,
    ): List<Int> {
        val warmup = max(1, (fs * WARMUP_SECONDS).toInt())
        if (filtered.size <= warmup + 2) return emptyList()
        var sum = 0.0
        var sumSq = 0.0
        val n = filtered.size - warmup
        for (index in warmup until filtered.size) {
            val value = filtered[index].toDouble()
            sum += value
            sumSq += value * value
        }
        val mean = sum / n
        val variance = max(0.0, sumSq / n - mean * mean)
        val std = sqrt(variance)
        if (std < MIN_PPG_STD) return emptyList()
        val threshold = (mean + THRESHOLD_K * std).toFloat()
        val minEcgInterval = (srHz * 60) / MAX_BPM
        val peaks = ArrayList<Int>()
        var index = max(warmup, 1)
        while (index < filtered.lastIndex) {
            val value = filtered[index]
            if (value >= threshold &&
                value >= filtered[index - 1] &&
                value > filtered[index + 1]
            ) {
                if (peaks.isEmpty() ||
                    run[index].ecgSampleIndex - run[peaks.last()].ecgSampleIndex >= minEcgInterval
                ) {
                    peaks += index
                } else if (value > filtered[peaks.last()]) {
                    peaks[peaks.lastIndex] = index
                }
                index += 1
                continue
            }
            index += 1
        }
        return peaks
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val copy = values.copyOf()
        copy.sort()
        val mid = copy.size / 2
        return if (copy.size % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) / 2.0
    }

    private const val MIN_PPG_POINTS = 20
    private const val MIN_RUN_POINTS = 16
    private const val MIN_CADENCE_ECG = 3L
    private const val MAX_CADENCE_ECG = 7L
    private const val MIN_RR_COUNT = 4
    private const val MIN_RR_MS = 333.0
    private const val MAX_RR_MS = 1_500.0
    private const val MIN_BPM = 40
    private const val MAX_BPM = 180
    private const val BSQI_MIN = 0.80
    private const val MIN_IBI_COUNT = 3
    private const val PPG_ABS_BPM = 5.0
    private const val PPG_REL_FRACTION = 0.08
    private const val HP_HZ = 0.5
    private const val LP_HZ = 5.0
    private const val WARMUP_SECONDS = 0.5
    private const val THRESHOLD_K = 0.40
    private const val MIN_PPG_STD = 5.0
}
