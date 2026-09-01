package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
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
 */
object EcgSignalChain {
    /** Lowest and highest plausible mains fundamental, in hertz. */
    const val LINE_SCAN_LOW_HZ = 45.0
    const val LINE_SCAN_HIGH_HZ = 65.0

    /** Scan margin so the accepted band never sits on the edge of the sweep. */
    private const val LINE_SCAN_MARGIN_HZ = 6.0

    /** Half-width excluded from the local floor, and the floor's outer edge. */
    private const val LINE_FLOOR_INNER_HZ = 1.5
    private const val LINE_FLOOR_OUTER_HZ = 6.0

    /** Grid nominals, and how far a peak may sit from one and still be mains. */
    private val GRID_NOMINALS_HZ = doubleArrayOf(50.0, 60.0)
    private const val GRID_TOLERANCE_HZ = 1.5

    /** Coarse scan step; finer than the 30 s Hann main lobe so a peak cannot hide. */
    private const val LINE_SCAN_STEP_HZ = 0.05
    private const val LINE_REFINE_TOLERANCE_HZ = 1e-4

    /** Peak-to-local-floor ratio a spectral peak must beat to count as mains. */
    const val MIN_LINE_PROMINENCE = 6.0

    /** -3 dB notch width is `f0 / Q`; 2.5 Hz at 50 Hz. */
    const val LINE_NOTCH_Q = 20.0

    const val BASELINE_STAGE_ONE_MS = 200
    const val BASELINE_STAGE_TWO_MS = 600

    const val BUTTERWORTH_ORDER = 4

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
        val centre = medianOf(residuals.copyOf())
        val spread = 1.4826 * medianOf(DoubleArray(residuals.size) { abs(residuals[it] - centre) })
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
    fun estimateLineNoise(values: DoubleArray, srHz: Double): EcgLineNoise? {
        val n = values.size
        if (n < 16 || srHz <= 2 * LINE_SCAN_HIGH_HZ) return null
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1).toDouble()) }
        var windowSum = 0.0
        for (w in window) windowSum += w
        if (windowSum <= 0.0) return null
        val mean = values.average()
        val windowed = DoubleArray(n) { (values[it] - mean) * window[it] }

        // Sweep wider than the accepted band so a peak can be compared against a
        // two-sided local floor. A plain band-wide median mistakes the natural
        // downward slope of the ECG spectrum for a peak at the low edge.
        val scanLow = LINE_SCAN_LOW_HZ - LINE_SCAN_MARGIN_HZ
        val scanHigh = LINE_SCAN_HIGH_HZ + LINE_SCAN_MARGIN_HZ
        if (srHz <= 2 * scanHigh) return null
        val steps = ((scanHigh - scanLow) / LINE_SCAN_STEP_HZ).roundToInt()
        val magnitudes = DoubleArray(steps + 1)
        var bestStep = -1
        for (step in 0..steps) {
            val frequency = scanLow + step * LINE_SCAN_STEP_HZ
            magnitudes[step] = goertzelMagnitude(windowed, srHz, frequency)
            if (frequency < LINE_SCAN_LOW_HZ || frequency > LINE_SCAN_HIGH_HZ) continue
            if (bestStep < 0 || magnitudes[step] > magnitudes[bestStep]) bestStep = step
        }
        if (bestStep < 0) return null
        val innerSteps = (LINE_FLOOR_INNER_HZ / LINE_SCAN_STEP_HZ).roundToInt()
        val outerSteps = (LINE_FLOOR_OUTER_HZ / LINE_SCAN_STEP_HZ).roundToInt()
        val neighbourhood = ArrayList<Double>(2 * (outerSteps - innerSteps + 1))
        for (step in 0..steps) {
            val distance = abs(step - bestStep)
            if (distance in innerSteps..outerSteps) neighbourhood += magnitudes[step]
        }
        if (neighbourhood.isEmpty()) return null
        val floor = medianOf(neighbourhood.toDoubleArray())
        if (floor <= 0.0) return null
        val prominence = magnitudes[bestStep] / floor
        if (prominence < MIN_LINE_PROMINENCE) return null

        val coarse = scanLow + bestStep * LINE_SCAN_STEP_HZ
        // Every grid runs at 50 or 60 Hz within a fraction of a hertz. A peak
        // further out than that is a harmonic of a very regular heart rate, not
        // interference, and notching it would remove real signal.
        if (GRID_NOMINALS_HZ.none { abs(coarse - it) <= GRID_TOLERANCE_HZ }) return null
        val refined = refinePeak(
            windowed,
            srHz,
            max(LINE_SCAN_LOW_HZ, coarse - LINE_SCAN_STEP_HZ),
            min(LINE_SCAN_HIGH_HZ, coarse + LINE_SCAN_STEP_HZ),
        )
        val amplitude = 2.0 * goertzelMagnitude(windowed, srHz, refined) / windowSum
        return EcgLineNoise(frequencyHz = refined, amplitudeMv = amplitude, prominence = prominence)
    }

    /** Golden-section search for the magnitude maximum inside `[low, high]`. */
    private fun refinePeak(windowed: DoubleArray, srHz: Double, low: Double, high: Double): Double {
        val phi = 0.6180339887498949
        var a = low
        var b = high
        var c = b - phi * (b - a)
        var d = a + phi * (b - a)
        var fc = goertzelMagnitude(windowed, srHz, c)
        var fd = goertzelMagnitude(windowed, srHz, d)
        var guard = 0
        while (b - a > LINE_REFINE_TOLERANCE_HZ && guard < 200) {
            if (fc > fd) {
                b = d
                d = c
                fd = fc
                c = b - phi * (b - a)
                fc = goertzelMagnitude(windowed, srHz, c)
            } else {
                a = c
                c = d
                fc = fd
                d = a + phi * (b - a)
                fd = goertzelMagnitude(windowed, srHz, d)
            }
            guard++
        }
        return (a + b) / 2.0
    }

    private fun goertzelMagnitude(x: DoubleArray, srHz: Double, frequencyHz: Double): Double {
        val omega = 2.0 * PI * frequencyHz / srHz
        val coefficient = 2.0 * cos(omega)
        var s1 = 0.0
        var s2 = 0.0
        for (value in x) {
            val s0 = value + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        val real = s1 - s2 * cos(omega)
        val imaginary = s2 * sin(omega)
        return hypot(real, imaginary)
    }

    /**
     * Zero-phase notch on [line] and every harmonic below [ceilingHz].
     *
     * Notch width scales with harmonic order so each one covers the same
     * fractional bandwidth.
     */
    fun removeLineNoise(
        values: DoubleArray,
        srHz: Double,
        line: EcgLineNoise?,
    ): DoubleArray {
        if (line == null) return values.copyOf()
        val limit = 0.9 * (srHz / 2.0)
        var output = values
        var harmonic = line.frequencyHz
        var order = 1
        while (harmonic < limit && order <= MAX_LINE_HARMONICS) {
            val section = notchBiquad(harmonic, srHz, LINE_NOTCH_Q * order)
            output = filtfilt(listOf(section), output, srHz)
            harmonic += line.frequencyHz
            order++
        }
        return if (output === values) values.copyOf() else output
    }

    private const val MAX_LINE_HARMONICS = 6

    // ------------------------------------------------------------- baseline

    /**
     * Baseline estimate: 200 ms median (removes QRS) then 600 ms median (tracks
     * wander only). Standard ST-safe baseline removal; a single mid-length
     * median instead attenuates the T wave by more than 10%.
     */
    fun baseline(values: DoubleArray, srHz: Double): DoubleArray {
        if (values.isEmpty() || srHz <= 0.0) return DoubleArray(values.size)
        val stageOne = runningMedian(values, oddKernel(BASELINE_STAGE_ONE_MS, srHz))
        return runningMedian(stageOne, oddKernel(BASELINE_STAGE_TWO_MS, srHz))
    }

    private fun oddKernel(milliseconds: Int, srHz: Double): Int {
        var kernel = (milliseconds * srHz / 1_000.0).roundToInt()
        if (kernel % 2 == 0) kernel += 1
        return max(3, kernel)
    }

    /** Sliding median over a reflected signal; O(n·k) moves, no per-sample sort. */
    internal fun runningMedian(values: DoubleArray, kernel: Int): DoubleArray {
        val n = values.size
        if (n == 0) return DoubleArray(0)
        if (kernel <= 1 || kernel > n) return values.copyOf()
        val radius = kernel / 2
        val padded = DoubleArray(n + 2 * radius)
        java.util.Arrays.fill(padded, 0, radius, values[0])
        System.arraycopy(values, 0, padded, radius, n)
        java.util.Arrays.fill(padded, radius + n, padded.size, values[n - 1])

        val window = DoubleArray(kernel)
        System.arraycopy(padded, 0, window, 0, kernel)
        window.sort()
        val out = DoubleArray(n)
        out[0] = window[radius]
        for (i in 1 until n) {
            removeSorted(window, kernel, padded[i - 1])
            insertSorted(window, kernel, padded[i + kernel - 1])
            out[i] = window[radius]
        }
        return out
    }

    private fun removeSorted(window: DoubleArray, size: Int, value: Double) {
        var position = lowerBound(window, size, value)
        if (position >= size || window[position] != value) {
            // Guard against a binary-search miss on repeated values.
            position = window.indexOfFirst(size) { it == value }
            if (position < 0) return
        }
        System.arraycopy(window, position + 1, window, position, size - position - 1)
    }

    private fun insertSorted(window: DoubleArray, size: Int, value: Double) {
        val position = lowerBound(window, size - 1, value)
        System.arraycopy(window, position, window, position + 1, size - position - 1)
        window[position] = value
    }

    private fun lowerBound(window: DoubleArray, size: Int, value: Double): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (window[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }

    private inline fun DoubleArray.indexOfFirst(size: Int, predicate: (Double) -> Boolean): Int {
        for (i in 0 until size) if (predicate(this[i])) return i
        return -1
    }

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
        val reference = medianOf(rms.copyOf())
        if (reference <= 0.0) return 0
        val limit = min(blockCount, (SETTLE_SEARCH_SEC * srHz / block).roundToInt())
        var last = -1
        for (b in 0 until limit) if (rms[b] > SETTLE_RMS_RATIO * reference) last = b
        return if (last < 0) 0 else min(filtered.size, (last + 1) * block)
    }

    // ------------------------------------------------------------ filtering

    /** Butterworth section quality factors for an even [order]. */
    internal fun butterworthQs(order: Int): DoubleArray {
        val sections = order / 2
        return DoubleArray(sections) { k -> 1.0 / (2.0 * cos((2.0 * (k + 1) - 1.0) * PI / (2.0 * order))) }
    }

    internal fun lowPassSections(cutoffHz: Double, srHz: Double, order: Int): List<Biquad> =
        butterworthQs(order).map { q -> lowPassBiquad(cutoffHz, srHz, q) }

    /** RBJ cookbook biquads; stable and well conditioned at these cutoffs. */
    internal fun lowPassBiquad(frequencyHz: Double, srHz: Double, q: Double): Biquad {
        val w0 = 2.0 * PI * frequencyHz / srHz
        val cosine = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(
            b0 = (1.0 - cosine) / 2.0 / a0,
            b1 = (1.0 - cosine) / a0,
            b2 = (1.0 - cosine) / 2.0 / a0,
            a1 = -2.0 * cosine / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    internal fun notchBiquad(frequencyHz: Double, srHz: Double, q: Double): Biquad {
        val w0 = 2.0 * PI * frequencyHz / srHz
        val cosine = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        return Biquad(
            b0 = 1.0 / a0,
            b1 = -2.0 * cosine / a0,
            b2 = 1.0 / a0,
            a1 = -2.0 * cosine / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    internal data class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    /** Forward-backward filtering with odd extension, matching `scipy.signal.filtfilt`. */
    internal fun filtfilt(sections: List<Biquad>, x: DoubleArray, srHz: Double): DoubleArray {
        if (x.isEmpty() || sections.isEmpty()) return x.copyOf()
        val pad = min(x.size - 1, max(1, (3.0 * srHz).roundToInt()))
        val extended = oddExtend(x, pad)
        var forward = extended
        for (section in sections) forward = biquad(section, forward)
        val reversed = DoubleArray(forward.size) { forward[forward.lastIndex - it] }
        var backward = reversed
        for (section in sections) backward = biquad(section, backward)
        return DoubleArray(x.size) { backward[backward.lastIndex - (pad + it)] }
    }

    private fun biquad(section: Biquad, x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return DoubleArray(0)
        val y = DoubleArray(x.size)
        // Seed from the constant-input steady state so a DC offset does not
        // create a false edge transient.
        val denominator = 1.0 + section.a1 + section.a2
        val dcGain = if (abs(denominator) > 1e-12) {
            (section.b0 + section.b1 + section.b2) / denominator
        } else {
            0.0
        }
        var x1 = x[0]
        var x2 = x[0]
        var y1 = x[0] * dcGain
        var y2 = y1
        for (i in x.indices) {
            val xi = x[i]
            val yi = section.b0 * xi + section.b1 * x1 + section.b2 * x2 - section.a1 * y1 - section.a2 * y2
            y[i] = yi
            x2 = x1
            x1 = xi
            y2 = y1
            y1 = yi
        }
        return y
    }

    private fun oddExtend(x: DoubleArray, pad: Int): DoubleArray {
        if (pad <= 0) return x.copyOf()
        val n = x.size
        val out = DoubleArray(n + 2 * pad)
        for (i in 0 until pad) out[i] = 2 * x[0] - x[reflect(pad - i, n)]
        System.arraycopy(x, 0, out, pad, n)
        for (i in 0 until pad) out[pad + n + i] = 2 * x[n - 1] - x[reflect(n - 2 - i, n)]
        return out
    }

    private fun reflect(i: Int, n: Int): Int {
        if (n <= 1) return 0
        val period = 2 * (n - 1)
        var x = i % period
        if (x < 0) x += period
        return if (x >= n) period - x else x
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

    /** RMS inside a +/-1.5 Hz band around the line fundamental. */
    private fun lineRms(values: DoubleArray, srHz: Double, line: EcgLineNoise?): Double {
        if (line == null || values.isEmpty()) return 0.0
        val bandwidthHz = 3.0
        val q = line.frequencyHz / bandwidthHz
        val w0 = 2.0 * PI * line.frequencyHz / srHz
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        val section = Biquad(
            b0 = alpha / a0,
            b1 = 0.0,
            b2 = -alpha / a0,
            a1 = -2.0 * cos(w0) / a0,
            a2 = (1.0 - alpha) / a0,
        )
        val band = filtfilt(listOf(section), values, srHz)
        var acc = 0.0
        for (v in band) acc += v * v
        return sqrt(acc / band.size)
    }

    // ------------------------------------------------------------- helpers

    private fun meanOf(x: List<Double>): Double {
        var acc = 0.0
        for (v in x) acc += v
        return acc / x.size
    }

    private fun medianOf(sortable: DoubleArray): Double {
        if (sortable.isEmpty()) return 0.0
        sortable.sort()
        val mid = sortable.size / 2
        return if (sortable.size % 2 == 1) sortable[mid] else (sortable[mid - 1] + sortable[mid]) / 2.0
    }
}
