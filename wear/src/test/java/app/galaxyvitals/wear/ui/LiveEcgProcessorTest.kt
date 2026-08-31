package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.WaveformScale
import app.galaxyvitals.wear.sensors.EcgBatch
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Test

class LiveEcgProcessorTest {
    @Test
    fun beginSettledWindowClearsTransientAndResetsScale() {
        val processor = LiveEcgProcessor()
        processor.append(
            EcgBatch(
                samplesMv = FloatArray(200) { index -> if (index % 20 == 0) 3f else 0.05f },
                sensorTimestampsMs = LongArray(200) { 1_000L + it * 2L },
                sequence = 0,
                leadOff = 0,
                minThresholdMv = -5f,
                maxThresholdMv = 5f,
                sampleFlags = IntArray(200),
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
    fun captureWindowCanReuseSettledDisplayFilterWithoutKeepingPreflightSamples() {
        val values = FloatArray(751) { index ->
            (0.2 * sin(2.0 * PI * 7.0 * index / 500.0)).toFloat()
        }
        val continuous = LiveEcgProcessor().also { processor ->
            processor.append(batch(sequence = 0, values = values))
        }
        val warmStarted = LiveEcgProcessor().also { processor ->
            processor.append(batch(sequence = 0, values = values.copyOf(750)))
            processor.beginCaptureWindow(signFactor = 1, preserveDisplaySettling = true)
            processor.append(batch(sequence = 0, values = floatArrayOf(values.last())))
        }

        assertThat(warmStarted.nextEcgSampleIndex).isEqualTo(1L)
        assertThat(warmStarted.analysisSamples.toList()).containsExactly(values.last())
        assertThat(warmStarted.displaySamples).hasSize(1)
        assertThat(warmStarted.displaySamples.single())
            .isWithin(1e-6f)
            .of(continuous.displaySamples.last())
    }

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
        val count = 2_000
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
