package app.galaxyvitals.data.protocol.dsp

import app.galaxyvitals.data.protocol.EcgLineNoise
import app.galaxyvitals.data.protocol.EcgStats
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Lowest and highest plausible mains fundamental, in hertz. */
const val LINE_SCAN_LOW_HZ = 45.0
const val LINE_SCAN_HIGH_HZ = 65.0

/** Peak-to-local-floor ratio a spectral peak must beat to count as mains. */
const val MIN_LINE_PROMINENCE = 6.0

/** -3 dB notch width is `f0 / Q`; 2.5 Hz at 50 Hz. */
const val LINE_NOTCH_Q = 20.0

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

private const val MAX_LINE_HARMONICS = 6

/**
 * Strongest 45-65 Hz component of [values], or `null` when nothing in that
 * band stands out far enough from the local floor to be mains.
 *
 * The scan runs on the corrected clock, so a well-formed capture reports a
 * frequency close to the grid nominal. A result far from 50 or 60 Hz is
 * itself a sign the sample clock is wrong.
 */
internal fun estimateLineNoise(values: DoubleArray, srHz: Double): EcgLineNoise? {
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
    val floor = EcgStats.median(neighbourhood.toDoubleArray(), whenEmpty = 0.0)
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
internal fun removeLineNoise(
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


/** RMS inside a +/-1.5 Hz band around the line fundamental. */
internal fun lineRms(values: DoubleArray, srHz: Double, line: EcgLineNoise?): Double {
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
