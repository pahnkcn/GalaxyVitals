package app.galaxyvitals.wear.debug

import app.galaxyvitals.domain.EcgSampleFlags
import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Test

class DebugReplayFixturesTest {
    @Test
    fun parseName_acceptsKnownFixturesAndRejectsUnknown() {
        assertThat(DebugReplayFixtures.NAMES).containsExactly(
            "clean_40",
            "clean_72",
            "clean_120",
            "clean_180",
            "twave_72",
            "dc_offset_72",
            "noise_abstain",
            "lead_off_gap",
        )
        for (name in DebugReplayFixtures.NAMES) {
            assertThat(DebugReplayFixtures.parseName(name)).isEqualTo(name)
        }
        assertThat(DebugReplayFixtures.parseName("not_a_fixture")).isNull()
        assertThat(DebugReplayFixtures.parseName("")).isNull()
        assertThat(DebugReplayFixtures.parseName(null)).isNull()
        assertThat(DebugReplayFixtures.parseName("CLEAN_72")).isNull()
    }

    @Test
    fun batches_alternateSize5And10WithSamsungPpgOffsets() {
        val batches = DebugReplayFixtures.batches("clean_72", sampleCount = 75)
        assertThat(batches.map { it.samplesMv.size })
            .containsExactly(5, 10, 5, 10, 5, 10, 5, 10, 5, 10)
            .inOrder()

        batches.forEachIndexed { index, batch ->
            assertThat(batch.sequence).isEqualTo(index and 0xff)
            assertThat(batch.leadOff).isEqualTo(0)
            val ppg = requireNotNull(batch.ppgGreen)
            val expectedOffsets = if (batch.samplesMv.size == 5) {
                intArrayOf(0)
            } else {
                intArrayOf(0, 5)
            }
            assertThat(ppg.ecgSampleOffsets.toList()).isEqualTo(expectedOffsets.toList())
            assertThat(ppg.values.size).isEqualTo(expectedOffsets.size)
            assertThat(ppg.sensorTimestampsMs.size).isEqualTo(expectedOffsets.size)
            expectedOffsets.forEachIndexed { ppgIndex, offset ->
                assertThat(ppg.sensorTimestampsMs[ppgIndex])
                    .isEqualTo(batch.sensorTimestampsMs[offset])
            }
        }
    }

    @Test
    fun leadOffGap_placesGapAndLeadOffInsideCaptureWindow() {
        val srHz = DebugReplayFixtures.SAMPLE_RATE_HZ
        val batches = DebugReplayFixtures.batches(
            "lead_off_gap",
            sampleCount = srHz * 60,
        )
        var sampleIndex = 0
        var gapAt: Int? = null
        var leadOffAt: Int? = null
        for (batch in batches) {
            if (gapAt == null &&
                batch.sampleFlags.any { flags -> flags and EcgSampleFlags.TIMESTAMP_GAP != 0 }
            ) {
                gapAt = sampleIndex
            }
            if (leadOffAt == null && batch.leadOff != 0) {
                leadOffAt = sampleIndex
            }
            sampleIndex += batch.samplesMv.size
        }
        assertThat(gapAt).isNotNull()
        assertThat(leadOffAt).isNotNull()

        val holdSamples = 4 * srHz
        val captureEndSamples = holdSamples + 30 * srHz
        assertThat(gapAt!!).isAtLeast(holdSamples)
        assertThat(gapAt).isLessThan(captureEndSamples)
        assertThat(leadOffAt!!).isAtLeast(holdSamples)
        assertThat(leadOffAt).isLessThan(captureEndSamples)
        assertThat(gapAt).isAtLeast(8 * srHz)
        assertThat(gapAt).isAtMost(12 * srHz)
        assertThat(leadOffAt).isAtLeast(20 * srHz)
        assertThat(leadOffAt).isAtMost(28 * srHz)
        assertThat(leadOffAt).isGreaterThan(gapAt)
        assertThat(batches.map { it.samplesMv.size }.toSet()).containsExactly(5, 10)
        assertThat(batches.any { it.ppgGreen != null }).isTrue()
    }

    @Test
    fun noiseAbstain_isNotACleanQrsTrain() {
        val noise = flatten("noise_abstain", sampleCount = 2_505)
        val clean = flatten("clean_72", sampleCount = 2_505)
        assertThat(looksLikeQrsTrain(clean, bpm = 72.0)).isTrue()
        assertThat(looksLikeQrsTrain(noise, bpm = 72.0)).isFalse()
        assertThat(noise.contentEquals(clean)).isFalse()
    }

    @Test
    fun cleanFixtures_emitRequestedRatesWithoutLeadOff() {
        mapOf(
            "clean_40" to 40.0,
            "clean_72" to 72.0,
            "clean_120" to 120.0,
            "clean_180" to 180.0,
        ).forEach { (name, bpm) ->
            val samples = flatten(name, sampleCount = 2_505)
            assertThat(looksLikeQrsTrain(samples, bpm)).isTrue()
            assertThat(DebugReplayFixtures.batches(name, sampleCount = 15).all { it.leadOff == 0 })
                .isTrue()
        }
    }

    private fun flatten(name: String, sampleCount: Int = 75): FloatArray {
        val batches = DebugReplayFixtures.batches(name, sampleCount = sampleCount)
        val out = FloatArray(batches.sumOf { it.samplesMv.size })
        var offset = 0
        for (batch in batches) {
            batch.samplesMv.copyInto(out, offset)
            offset += batch.samplesMv.size
        }
        return out
    }

    private fun looksLikeQrsTrain(samples: FloatArray, bpm: Double, srHz: Int = 500): Boolean {
        val period = srHz * 60.0 / bpm
        var peak = period * 0.5
        var hits = 0
        var checked = 0
        while (peak < samples.size) {
            val center = peak.roundToInt()
            if (center in samples.indices) {
                val from = (center - 8).coerceAtLeast(0)
                val to = (center + 8).coerceAtMost(samples.lastIndex)
                var max = Float.NEGATIVE_INFINITY
                for (index in from..to) {
                    if (samples[index] > max) max = samples[index]
                }
                checked++
                if (max > 0.6f) hits++
            }
            peak += period
        }
        return checked >= 3 && hits * 2 >= checked
    }
}
