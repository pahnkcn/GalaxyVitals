package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.WaveformScale
import app.galaxyvitals.wear.sensors.EcgBatch
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

class LiveEcgProcessorTest {
    @Test
    fun beginSettledWindowClearsTransientAndResetsScale() {
        val processor = LiveEcgProcessor()
        processor.append(
            EcgBatch(
                samplesMv = FloatArray(800) { index -> if (index % 20 == 0) 3f else 0.05f },
                sensorTimestampsMs = LongArray(800) { 1_000L + it * 2L },
                sequence = 0,
                leadOff = 0,
                minThresholdMv = -5f,
                maxThresholdMv = 5f,
                sampleFlags = IntArray(800),
            ),
        )
        val polluted = processor.waveformFrame(50L)
        assertThat(polluted.scale.halfRangeMv).isGreaterThan(WaveformScale.Default.halfRangeMv)
        assertThat(processor.displaySamples).isNotEmpty()

        processor.beginSettledWindow(signFactor = 1)

        assertThat(processor.displaySamples).isEmpty()
        assertThat(processor.analysisSamples).isEmpty()
        assertThat(processor.livePpg).isEmpty()
        assertThat(processor.waveformFrame(50L).scale).isEqualTo(WaveformScale.Default)
    }

    @Test
    fun beginCaptureWindowRestartsIndexAndAnalysis() {
        val processor = LiveEcgProcessor()
        processor.append(transientBatch(sequence = 0, valueMv = 0.4f, sampleCount = 25))
        assertThat(processor.nextEcgSampleIndex).isEqualTo(25L)

        processor.beginCaptureWindow(signFactor = -1)

        assertThat(processor.signFactor).isEqualTo(-1)
        assertThat(processor.nextEcgSampleIndex).isEqualTo(0L)
        assertThat(processor.analysisSampleCount).isEqualTo(0)
        assertThat(processor.displaySamples).isEmpty()
        processor.append(transientBatch(sequence = 0, valueMv = 0.2f, sampleCount = 10))
        assertThat(processor.nextEcgSampleIndex).isEqualTo(10L)
        assertThat(processor.analysisSamples.toList()).containsExactlyElementsIn(List(10) { 0.2f })
    }

    @Test
    fun displayDrawsNothingUntilTheMedianCascadeCanCentreAWindow() {
        val processor = LiveEcgProcessor()
        val hold = LiveEcgProcessor.DISPLAY_BASELINE_LOOKAHEAD_SAMPLES +
            LiveEcgProcessor.DISPLAY_WARMUP_SAMPLES

        val next = appendInBatches(processor, FloatArray(hold) { 0.1f })
        assertThat(processor.displaySamples).isEmpty()

        appendInBatches(processor, FloatArray(10) { 0.1f }, startSequence = next)
        assertThat(processor.displaySamples).hasSize(10)
        // Anchored on the newest drawable sample, not the newest received one.
        assertThat(processor.waveformFrame(100L).lastSampleIndex).isEqualTo(
            processor.nextEcgSampleIndex - 1L -
                LiveEcgProcessor.DISPLAY_BASELINE_LOOKAHEAD_SAMPLES,
        )
    }

    @Test
    fun electrodePolarisationAtCaptureStartDoesNotDominateTheFirstDrawnSecond() {
        val srHz = 500.0
        // The probe settles on one electrode offset. The capture runs on a
        // restarted sensor session, which polarises from its own and ramps ~17 mV
        // in 250 ms - what the watch does at the top of every recording, and 17x
        // the size of the QRS riding on it.
        val settledOffsetMv = 58f
        val sessionStartMv = 41f
        val rampSamples = 125
        val processor = LiveEcgProcessor()
        appendInBatches(
            processor,
            FloatArray(2_000) { settledOffsetMv + beatAt(it / srHz).toFloat() },
        )

        processor.beginCaptureWindow(signFactor = 1)
        appendInBatches(
            processor,
            FloatArray(1_800) { index ->
                val polarisation = if (index >= rampSamples) {
                    settledOffsetMv
                } else {
                    sessionStartMv + (settledOffsetMv - sessionStartMv) * index / rampSamples
                }
                polarisation + beatAt(index / srHz).toFloat()
            },
        )

        val drawn = processor.displaySamples
        val steadyPeak = drawn.drop(500).maxOf { abs(it) }
        assertThat(peakToPeak(drawn.take(500))).isLessThan(3f * steadyPeak)
        assertThat(drawn.take(500).maxOf { abs(it) }).isLessThan(1.5f * steadyPeak)
    }

    @Test
    fun conditionedBufferRemovesWanderTheDetectorWouldOtherwiseMeasure() {
        val srHz = 500.0
        val count = 5_000
        // 0.9 mV peak-to-peak of wander under a 1.2 mV R wave, which is what a
        // wrist capture actually delivers.
        val values = FloatArray(count) { index ->
            val t = index / srHz
            (beatAt(t) + 0.45 * sin(2 * PI * 0.3 * t)).toFloat()
        }
        val processor = LiveEcgProcessor()
        for (start in 0 until count step 10) {
            processor.append(batch(sequence = (start / 10) and 0xff, values = values.copyOfRange(start, start + 10)))
        }

        assertThat(processor.conditionedSamples).hasLength(processor.analysisSamples.size)
        // Raw keeps the wander; the conditioned trace does not.
        val rawTail = processor.analysisSamples.toList().takeLast(2_000)
        val conditionedTail = processor.conditionedSamples.toList().takeLast(2_000)
        assertThat(peakToPeak(rawTail)).isGreaterThan(1.5f)
        val rawWander = toneAmplitude(rawTail, srHz, 0.3)
        val conditionedWander = toneAmplitude(conditionedTail, srHz, 0.3)
        assertThat(rawWander).isGreaterThan(0.4)
        // Four poles at 0.5 Hz leave about an eighth of a 0.3 Hz component, which
        // takes the wander from larger than the R wave to a small fraction of it.
        assertThat(conditionedWander).isLessThan(0.2 * rawWander)
        assertThat(conditionedWander).isLessThan(0.1)
    }

    @Test
    fun conditioningIsLinearSoTheCallersSignFactorStillApplies() {
        val values = FloatArray(1_000) { index -> beatAt(index / 500.0).toFloat() }
        val upright = LiveEcgProcessor(signFactor = 1)
        val inverted = LiveEcgProcessor(signFactor = -1)
        for (start in 0 until 1_000 step 10) {
            val slice = values.copyOfRange(start, start + 10)
            upright.append(batch(sequence = (start / 10) and 0xff, values = slice))
            inverted.append(batch(sequence = (start / 10) and 0xff, values = slice))
        }
        // The analysis chain runs on the unoriented sample, so both processors
        // hold the same conditioned trace and the sign is applied downstream.
        assertThat(inverted.conditionedSamples.toList()).isEqualTo(upright.conditionedSamples.toList())
    }

    @Test
    fun effectiveSampleRateIsMeasuredFromSamsungBatchTimestamps() {
        val srHz = 501.67
        val processor = LiveEcgProcessor()
        val batches = 500
        for (index in 0 until batches) {
            val stamp = 1_000L + (index * 10 * 1_000.0 / srHz).toLong()
            processor.append(
                EcgBatch(
                    samplesMv = FloatArray(10) { 0.1f },
                    sensorTimestampsMs = LongArray(10) { stamp },
                    sequence = index and 0xff,
                    leadOff = 0,
                    minThresholdMv = -5f,
                    maxThresholdMv = 5f,
                    sampleFlags = IntArray(10),
                ),
            )
        }
        assertThat(processor.effectiveSrHz).isWithin(0.3).of(srHz)
    }

    @Test
    fun effectiveSampleRateFallsBackToTheDeclaredRateBeforeEnoughBatchesArrive() {
        val processor = LiveEcgProcessor()
        processor.append(transientBatch(sequence = 0, valueMv = 0.1f, sampleCount = 10))
        assertThat(processor.effectiveSrHz).isWithin(1e-9).of(500.0)
    }

    @Test
    fun captureWindowKeepsTheClockObservationsTheProbeCollected() {
        val srHz = 501.67
        val processor = LiveEcgProcessor()
        for (index in 0 until 500) {
            val stamp = 1_000L + (index * 10 * 1_000.0 / srHz).toLong()
            processor.append(
                EcgBatch(
                    samplesMv = FloatArray(10) { 0.1f },
                    sensorTimestampsMs = LongArray(10) { stamp },
                    sequence = index and 0xff,
                    leadOff = 0,
                    minThresholdMv = -5f,
                    maxThresholdMv = 5f,
                    sampleFlags = IntArray(10),
                ),
            )
        }
        val beforeCapture = processor.effectiveSrHz
        processor.beginCaptureWindow(signFactor = 1)

        assertThat(processor.conditionedSamples).isEmpty()
        // The sensor clock does not restart with the analysis window.
        assertThat(processor.effectiveSrHz).isWithin(1e-9).of(beforeCapture)
    }

    /**
     * Feeds [values] the way Samsung delivers them, in contiguous batches of ten,
     * returning the sequence number the next batch must carry to stay contiguous.
     */
    private fun appendInBatches(
        processor: LiveEcgProcessor,
        values: FloatArray,
        startSequence: Int = 0,
    ): Int {
        var sequence = startSequence
        for (start in values.indices step 10) {
            val end = minOf(start + 10, values.size)
            processor.append(
                batch(sequence = sequence++, values = values.copyOfRange(start, end)),
            )
        }
        return sequence
    }

    private fun peakToPeak(values: List<Float>): Float =
        (values.max() - values.min())

    private fun transientBatch(sequence: Int, valueMv: Float, sampleCount: Int = 10): EcgBatch = EcgBatch(
        samplesMv = FloatArray(sampleCount) { valueMv },
        sensorTimestampsMs = LongArray(sampleCount) { 1_000L + it * 2L },
        sequence = sequence and 0xff,
        leadOff = 0,
        minThresholdMv = -5f,
        maxThresholdMv = 5f,
        sampleFlags = IntArray(sampleCount),
    )

    @Test
    fun liveTraceNotchesMainsOnceTheProbeHasEnoughSamples() {
        val srHz = 500.0
        val lineHz = 49.85
        // The notch reaches the display DISPLAY_BASELINE_LOOKAHEAD_SAMPLES after it
        // is configured, so leave room for the measured tail to sit past that.
        val count = 2_600
        val values = FloatArray(count) { index ->
            val t = index / srHz
            (beatAt(t) + 0.26 * sin(2 * PI * lineHz * t)).toFloat()
        }
        val processor = LiveEcgProcessor()
        // Batches of ten, the way Samsung delivers them.
        for (start in 0 until count step 10) {
            processor.append(
                batch(sequence = (start / 10) and 0xff, values = values.copyOfRange(start, start + 10)),
            )
        }

        val line = processor.lineNoise
        assertThat(line).isNotNull()
        assertThat(line!!.frequencyHz).isWithin(0.3).of(lineHz)

        // The notch turns on after the estimate, so measure the tail of the
        // display window, which is entirely post-configuration.
        val display = processor.displaySamples
        val tail = display.subList(display.size - 500, display.size)
        assertThat(toneAmplitude(tail, srHz, lineHz)).isLessThan(0.02)
    }

    @Test
    fun liveTraceLeavesTheSignalAloneWhenThereIsNoMains() {
        val srHz = 500.0
        val count = 2_000
        val values = FloatArray(count) { (beatAt(it / srHz)).toFloat() }
        val processor = LiveEcgProcessor()
        for (start in 0 until count step 10) {
            processor.append(
                batch(sequence = (start / 10) and 0xff, values = values.copyOfRange(start, start + 10)),
            )
        }

        assertThat(processor.lineNoise).isNull()
    }

    private fun beatAt(timeSec: Double): Double {
        val rrSec = 60.0 / (75.0 + 5.0 * sin(2 * PI * 0.25 * timeSec))
        var phase = timeSec % rrSec
        if (phase < 0) phase += rrSec
        fun bump(centre: Double, width: Double): Double {
            val z = (phase - centre) / width
            return kotlin.math.exp(-0.5 * z * z)
        }
        return 0.62 * bump(0.0, 0.016) - 0.47 * bump(0.030, 0.020) + 0.38 * bump(0.230, 0.048)
    }

    private fun toneAmplitude(values: List<Float>, srHz: Double, frequencyHz: Double): Double {
        var sine = 0.0
        var cosine = 0.0
        for (index in values.indices) {
            val angle = 2 * PI * frequencyHz * index / srHz
            sine += values[index] * sin(angle)
            cosine += values[index] * kotlin.math.cos(angle)
        }
        return 2.0 * kotlin.math.sqrt(sine * sine + cosine * cosine) / values.size
    }

    private fun batch(sequence: Int, values: FloatArray): EcgBatch = EcgBatch(
        samplesMv = values,
        sensorTimestampsMs = LongArray(values.size) { 1_000L + it * 2L },
        sequence = sequence and 0xff,
        leadOff = 0,
        minThresholdMv = -5f,
        maxThresholdMv = 5f,
        sampleFlags = IntArray(values.size),
    )
}
