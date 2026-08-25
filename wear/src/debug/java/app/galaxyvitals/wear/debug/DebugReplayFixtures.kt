package app.galaxyvitals.wear.debug

import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.PpgGreenBatch
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

internal sealed interface DebugReplayCommand {
    data object UseHardware : DebugReplayCommand
    data class SetFixture(val name: String) : DebugReplayCommand
    data object Unchanged : DebugReplayCommand
}

internal fun parseDebugReplayCommand(raw: String?): DebugReplayCommand {
    val name = raw?.trim().orEmpty()
    if (name == "hardware") return DebugReplayCommand.UseHardware
    val fixture = DebugReplayFixtures.parseName(name)
    return if (fixture != null) DebugReplayCommand.SetFixture(fixture) else DebugReplayCommand.Unchanged
}

internal object DebugReplayFixtures {
    const val SAMPLE_RATE_HZ = 500
    const val SAMPLE_PERIOD_MS = 2L
    /** Timestamp gap ~10 s from stream start (after 4 s hold, during recording). */
    const val LEAD_OFF_GAP_AT_SAMPLE = 10 * SAMPLE_RATE_HZ
    /** Lead-off ~24 s from stream start (still inside the 30 s capture). */
    const val LEAD_OFF_CONTACT_AT_SAMPLE = 24 * SAMPLE_RATE_HZ
    val NAMES: Set<String> = Fixture.entries.map { it.id }.toSet()

    fun parseName(raw: String?): String? {
        val name = raw?.trim().orEmpty()
        return name.takeIf { it in NAMES }
    }

    fun batches(
        name: String,
        sampleCount: Int = SAMPLE_RATE_HZ * 30,
        startTimestampMs: Long = 1_000L,
    ): List<EcgBatch> {
        val fixture = Fixture.fromId(name) ?: error("Unknown replay fixture $name")
        val count = sampleCount - sampleCount % 15
        require(count >= 15) { "sampleCount must cover at least one 5/10 partition pair" }
        val samples = waveform(fixture, count)
        val sizes = partitionSizes(count)
        val gapAt = if (fixture == Fixture.LEAD_OFF_GAP) {
            eventBatchStart(sizes, count, LEAD_OFF_GAP_AT_SAMPLE)
        } else {
            -1
        }
        val leadOffAt = if (fixture == Fixture.LEAD_OFF_GAP) {
            eventBatchStart(sizes, count, LEAD_OFF_CONTACT_AT_SAMPLE)
        } else {
            -1
        }
        val (minTh, maxTh) = thresholds(fixture)
        val batches = ArrayList<EcgBatch>(sizes.size)
        var offset = 0
        var timestampShiftMs = 0L
        var leadOffBatchesRemaining = 0
        for ((sequence, size) in sizes.withIndex()) {
            var gapOnFirst = false
            if (offset == gapAt) {
                timestampShiftMs += GAP_MS
                gapOnFirst = true
            }
            if (offset == leadOffAt) {
                leadOffBatchesRemaining = 4
            }
            val leadOff = if (leadOffBatchesRemaining > 0) 1 else 0
            if (leadOffBatchesRemaining > 0) leadOffBatchesRemaining -= 1
            val chunk = samples.copyOfRange(offset, offset + size)
            val timestamps = LongArray(size) { index ->
                startTimestampMs + timestampShiftMs + (offset + index) * SAMPLE_PERIOD_MS
            }
            val flags = IntArray(size)
            if (leadOff != 0) {
                for (i in flags.indices) flags[i] = flags[i] or EcgSampleFlags.CONTACT_LOSS
            }
            if (gapOnFirst) flags[0] = flags[0] or EcgSampleFlags.TIMESTAMP_GAP
            for (i in chunk.indices) {
                if (chunk[i] < minTh || chunk[i] > maxTh) {
                    flags[i] = flags[i] or EcgSampleFlags.CLIPPED
                }
            }
            val ppgOffsets = if (size == 5) intArrayOf(0) else intArrayOf(0, 5)
            val ppg = PpgGreenBatch(
                values = IntArray(ppgOffsets.size) { i ->
                    ppgValue(offset + ppgOffsets[i], fixture)
                },
                ecgSampleOffsets = ppgOffsets,
                sensorTimestampsMs = LongArray(ppgOffsets.size) { i ->
                    timestamps[ppgOffsets[i]]
                },
            )
            batches += EcgBatch(
                samplesMv = chunk,
                sensorTimestampsMs = timestamps,
                sequence = sequence and 0xff,
                leadOff = leadOff,
                minThresholdMv = minTh,
                maxThresholdMv = maxTh,
                sampleFlags = flags,
                ppgGreen = ppg,
            )
            offset += size
        }
        return batches
    }

    private fun partitionSizes(sampleCount: Int): List<Int> {
        val sizes = ArrayList<Int>(sampleCount / 7)
        var remaining = sampleCount
        var five = true
        while (remaining >= 5) {
            val size = if (five) 5 else 10
            if (remaining < size) break
            sizes += size
            remaining -= size
            five = !five
        }
        return sizes
    }

    private fun eventBatchStart(sizes: List<Int>, sampleCount: Int, minStart: Int): Int {
        if (minStart >= sampleCount) return -1
        var start = 0
        for (size in sizes) {
            if (start >= minStart) return start
            start += size
        }
        return -1
    }

    private fun waveform(fixture: Fixture, n: Int): FloatArray {
        val out = DoubleArray(n)
        val bpm = fixture.bpm
        if (bpm != null) {
            val period = SAMPLE_RATE_HZ * 60.0 / bpm
            var peak = period * 0.5
            while (peak < n) {
                val r = peak.roundToInt()
                addGaussian(out, r - (0.18 * SAMPLE_RATE_HZ).roundToInt(), 0.12, 0.035 * SAMPLE_RATE_HZ)
                addGaussian(out, r - (0.025 * SAMPLE_RATE_HZ).roundToInt(), -0.15, 0.008 * SAMPLE_RATE_HZ)
                addGaussian(out, r, 1.20, 0.010 * SAMPLE_RATE_HZ)
                addGaussian(out, r + (0.025 * SAMPLE_RATE_HZ).roundToInt(), -0.28, 0.010 * SAMPLE_RATE_HZ)
                addGaussian(out, r + (0.22 * SAMPLE_RATE_HZ).roundToInt(), fixture.tWaveMv, 0.045 * SAMPLE_RATE_HZ)
                peak += period
            }
        }
        val rng = Random(1L)
        for (index in out.indices) {
            val t = index.toDouble() / SAMPLE_RATE_HZ
            if (bpm != null) {
                out[index] += 0.04 * sin(2 * PI * 0.25 * t)
            } else {
                out[index] += 0.05 * sin(2 * PI * 1.3 * t)
                out[index] += 0.08 * sin(2 * PI * 50.0 * t)
            }
            out[index] += fixture.dcOffsetMv
            if (fixture.noiseRms > 0.0) {
                out[index] += rng.nextGaussian() * fixture.noiseRms
            }
        }
        return FloatArray(n) { out[it].toFloat() }
    }

    private fun addGaussian(out: DoubleArray, center: Int, amplitude: Double, sigma: Double) {
        if (sigma <= 0.0) return
        val radius = (sigma * 4.0).roundToInt().coerceAtLeast(1)
        val twoSigmaSq = 2.0 * sigma * sigma
        for (delta in -radius..radius) {
            val index = center + delta
            if (index in out.indices) {
                out[index] += amplitude * exp(-(delta * delta) / twoSigmaSq)
            }
        }
    }

    private fun ppgValue(globalIndex: Int, fixture: Fixture): Int {
        val bpm = fixture.bpm
        if (bpm == null) {
            return 12_000 + ((globalIndex * 37) % 900) - 450
        }
        val period = SAMPLE_RATE_HZ * 60.0 / bpm
        val peak = period / 5.0
        val sigma = period * 0.08
        val phase = globalIndex % period
        val gauss = exp(-((phase - peak) * (phase - peak)) / (2.0 * sigma * sigma))
        return (12_000 + 4_000 * gauss).toInt()
    }

    private fun thresholds(fixture: Fixture): Pair<Float, Float> {
        return if (fixture == Fixture.DC_OFFSET_72) {
            -200f to 200f
        } else {
            -5f to 5f
        }
    }

    private const val GAP_MS = 200L

    internal enum class Fixture(
        val id: String,
        val bpm: Double?,
        val tWaveMv: Double = 0.30,
        val dcOffsetMv: Double = 0.0,
        val noiseRms: Double = 0.0,
    ) {
        CLEAN_40("clean_40", 40.0),
        CLEAN_72("clean_72", 72.0),
        CLEAN_120("clean_120", 120.0),
        CLEAN_180("clean_180", 180.0),
        TWAVE_72("twave_72", 72.0, tWaveMv = 0.85),
        DC_OFFSET_72("dc_offset_72", 72.0, dcOffsetMv = 100.0),
        NOISE_ABSTAIN("noise_abstain", bpm = null, noiseRms = 0.12),
        LEAD_OFF_GAP("lead_off_gap", 72.0),
        ;

        companion object {
            fun fromId(id: String): Fixture? = entries.firstOrNull { it.id == id }
        }
    }
}
