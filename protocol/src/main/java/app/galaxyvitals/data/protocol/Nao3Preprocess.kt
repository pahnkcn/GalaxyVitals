package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Exact preprocessing contract used by the direct three-output GeminiMan model. */
object Nao3Preprocess {
    const val TARGET_HZ = 256
    const val INPUT_SAMPLES = 7_680
    const val DURATION_SECONDS = 30

    /**
     * Rows from `ecg_filters_256hz.json`, in the contract order: three
     * band-pass sections, the 50-Hz notch, then the 60-Hz notch.
     */
    private val SOS = arrayOf(
        doubleArrayOf(
            0.05303763579076746,
            0.10607527158153492,
            0.05303763579076746,
            1.0,
            -0.7950426882264209,
            0.4212118677344409,
        ),
        doubleArrayOf(
            1.0,
            0.0,
            -1.0,
            1.0,
            -1.301494536469592,
            0.3100598090321417,
        ),
        doubleArrayOf(
            1.0,
            -2.0,
            1.0,
            1.0,
            -1.9877958220371288,
            0.987947188791069,
        ),
        doubleArrayOf(
            0.9799541272795681,
            -0.6602732045406293,
            0.9799541272795681,
            1.0,
            -0.6602732045406293,
            0.9599082545591362,
        ),
        doubleArrayOf(
            0.9760395733504627,
            -0.19133721565659384,
            0.9760395733504627,
            1.0,
            -0.19133721565659384,
            0.9520791467009253,
        ),
    )

    fun prepare(parsed: ParsedEcgFile): FloatArray {
        if (parsed.samples.isEmpty()) return FloatArray(INPUT_SAMPLES)
        val polarity = parsed.effectivePolarity()
        val oriented = FloatArray(parsed.samples.size) { index ->
            parsed.samples[index].valueMv * polarity
        }
        require(oriented.all { it.isFinite() }) { "NAO3 input contains non-finite ECG samples" }

        val resampled = linearResample(oriented, parsed.srHz)
        val filtered = forwardReverseSos(resampled)
        val normalized = zScore(filtered)
        val fitted = centerFit(normalized, INPUT_SAMPLES)
        check(fitted.all { it.isFinite() }) { "NAO3 preprocessing produced non-finite values" }
        return fitted
    }

    /** Center 30 s of the first continuous run that is long enough; never concatenates across a gap. */
    fun selectExactWindow(parsed: ParsedEcgFile): ParsedEcgFile? {
        if (parsed.srHz <= 0) return null
        val needed = parsed.srHz * DURATION_SECONDS
        val run = continuousRuns(parsed.samples).firstOrNull { it.size >= needed } ?: return null
        val start = (run.size - needed) / 2
        val slice = run.subList(start, start + needed).toList()
        return parsed.copy(
            samples = slice,
            durationSec = DURATION_SECONDS.toDouble(),
        )
    }

    /** Filter and z-score an already-selected 30 s window. Refuses pad or crop. */
    fun prepareExact(parsed: ParsedEcgFile): FloatArray {
        require(parsed.samples.isNotEmpty()) { "NAO3 inference requires samples" }
        val polarity = parsed.effectivePolarity()
        val oriented = FloatArray(parsed.samples.size) { index ->
            parsed.samples[index].valueMv * polarity
        }
        require(oriented.all { it.isFinite() }) { "NAO3 input contains non-finite ECG samples" }
        val resampled = linearResample(oriented, parsed.srHz)
        require(resampled.size == INPUT_SAMPLES) {
            "NAO3 inference requires exactly $INPUT_SAMPLES samples after resample, found ${resampled.size}"
        }
        val filtered = forwardReverseSos(resampled)
        val normalized = zScore(filtered)
        check(normalized.size == INPUT_SAMPLES)
        check(normalized.all { it.isFinite() }) { "NAO3 preprocessing produced non-finite values" }
        return normalized
    }

    private fun continuousRuns(samples: List<EcgSample>): List<List<EcgSample>> {
        if (samples.isEmpty()) return emptyList()
        val gapMask = EcgSampleFlags.TIMESTAMP_GAP or EcgSampleFlags.SEQUENCE_GAP
        val runs = ArrayList<List<EcgSample>>()
        var start = 0
        for (index in 1 until samples.size) {
            if (samples[index].flags and gapMask != 0) {
                runs += samples.subList(start, index)
                start = index
            }
        }
        runs += samples.subList(start, samples.size)
        return runs
    }

    /** Linear interpolation with GeminiMan's endpoint-duration output length. */
    internal fun linearResample(input: FloatArray, sourceHz: Int): FloatArray {
        require(sourceHz > 0) { "ECG sample rate must be positive" }
        if (input.isEmpty()) return FloatArray(0)
        val outputSize = (
            ((input.size - 1).toDouble() / sourceHz) * TARGET_HZ
            ).roundToInt() + 1
        val output = FloatArray(outputSize)
        for (outputIndex in output.indices) {
            val sourcePosition = outputIndex.toDouble() * sourceHz / TARGET_HZ
            val left = floor(sourcePosition).toInt().coerceIn(input.indices)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = sourcePosition - floor(sourcePosition)
            output[outputIndex] = (
                (1.0 - fraction) * input[left] + fraction * input[right]
                ).toFloat()
        }
        return output
    }

    /** Forward cascade, reverse, fresh zero-state cascade, then reverse back. */
    internal fun forwardReverseSos(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val forward = forwardSos(input)
        forward.reverse()
        val backward = forwardSos(forward)
        backward.reverse()
        return backward
    }

    internal fun zScore(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        var sum = 0.0
        var sumSquares = 0.0
        input.forEach { value ->
            val doubleValue = value.toDouble()
            sum += doubleValue
            sumSquares += doubleValue * doubleValue
        }
        val mean = sum / input.size
        val variance = max(sumSquares / input.size - mean * mean, 1e-9)
        val standardDeviation = sqrt(variance)
        return FloatArray(input.size) { index ->
            ((input[index] - mean) / standardDeviation).toFloat()
        }
    }

    internal fun centerFit(input: FloatArray, outputSize: Int): FloatArray {
        require(outputSize >= 0) { "NAO3 output length cannot be negative" }
        if (input.size == outputSize) return input.copyOf()
        val output = FloatArray(outputSize)
        if (input.size > outputSize) {
            val sourceStart = max(0, (input.size - outputSize) / 2)
            input.copyInto(output, startIndex = sourceStart, endIndex = sourceStart + outputSize)
        } else {
            val destinationStart = (outputSize - input.size) / 2
            input.copyInto(output, destinationOffset = destinationStart)
        }
        return output
    }

    private fun forwardSos(input: FloatArray): FloatArray {
        var source = input.copyOf()
        var destination = FloatArray(input.size)
        SOS.forEach { row ->
            val a0 = if (abs(row[3]) < 1e-12) 1.0 else row[3]
            val b0 = row[0] / a0
            val b1 = row[1] / a0
            val b2 = row[2] / a0
            val a1 = row[4] / a0
            val a2 = row[5] / a0
            var state1 = 0.0
            var state2 = 0.0
            for (index in source.indices) {
                val sample = source[index].toDouble()
                val output = b0 * sample + state1
                state1 = b1 * sample - a1 * output + state2
                state2 = b2 * sample - a2 * output
                destination[index] = output.toFloat()
            }
            val swap = source
            source = destination
            destination = swap
        }
        return source
    }
}
