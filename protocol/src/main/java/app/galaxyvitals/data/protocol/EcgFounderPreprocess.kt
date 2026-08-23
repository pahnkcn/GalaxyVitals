package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PreparedWindow(
    val samples: FloatArray,
    val startRelMs: Long,
    val rms: Float,
    val qualityFlags: Set<QualityFlag> = emptySet(),
)

data class PreparedRecording(
    val windows: List<PreparedWindow>,
    val quality: SignalQualityReport,
)

data class SignalQuality(
    val usable: Boolean,
    val reason: String,
    val rms: Float,
)

/**
 * Matches ECGFounder `util.filter_bandpass` + z-score + 10 s @ 500 Hz windows.
 * Coefficients generated from SciPy to match the official notebook.
 */
object EcgFounderPreprocess {
    const val TARGET_HZ = 500
    const val WINDOW_SAMPLES = 5000
    const val WINDOW_MS = 10_000L
    const val HOP_MS = 5_000L
    val SUPPORTED_INPUT_HZ = setOf(250, 300, 500)

    fun prepare(parsed: ParsedEcgFile): PreparedRecording {
        val baseQuality = SignalQualityAnalyzer.analyze(parsed)
        if (parsed.srHz !in SUPPORTED_INPUT_HZ) return PreparedRecording(emptyList(), baseQuality)
        val out = ArrayList<PreparedWindow>()
        val cleanRanges = ArrayList<LongRange>()
        val polarity = parsed.effectivePolarity()
        baseQuality.segments.forEach { segment ->
            if (segment.endRelMs - segment.startRelMs < WINDOW_MS - 2L) return@forEach
            val oriented = FloatArray(segment.samples.size) { index ->
                segment.samples[index].valueMv * polarity
            }
            val series = resamplePolyphase(oriented, parsed.srHz, TARGET_HZ)
            val filtered = filterBandpass(series)
            val hop = TARGET_HZ * (HOP_MS / 1000).toInt()
            var start = 0
            while (start + WINDOW_SAMPLES <= filtered.size) {
                val slice = filtered.copyOfRange(start, start + WINDOW_SAMPLES)
                val windowFlags = SignalQualityAnalyzer.assessWindow(slice, TARGET_HZ)
                val startMs = segment.startRelMs + start * 1000L / TARGET_HZ
                if (windowFlags.isEmpty()) {
                    out += PreparedWindow(zScore(slice), startMs, rms(slice), windowFlags)
                    cleanRanges += startMs..(startMs + WINDOW_MS)
                }
                start += hop
            }
        }
        val durationMs = parsed.samples.last().relMs - parsed.samples.first().relMs
        val finalQuality = SignalQualityAnalyzer.withCleanWindows(baseQuality, cleanRanges, durationMs)
        return PreparedRecording(out, finalQuality)
    }

    // iirnotch(50, 30, 500) as SOS
    private val NOTCH = arrayOf(
        doubleArrayOf(
            0.9896361753628921, -1.6012649682336104, 0.989636175362892,
            1.0, -1.6012649682336102, 0.9792723507257837,
        ),
    )

    // butter(4, [0.67, 40], bandpass, fs=500) as SOS
    private val BANDPASS = arrayOf(
        doubleArrayOf(0.0021067813406204, 0.0042135626812408, 0.0021067813406204, 1.0, -1.233079827668742, 0.3968477349869862),
        doubleArrayOf(1.0, 2.0, 1.0, 1.0, -1.4889634861105354, 0.6970941053044079),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.984128188631517, 0.984202641211675),
        doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9936562232241961, 0.9937275352174721),
    )

    fun windows(samples: List<EcgSample>, srHz: Int): List<PreparedWindow> {
        if (samples.isEmpty() || srHz !in SUPPORTED_INPUT_HZ) return emptyList()
        val series = resampleToTarget(samples, srHz)
        val filtered = filterBandpass(series)
        val out = ArrayList<PreparedWindow>()
        var start = 0
        val hop = TARGET_HZ * (HOP_MS / 1000).toInt()
        if (filtered.size < WINDOW_SAMPLES) return emptyList()
        while (start + WINDOW_SAMPLES <= filtered.size) {
            val slice = filtered.copyOfRange(start, start + WINDOW_SAMPLES)
            val z = zScore(slice)
            val startMs = samples.first().relMs + start * 1000L / TARGET_HZ
            out += PreparedWindow(z, startMs, rms(slice))
            start += hop
        }
        return out
    }

    fun quality(windows: List<PreparedWindow>, usablePct: Double): SignalQuality {
        if (windows.isEmpty()) return SignalQuality(false, "No ECG windows", 0f)
        if (!usablePct.isFinite()) {
            return SignalQuality(false, "Signal quality is not finite", 0f)
        }
        if (windows.any { window ->
                !window.rms.isFinite() || window.samples.any { sample -> !sample.isFinite() }
            }
        ) {
            return SignalQuality(false, "Signal contains non-finite values", 0f)
        }
        val rms = windows.map { it.rms }.average().toFloat()
        if (!rms.isFinite()) return SignalQuality(false, "Signal RMS is not finite", 0f)
        if (usablePct < 40.0) return SignalQuality(false, "Too much lead-off / flat signal", rms)
        if (rms < 0.01f) return SignalQuality(false, "Amplitude too small after filtering", rms)
        if (rms > 8f) return SignalQuality(false, "Amplitude looks clipped or saturated", rms)
        return SignalQuality(true, "", rms)
    }

    internal fun resampleToTarget(samples: List<EcgSample>, srHz: Int): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        return resamplePolyphase(FloatArray(samples.size) { samples[it].valueMv }, srHz, TARGET_HZ)
    }

    /** Windowed-sinc polyphase FIR resampler for the supported 250/300/500-Hz inputs. */
    internal fun resamplePolyphase(input: FloatArray, sourceHz: Int, targetHz: Int): FloatArray {
        require(sourceHz in SUPPORTED_INPUT_HZ && targetHz == TARGET_HZ)
        if (sourceHz == targetHz) return input.copyOf()
        if (input.isEmpty()) return FloatArray(0)
        val outputSize = ((input.size - 1).toLong() * targetHz / sourceHz).toInt() + 1
        val output = FloatArray(outputSize)
        val radius = 16
        for (outIndex in output.indices) {
            val sourcePosition = outIndex.toDouble() * sourceHz / targetHz
            val center = kotlin.math.floor(sourcePosition).toInt()
            var weighted = 0.0
            var weightSum = 0.0
            for (tap in (center - radius + 1)..(center + radius)) {
                if (tap !in input.indices) continue
                val distance = sourcePosition - tap
                val sinc = if (abs(distance) < 1e-12) 1.0 else {
                    kotlin.math.sin(Math.PI * distance) / (Math.PI * distance)
                }
                val normalized = distance / radius
                val window = if (abs(normalized) <= 1.0) {
                    0.5 + 0.5 * kotlin.math.cos(Math.PI * normalized)
                } else 0.0
                val weight = sinc * window
                weighted += input[tap] * weight
                weightSum += weight
            }
            output[outIndex] = if (abs(weightSum) > 1e-12) {
                (weighted / weightSum).toFloat()
            } else input[center.coerceIn(input.indices)]
        }
        return output
    }

    internal fun filterBandpass(raw: FloatArray): FloatArray {
        if (raw.isEmpty()) return raw
        val x = DoubleArray(raw.size) { raw[it].toDouble() }
        val notched = filtfilt(NOTCH, x)
        val band = filtfilt(BANDPASS, notched)
        val baseline = medianFilter(band, medianKernel(TARGET_HZ))
        return FloatArray(raw.size) { i -> (band[i] - baseline[i]).toFloat() }
    }

    internal fun zScore(x: FloatArray): FloatArray {
        var sum = 0.0
        var sumSq = 0.0
        for (v in x) {
            sum += v
            sumSq += v * v
        }
        val n = x.size.toDouble()
        val mean = sum / n
        val sd = sqrt(max(1e-16, sumSq / n - mean * mean)).toFloat()
        val denom = if (sd < 1e-8f) 1f else sd
        return FloatArray(x.size) { ((x[it] - mean.toFloat()) / denom) }
    }

    private fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var acc = 0.0
        for (v in x) acc += v * v
        return sqrt(acc / x.size).toFloat()
    }

    private fun medianKernel(fs: Int): Int {
        var k = (0.4 * fs).roundToInt() + 1
        if (k % 2 == 0) k += 1
        return max(3, k)
    }

    internal fun medianFilter(x: DoubleArray, kernel: Int): DoubleArray {
        val r = kernel / 2
        val y = DoubleArray(x.size)
        val buf = DoubleArray(kernel)
        for (i in x.indices) {
            var n = 0
            for (k in -r..r) {
                val idx = reflect(i + k, x.size)
                buf[n++] = x[idx]
            }
            buf.sort(0, n)
            y[i] = buf[n / 2]
        }
        return y
    }

    internal fun filtfilt(sos: Array<DoubleArray>, x: DoubleArray): DoubleArray {
        val pad = min(x.size - 1, 3 * 2 * sos.size)
        val ext = oddExtend(x, pad)
        val fwd = sosFilt(sos, ext)
        val revIn = DoubleArray(fwd.size) { fwd[fwd.lastIndex - it] }
        val rev = sosFilt(sos, revIn)
        val y = DoubleArray(x.size)
        for (i in x.indices) {
            y[i] = rev[rev.lastIndex - (pad + i)]
        }
        return y
    }

    private fun sosFilt(sos: Array<DoubleArray>, x: DoubleArray): DoubleArray {
        var y = x
        for (section in sos) {
            y = biquad(section, y)
        }
        return y
    }

    private fun biquad(s: DoubleArray, x: DoubleArray): DoubleArray {
        if (x.isEmpty()) return DoubleArray(0)
        val b0 = s[0]
        val b1 = s[1]
        val b2 = s[2]
        val a1 = s[4]
        val a2 = s[5]
        val y = DoubleArray(x.size)
        // Start each section at its constant-input steady state. Zero initial
        // conditions create a false edge transient large enough to make a flat
        // ECG look usable after forward/backward filtering.
        val denominator = 1.0 + a1 + a2
        val dcGain = if (abs(denominator) > 1e-12) {
            (b0 + b1 + b2) / denominator
        } else {
            0.0
        }
        var x1 = x[0]
        var x2 = x[0]
        var y1 = x[0] * dcGain
        var y2 = y1
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
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
        for (i in 0 until pad) {
            val idx = reflect(pad - i, n)
            out[i] = 2 * x[0] - x[idx]
        }
        System.arraycopy(x, 0, out, pad, n)
        for (i in 0 until pad) {
            val idx = reflect(n - 2 - i, n)
            out[pad + n + i] = 2 * x[n - 1] - x[idx]
        }
        return out
    }

    private fun reflect(i: Int, n: Int): Int {
        if (n <= 1) return 0
        var x = i
        val period = 2 * (n - 1)
        x %= period
        if (x < 0) x += period
        return if (x >= n) period - x else x
    }
}
