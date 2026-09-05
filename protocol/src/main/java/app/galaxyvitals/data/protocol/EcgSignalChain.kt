package app.galaxyvitals.data.protocol

import app.galaxyvitals.data.protocol.dsp.BUTTERWORTH_ORDER_DEFAULT
import app.galaxyvitals.data.protocol.dsp.filtfilt
import app.galaxyvitals.data.protocol.dsp.lineRms
import app.galaxyvitals.data.protocol.dsp.lowPassSections
import app.galaxyvitals.data.protocol.dsp.oddKernel
import app.galaxyvitals.data.protocol.dsp.estimateLineNoise as dspEstimateLineNoise
import app.galaxyvitals.data.protocol.dsp.removeLineNoise as dspRemoveLineNoise
import app.galaxyvitals.data.protocol.dsp.runningMedian as dspRunningMedian
import app.galaxyvitals.domain.EcgSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Recording bandwidth presets.
 *
 * The AHA/ACC/HRS standardization recommendations ask for a 150 Hz passband on
 * adult diagnostic recordings; a 40 Hz cutoff is monitoring bandwidth and costs
 * roughly 15-20% of R-wave amplitude on Galaxy Watch captures, so it must never
 * be the input to an amplitude or morphology measurement.
 */
enum class EcgBandwidth(val lowPassHz: Double, val label: String) {
    /** 150 Hz passband. Use for every measurement that is reported as a number. */
    DIAGNOSTIC(150.0, "0.7-150 Hz diagnostic"),

    /** 40 Hz passband. Smoothed trace for on-screen reading only. */
    MONITOR(40.0, "0.7-40 Hz monitor"),
}

/** Powerline interference estimated from the recording itself. */
data class EcgLineNoise(
    /** Interference frequency on the corrected sample clock, in hertz. */
    val frequencyHz: Double,
    /** Peak amplitude of the fundamental, in millivolts. */
    val amplitudeMv: Double,
    /** Spectral peak height relative to the median of the 45-65 Hz scan. */
    val prominence: Double,
)

data class EcgSignalMetrics(
    /** Sample rate the chain actually used, measured from raw sensor timestamps. */
    val srHz: Double,
    /** Nominal rate declared by the file. */
    val nominalSrHz: Int,
    /** True when [srHz] came from raw timestamps rather than the declared rate. */
    val srMeasured: Boolean,
    val line: EcgLineNoise?,
    /** Line-fundamental RMS before and after cancellation, in millivolts. */
    val lineRmsBeforeMv: Double,
    val lineRmsAfterMv: Double,
    /** Peak-to-peak of the removed baseline (electrode polarization + wander). */
    val baselineExcursionMv: Double,
    /** Samples at the head of the record that failed the stationarity check. */
    val settleSampleIndex: Int,
    val bandwidth: EcgBandwidth,
) {
    val lineSuppressionDb: Double
        get() = if (lineRmsBeforeMv <= 0.0 || lineRmsAfterMv <= 0.0) {
            0.0
        } else {
            20.0 * kotlin.math.log10(lineRmsAfterMv / lineRmsBeforeMv)
        }

    val settleMs: Long get() = if (srHz <= 0.0) 0L else (settleSampleIndex * 1_000.0 / srHz).toLong()
}

data class EcgFilteredSignal(
    val valuesMv: DoubleArray,
    val metrics: EcgSignalMetrics,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EcgFilteredSignal && valuesMv.contentEquals(other.valuesMv) && metrics == other.metrics)

    override fun hashCode(): Int = 31 * valuesMv.contentHashCode() + metrics.hashCode()
}

/**
 * The one ECG filter chain. Everything that measures or draws a Galaxy Watch
 * recording goes through here so display, beat detection and morphology all see
 * the same signal.
 *
 * Stages, in order:
 *
 *  1. **Clock.** `ECG_ON_DEMAND` is documented as 500 Hz, but the recorded
 *     `DataPoint` timestamps on Galaxy Watch put the real rate near 501.67 Hz.
 *     Every interval derived on the nominal grid is then ~0.33% long. The chain
 *     regresses timestamps against sample index and uses the measured rate.
 *  2. **Powerline.** Samsung delivers raw, unfiltered ECG; a wrist capture near
 *     mains carries a fundamental comparable to the R wave and far larger than a
 *     P wave. The line frequency is estimated from the record (not assumed to be
 *     50 or 60 Hz) and removed with zero-phase notches on the fundamental and its
 *     harmonics.
 *  3. **Baseline.** A 200 ms -> 600 ms median cascade. The first stage removes
 *     QRS so the second stage sees a QRS-free trace and tracks only wander, which
 *     keeps ST and T intact. Unlike a linear high-pass this cannot ring, so the
 *     electrode-polarization step at the start of a capture is absorbed instead of
 *     being turned into a multi-second swing.
 *  4. **Low-pass.** Zero-phase Butterworth at the [EcgBandwidth] cutoff.
 *
 * Nothing here rescales `ECG_MV` or infers undocumented Samsung constants; the
 * stored raw rows are never modified.
 *
 * The arithmetic each stage is built from lives in `dsp/`: the spectral search
 * and notch ladder in `LineNoise`, the sorted-window median in `RunningMedian`,
 * and the sections and zero-phase filtering in `Biquad`. None of those knows
 * about ECG; what is decided here is which of them runs, in what order, and on
 * what clock.
 */
object EcgSignalChain {
    /** Lowest and highest plausible mains fundamental, in hertz. */
    const val LINE_SCAN_LOW_HZ = app.galaxyvitals.data.protocol.dsp.LINE_SCAN_LOW_HZ
    const val LINE_SCAN_HIGH_HZ = app.galaxyvitals.data.protocol.dsp.LINE_SCAN_HIGH_HZ

    /** Peak-to-local-floor ratio a spectral peak must beat to count as mains. */
    const val MIN_LINE_PROMINENCE = app.galaxyvitals.data.protocol.dsp.MIN_LINE_PROMINENCE

    /** -3 dB notch width is `f0 / Q`; 2.5 Hz at 50 Hz. */
    const val LINE_NOTCH_Q = app.galaxyvitals.data.protocol.dsp.LINE_NOTCH_Q

    const val BASELINE_STAGE_ONE_MS = 200
    const val BASELINE_STAGE_TWO_MS = 600

    const val BUTTERWORTH_ORDER = BUTTERWORTH_ORDER_DEFAULT

    /** Reject a measured rate this far from nominal as a broken timestamp column. */
    const val MAX_SR_DEVIATION = 0.05

    /** Block length and tolerance for the head-of-record stationarity check. */
    private const val SETTLE_BLOCK_MS = 250
    private const val SETTLE_SEARCH_SEC = 6
    private const val SETTLE_RMS_RATIO = 3.0

    // ---------------------------------------------------------------- clock

    /**
     * Measured sample rate from the raw Samsung `DataPoint` timestamps.
     *
     * A batch carries one timestamp repeated across its samples, so only the
     * first sample of each batch is a real observation. Outliers are trimmed at
     * 4 MAD before the final fit. Returns [nominalSrHz] when there are too few
     * distinct stamps or the fit lands more than [MAX_SR_DEVIATION] off nominal.
     */
    fun estimateSampleRateHz(samples: List<EcgSample>, nominalSrHz: Int): Double {
        if (nominalSrHz <= 0) return nominalSrHz.toDouble()
        val indices = ArrayList<Double>()
        val stamps = ArrayList<Double>()
        var previous = Long.MIN_VALUE
        for (sample in samples) {
            val raw = sample.sensorTimestampMsRaw ?: continue
            if (raw == previous) continue
            previous = raw
            indices += sample.sampleIndex.toDouble()
            stamps += raw.toDouble()
        }
        return fitSampleRateHz(indices, stamps, nominalSrHz)
    }

    /**
     * Same fit over parallel arrays, for callers that hold raw timestamps
     * without having built [EcgSample] rows - the CSV writer and the on-watch
     * live path. Entry `i` of [sensorTimestampsMsRaw] is the stamp of sample
     * index `i`; repeated stamps inside one batch are collapsed here so there is
     * one clock estimator in the codebase rather than two.
     */
    fun estimateSampleRateHz(sensorTimestampsMsRaw: LongArray, nominalSrHz: Int): Double {
        if (nominalSrHz <= 0) return nominalSrHz.toDouble()
        val indices = ArrayList<Double>(sensorTimestampsMsRaw.size)
        val stamps = ArrayList<Double>(sensorTimestampsMsRaw.size)
        var previous = Long.MIN_VALUE
        for (index in sensorTimestampsMsRaw.indices) {
            val raw = sensorTimestampsMsRaw[index]
            if (raw < 0L || raw == previous) continue
            previous = raw
            indices += index.toDouble()
            stamps += raw.toDouble()
        }
        return fitSampleRateHz(indices, stamps, nominalSrHz)
    }

    /**
     * Same fit from an explicit `(sample index, timestamp)` list, for the live
     * path, which sees one stamp per batch and never materialises a dense
     * per-sample array.
     */
    fun estimateSampleRateHz(
        sampleIndices: LongArray,
        timestampsMs: LongArray,
        count: Int,
        nominalSrHz: Int,
    ): Double {
        if (nominalSrHz <= 0) return nominalSrHz.toDouble()
        val size = minOf(count, sampleIndices.size, timestampsMs.size)
        if (size <= 0) return nominalSrHz.toDouble()
        val indices = ArrayList<Double>(size)
        val stamps = ArrayList<Double>(size)
        for (index in 0 until size) {
            indices += sampleIndices[index].toDouble()
            stamps += timestampsMs[index].toDouble()
        }
        return fitSampleRateHz(indices, stamps, nominalSrHz)
    }

    private fun fitSampleRateHz(
        indices: List<Double>,
        stamps: List<Double>,
        nominalSrHz: Int,
    ): Double {
        val nominal = nominalSrHz.toDouble()
        if (indices.size < MIN_CLOCK_OBSERVATIONS) return nominal
        var slope = leastSquaresSlope(indices, stamps) ?: return nominal
        val residuals = DoubleArray(indices.size)
        val intercept = meanOf(stamps) - slope * meanOf(indices)
        for (i in residuals.indices) residuals[i] = stamps[i] - (slope * indices[i] + intercept)
        val centre = EcgStats.median(residuals, whenEmpty = 0.0)
        val spread = 1.4826 * EcgStats.median(
            DoubleArray(residuals.size) { abs(residuals[it] - centre) },
            whenEmpty = 0.0,
        )
        val tolerance = 4.0 * max(spread, 0.5)
        val keptIndices = ArrayList<Double>(indices.size)
        val keptStamps = ArrayList<Double>(indices.size)
        for (i in indices.indices) {
            if (abs(residuals[i] - centre) <= tolerance) {
                keptIndices += indices[i]
                keptStamps += stamps[i]
            }
        }
        if (keptIndices.size >= MIN_CLOCK_OBSERVATIONS) {
            slope = leastSquaresSlope(keptIndices, keptStamps) ?: slope
        }
        if (slope <= 0.0 || !slope.isFinite()) return nominal
        val measured = 1_000.0 / slope
        if (!measured.isFinite() || abs(measured / nominal - 1.0) > MAX_SR_DEVIATION) return nominal
        return measured
    }

    private const val MIN_CLOCK_OBSERVATIONS = 32

    private fun leastSquaresSlope(x: List<Double>, y: List<Double>): Double? {
        val n = x.size
        if (n < 2) return null
        val meanX = meanOf(x)
        val meanY = meanOf(y)
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = x[i] - meanX
            num += dx * (y[i] - meanY)
            den += dx * dx
        }
        if (den <= 0.0) return null
        return num / den
    }
    // ------------------------------------------------------------ powerline

    /**
     * Strongest 45-65 Hz component of [values], or `null` when nothing in that
     * band stands out far enough from the local floor to be mains.
     *
     * The scan runs on the corrected clock, so a well-formed capture reports a
     * frequency close to the grid nominal. A result far from 50 or 60 Hz is
     * itself a sign the sample clock is wrong.
     */
    fun estimateLineNoise(values: DoubleArray, srHz: Double): EcgLineNoise? =
        dspEstimateLineNoise(values, srHz)

    /**
     * Zero-phase notch on [line] and every harmonic below the Nyquist margin.
     *
     * Notch width scales with harmonic order so each one covers the same
     * fractional bandwidth.
     */
    fun removeLineNoise(
        values: DoubleArray,
        srHz: Double,
        line: EcgLineNoise?,
    ): DoubleArray = dspRemoveLineNoise(values, srHz, line)

    // ------------------------------------------------------------- baseline

    /**
     * Baseline estimate: 200 ms median (removes QRS) then 600 ms median (tracks
     * wander only). Standard ST-safe baseline removal; a single mid-length
     * median instead attenuates the T wave by more than 10%.
     */
    fun baseline(values: DoubleArray, srHz: Double): DoubleArray {
        if (values.isEmpty() || srHz <= 0.0) return DoubleArray(values.size)
        val stageOne = dspRunningMedian(values, oddKernel(BASELINE_STAGE_ONE_MS, srHz))
        return dspRunningMedian(stageOne, oddKernel(BASELINE_STAGE_TWO_MS, srHz))
    }

    /** Sliding median over a reflected signal; O(n·k) moves, no per-sample sort. */
    internal fun runningMedian(values: DoubleArray, kernel: Int): DoubleArray =
        dspRunningMedian(values, kernel)

    // ------------------------------------------------------------- settling

    /**
     * First sample after which the chain output is stationary.
     *
     * The median cascade already absorbs the electrode-polarization step, so a
     * healthy capture returns `0`. A non-zero result means the head of the
     * record is still moving faster than the baseline estimator can follow and
     * must be excluded from measurement.
     */
    fun settleSampleIndex(filtered: DoubleArray, srHz: Double): Int {
        if (filtered.isEmpty() || srHz <= 0.0) return 0
        val block = max(1, (SETTLE_BLOCK_MS * srHz / 1_000.0).roundToInt())
        val blockCount = filtered.size / block
        if (blockCount < 8) return 0
        val rms = DoubleArray(blockCount)
        for (b in 0 until blockCount) {
            var acc = 0.0
            for (i in b * block until (b + 1) * block) acc += filtered[i] * filtered[i]
            rms[b] = sqrt(acc / block)
        }
        val reference = EcgStats.median(rms, whenEmpty = 0.0)
        if (reference <= 0.0) return 0
        val limit = min(blockCount, (SETTLE_SEARCH_SEC * srHz / block).roundToInt())
        var last = -1
        for (b in 0 until limit) if (rms[b] > SETTLE_RMS_RATIO * reference) last = b
        return if (last < 0) 0 else min(filtered.size, (last + 1) * block)
    }

    // --------------------------------------------------------------- public

    /** Runs stages 2-4 on an already-oriented signal at a known rate. */
    fun filter(
        values: DoubleArray,
        srHz: Double,
        bandwidth: EcgBandwidth,
        line: EcgLineNoise?,
    ): DoubleArray {
        if (values.isEmpty()) return DoubleArray(0)
        val cutoff = min(bandwidth.lowPassHz, 0.45 * srHz)
        val notched = removeLineNoise(values, srHz, line)
        val wander = baseline(notched, srHz)
        for (i in notched.indices) notched[i] -= wander[i]
        return filtfilt(lowPassSections(cutoff, srHz, BUTTERWORTH_ORDER), notched, srHz)
    }

    /**
     * Full chain over parsed samples.
     *
     * [polarity] orients the trace for the recording wrist; it is applied to the
     * working copy only and never to the stored rows.
     */
    fun process(
        samples: List<EcgSample>,
        nominalSrHz: Int,
        polarity: Float,
        bandwidth: EcgBandwidth,
    ): EcgFilteredSignal {
        require(nominalSrHz > 0) { "ECG sample rate must be positive" }
        val srHz = estimateSampleRateHz(samples, nominalSrHz)
        val oriented = DoubleArray(samples.size) { samples[it].valueMv * polarity.toDouble() }
        if (oriented.isEmpty()) {
            return EcgFilteredSignal(
                valuesMv = DoubleArray(0),
                metrics = EcgSignalMetrics(
                    srHz = srHz,
                    nominalSrHz = nominalSrHz,
                    srMeasured = srHz != nominalSrHz.toDouble(),
                    line = null,
                    lineRmsBeforeMv = 0.0,
                    lineRmsAfterMv = 0.0,
                    baselineExcursionMv = 0.0,
                    settleSampleIndex = 0,
                    bandwidth = bandwidth,
                ),
            )
        }
        val cutoff = min(bandwidth.lowPassHz, 0.45 * srHz)
        val line = estimateLineNoise(oriented, srHz)
        val before = lineRms(oriented, srHz, line)
        val notched = removeLineNoise(oriented, srHz, line)
        val after = lineRms(notched, srHz, line)
        val wander = baseline(notched, srHz)
        var wanderMin = Double.MAX_VALUE
        var wanderMax = -Double.MAX_VALUE
        for (i in notched.indices) {
            val b = wander[i]
            if (b < wanderMin) wanderMin = b
            if (b > wanderMax) wanderMax = b
            notched[i] -= b
        }
        val filtered = filtfilt(lowPassSections(cutoff, srHz, BUTTERWORTH_ORDER), notched, srHz)
        return EcgFilteredSignal(
            valuesMv = filtered,
            metrics = EcgSignalMetrics(
                srHz = srHz,
                nominalSrHz = nominalSrHz,
                srMeasured = srHz != nominalSrHz.toDouble(),
                line = line,
                lineRmsBeforeMv = before,
                lineRmsAfterMv = after,
                baselineExcursionMv = wanderMax - wanderMin,
                settleSampleIndex = settleSampleIndex(filtered, srHz),
                bandwidth = bandwidth,
            ),
        )
    }

    // ------------------------------------------------------------- helpers

    private fun meanOf(x: List<Double>): Double {
        var acc = 0.0
        for (v in x) acc += v
        return acc / x.size
    }
}
