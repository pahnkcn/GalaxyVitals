package app.galaxyvitals.wear.ui

import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.EcgBeatDetectorConfig
import app.galaxyvitals.data.protocol.EcgBpmStatus
import app.galaxyvitals.data.protocol.EcgBeatResult
import app.galaxyvitals.data.protocol.EcgDetectorInput
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.PpgGreenBatch
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Test

class LiveBpmEstimatorTest {
    @Test
    fun sparsePpgEveryFiveEcgSamplesCorroboratesEcgBpm() {
        val ecg = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val processor = LiveEcgProcessor()
        feed(processor, ecg, syntheticSparsePpg(ecg.size, bpm = 72), batchSize = 10)

        assertThat(processor.livePpg.size).isEqualTo(ecg.size / 5)
        assertThat(processor.livePpg[1].ecgSampleIndex - processor.livePpg[0].ecgSampleIndex).isEqualTo(5L)

        val assessment = processor.estimate(nowMs = 10_000L)
        val estimate = assessment.estimate
        assertThat(estimate).isNotNull()
        assertThat(assessment.reason).isNull()
        assertThat(estimate!!.source).isEqualTo(BpmSource.APP_ECG_RR_PPG_CORROBORATED)
        assertThat(abs(estimate.bpm - 72.0)).isAtMost(4.0)
        assertThat(estimate.bpm).isWithin(0.01).of(
            EcgBeatAnalyzer.analyzeWindow(
                samplesMv = processor.conditionedSamples,
                srHz = 500,
                signFactor = 1,
                config = EcgBeatDetectorConfig.DEFAULT,
                effectiveSrHz = processor.effectiveSrHz,
                input = EcgDetectorInput.CONDITIONED,
            ).bpmMedian!!,
        )
    }

    @Test
    fun fiveAndTenSampleBatchesYieldTheSameEstimate() {
        val ecg = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val ppg = syntheticSparsePpg(ecg.size, bpm = 72)
        val byFive = LiveEcgProcessor()
        val byTen = LiveEcgProcessor()
        feed(byFive, ecg, ppg, batchSize = 5)
        feed(byTen, ecg, ppg, batchSize = 10)

        assertThat(byFive.livePpg.map { it.ecgSampleIndex })
            .isEqualTo(byTen.livePpg.map { it.ecgSampleIndex })
        assertThat(byFive.livePpg.map { it.rawValue }).isEqualTo(byTen.livePpg.map { it.rawValue })
        assertThat(byFive.analysisSamples.toList()).isEqualTo(byTen.analysisSamples.toList())

        val left = byFive.estimate(10_000L)
        val right = byTen.estimate(10_000L)
        assertThat(left).isEqualTo(right)
        assertThat(left.estimate).isNotNull()
        assertThat(left.reason).isNull()
        assertThat(left.estimate!!.source).isEqualTo(BpmSource.APP_ECG_RR_PPG_CORROBORATED)
    }

    @Test
    fun mixedFiveAndTenSampleBatchesKeepGlobalPpgIndexContinuous() {
        val processor = LiveEcgProcessor()
        processor.append(batch(sampleCount = 5, ppgOffsets = intArrayOf(0), ppgValues = intArrayOf(11)))
        processor.append(batch(sampleCount = 10, ppgOffsets = intArrayOf(0, 5), ppgValues = intArrayOf(12, 13)))
        processor.append(batch(sampleCount = 5, ppgOffsets = intArrayOf(0), ppgValues = intArrayOf(14)))

        assertThat(processor.livePpg.map { it.ecgSampleIndex }).containsExactly(0L, 5L, 10L, 15L).inOrder()
        assertThat(processor.nextEcgSampleIndex).isEqualTo(20L)
    }

    @Test
    fun ppgDisagreementAnnotatesButStillPublishesEcg() {
        val ecg = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val processor = LiveEcgProcessor()
        feed(processor, ecg, syntheticSparsePpg(ecg.size, bpm = 110), batchSize = 10)

        assertThat(LiveBpmEstimator.estimateSparsePpgBpm(processor.livePpg)).isNotNull()
        assertThat(abs(LiveBpmEstimator.estimateSparsePpgBpm(processor.livePpg)!! - 110.0)).isAtMost(6.0)
        val assessment = processor.estimate(10_000L)
        assertThat(assessment.reason).isNull()
        val estimate = assessment.estimate
        assertThat(estimate).isNotNull()
        assertThat(abs(estimate!!.bpm - 72.0)).isAtMost(4.0)
        assertThat(estimate.source).isEqualTo(BpmSource.APP_ECG_RR)
        assertThat(estimate.corroboration).isEqualTo(BpmCorroboration.DISAGREES)
        assertThat(assessment.corroboratingBpm).isNotNull()
    }

    @Test
    fun samsungIbiIsPreferredOverSparsePpgForCorroboration() {
        // 72 bpm is 833 ms; sparse green PPG here says 110 bpm.
        val ecg = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val processor = LiveEcgProcessor()
        feed(processor, ecg, syntheticSparsePpg(ecg.size, bpm = 110), batchSize = 10)

        val assessment = LiveBpmEstimator.estimate(
            analysisWindow = processor.conditionedSamples,
            livePpg = processor.livePpg,
            signFactor = processor.signFactor,
            nowMs = 10_000L,
            effectiveSrHz = processor.effectiveSrHz,
            samsungIbiMs = listOf(833, 830, 836, 833),
        )
        val estimate = assessment.estimate
        assertThat(estimate).isNotNull()
        assertThat(estimate!!.corroboration).isEqualTo(BpmCorroboration.AGREES)
        assertThat(estimate.source).isEqualTo(BpmSource.APP_ECG_RR_PPG_CORROBORATED)
        assertThat(assessment.corroboratingBpm!!).isWithin(1.0).of(72.0)
    }

    @Test
    fun samsungIbiIsIgnoredWhenTheTrackerSuppliedNone() {
        assertThat(LiveBpmEstimator.samsungIbiBpm(emptyList())).isNull()
        assertThat(LiveBpmEstimator.samsungIbiBpm(listOf(833, 830))).isNull()
        assertThat(LiveBpmEstimator.samsungIbiBpm(listOf(0, 0, 0, 0))).isNull()
    }

    @Test
    fun publishRequiresAtLeastFourRrIntervals() {
        val short = beatResult(bpm = 72.0, bSqi = 0.95, matched = 4)
        val abstained = LiveBpmEstimator.publish(short, ppgBpm = null, nowMs = 1L)
        assertThat(abstained.estimate).isNull()
        assertThat(abstained.reason).isEqualTo(BpmAbstainReason.INSUFFICIENT_RR)
        assertThat(abstained.rrCount).isEqualTo(3)

        val enough = beatResult(bpm = 72.0, bSqi = 0.95, matched = 5)
        val assessment = LiveBpmEstimator.publish(enough, ppgBpm = null, nowMs = 1L)
        assertThat(assessment.estimate).isNotNull()
        assertThat(assessment.reason).isNull()
        assertThat(assessment.estimate!!.rrCount).isEqualTo(4)
        assertThat(assessment.estimate!!.epoch).isEqualTo(BpmEpoch.CAPTURE)
        assertThat(assessment.estimate!!.source).isEqualTo(BpmSource.APP_ECG_RR)
    }

    @Test
    fun publishKeepsRequestedEpoch() {
        val ecg = beatResult(bpm = 72.0, bSqi = 0.95, matched = 8)
        val estimate = LiveBpmEstimator.publish(ecg, ppgBpm = null, nowMs = 2L, epoch = BpmEpoch.PREFLIGHT)
        assertThat(estimate.estimate!!.epoch).isEqualTo(BpmEpoch.PREFLIGHT)
    }

    @Test
    fun oneBsqiThresholdWhetherOrNotASecondSensorIsReporting() {
        val mid = beatResult(bpm = 72.0, bSqi = 0.85, matched = 8)
        val withoutPpg = LiveBpmEstimator.publish(mid, ppgBpm = null, nowMs = 1L)
        assertThat(withoutPpg.estimate).isNotNull()
        assertThat(withoutPpg.estimate!!.source).isEqualTo(BpmSource.APP_ECG_RR)
        assertThat(withoutPpg.estimate!!.bpm).isEqualTo(72.0)
        assertThat(withoutPpg.estimate!!.rrCount).isEqualTo(7)
        assertThat(withoutPpg.corroboration).isEqualTo(BpmCorroboration.UNAVAILABLE)

        val below = beatResult(bpm = 72.0, bSqi = 0.79, matched = 8)
        assertThat(LiveBpmEstimator.publish(below, ppgBpm = 72.0, nowMs = 1L).reason)
            .isEqualTo(BpmAbstainReason.LOW_BSQI)
        assertThat(LiveBpmEstimator.publish(below, ppgBpm = null, nowMs = 1L).reason)
            .isEqualTo(BpmAbstainReason.LOW_BSQI)
    }

    @Test
    fun cleanEcgWithoutPpgPublishesEcgSource() {
        val ecg = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val processor = LiveEcgProcessor()
        feed(processor, ecg, ppgValues = null, batchSize = 10)
        val assessment = processor.estimate(10_000L)
        val estimate = assessment.estimate
        assertThat(estimate).isNotNull()
        assertThat(estimate!!.source).isEqualTo(BpmSource.APP_ECG_RR)
        assertThat(estimate.bSqi!!).isAtLeast(0.90)
        assertThat(abs(estimate.bpm - 72.0)).isAtMost(4.0)
    }

    @Test
    fun bSqiBelow80DropsEvenWhenPpgAgrees() {
        val ecg = beatResult(bpm = 72.0, bSqi = 0.79, matched = 8)
        val assessment = LiveBpmEstimator.publish(ecg, ppgBpm = 72.0, nowMs = 1L)
        assertThat(assessment.estimate).isNull()
        assertThat(assessment.reason).isEqualTo(BpmAbstainReason.LOW_BSQI)
    }

    @Test
    fun corroboratedEstimateUsesEcgBpmNotPpg() {
        val ecg = beatResult(bpm = 72.0, bSqi = 0.85, matched = 9)
        val assessment = LiveBpmEstimator.publish(ecg, ppgBpm = 76.0, nowMs = 40L)
        val estimate = assessment.estimate
        assertThat(estimate).isNotNull()
        assertThat(estimate!!.source).isEqualTo(BpmSource.APP_ECG_RR_PPG_CORROBORATED)
        assertThat(estimate.bpm).isEqualTo(72.0)
        assertThat(estimate.updatedAtElapsedMs).isEqualTo(40L)
    }

    @Test
    fun aRateSittingExactlyOnABoundDoesNotFlicker() {
        // Sub-millisecond RR resolution dithers a steady 40 bpm subject by
        // fractions of a bpm; the clean_40 replay fixture abstained eight times
        // in a 30 s capture because of it.
        listOf(40.0, 39.9999, 39.6, 180.0, 180.0001, 180.4).forEach { bpm ->
            val assessment = LiveBpmEstimator.publish(
                beatResult(bpm = bpm, bSqi = 0.95, matched = 8),
                ppgBpm = null,
                nowMs = 1L,
            )
            assertThat(assessment.reason).isNull()
            assertThat(assessment.estimate).isNotNull()
        }
    }

    @Test
    fun outOfRangeBpmAbstains() {
        val high = beatResult(bpm = 181.0, bSqi = 0.95, matched = 8)
        val highAssessment = LiveBpmEstimator.publish(high, ppgBpm = null, nowMs = 1L)
        assertThat(highAssessment.estimate).isNull()
        assertThat(highAssessment.reason).isEqualTo(BpmAbstainReason.OUT_OF_RANGE)
        assertThat(highAssessment.rawBpm).isEqualTo(181.0)

        val low = beatResult(bpm = 39.0, bSqi = 0.95, matched = 8)
        val lowAssessment = LiveBpmEstimator.publish(low, ppgBpm = null, nowMs = 1L)
        assertThat(lowAssessment.estimate).isNull()
        assertThat(lowAssessment.reason).isEqualTo(BpmAbstainReason.OUT_OF_RANGE)
    }

    @Test
    fun publishNeverReturnsSilentNull() {
        val assessment = LiveBpmEstimator.publish(
            beatResult(bpm = 72.0, bSqi = 0.50, matched = 2),
            ppgBpm = null,
            nowMs = 1L,
        )
        assertThat(assessment).isNotNull()
        assertThat(assessment.estimate).isNull()
        assertThat(assessment.reason).isEqualTo(BpmAbstainReason.INSUFFICIENT_RR)
    }

    @Test
    fun sparsePpgDoesNotInterpolateMissingCadence() {
        val points = (0 until 200).map { index ->
            val t = index * 50
            val period = 500 * 60 / 72
            val phase = t % period
            val sigma = period * 0.08
            val peak = period / 5
            val gauss = exp(-((phase - peak) * (phase - peak)) / (2.0 * sigma * sigma))
            LivePpgPoint(ecgSampleIndex = t.toLong(), rawValue = (12_000 + 4_000 * gauss).toInt())
        }
        assertThat(LiveBpmEstimator.estimateSparsePpgBpm(points)).isNull()
    }

    @Test
    fun analysisWindowCapsAt10SecondsAndTrimsPpg() {
        val processor = LiveEcgProcessor()
        val ecg = syntheticQrs(seconds = 12.0, bpm = 72.0)
        feed(processor, ecg, syntheticSparsePpg(ecg.size, bpm = 72), batchSize = 10)
        assertThat(processor.analysisSamples.size).isEqualTo(LiveEcgProcessor.ANALYSIS_WINDOW_SAMPLES)
        assertThat(processor.displaySamples.size).isEqualTo(LiveEcgProcessor.DISPLAY_WINDOW_SAMPLES)
        val minIndex = processor.nextEcgSampleIndex - processor.analysisSamples.size
        assertThat(processor.livePpg.minOf { it.ecgSampleIndex }).isAtLeast(minIndex)
        assertThat(processor.livePpg.maxOf { it.ecgSampleIndex }).isLessThan(processor.nextEcgSampleIndex)
    }

    private fun beatResult(bpm: Double, bSqi: Double, matched: Int): EcgBeatResult = EcgBeatResult(
        status = if (bSqi >= 0.80) EcgBpmStatus.RELIABLE else EcgBpmStatus.DETECTOR_DISAGREEMENT,
        bpmMedian = bpm,
        primaryPeaks = IntArray(matched),
        secondaryPeaks = IntArray(matched),
        matchedPeaks = IntArray(matched),
        bSqi = bSqi,
        cleanDurationMs = 10_000L,
        reason = "",
    )

    private fun feed(
        processor: LiveEcgProcessor,
        ecg: FloatArray,
        ppgValues: IntArray?,
        batchSize: Int,
    ) {
        var offset = 0
        var sequence = 0
        while (offset < ecg.size) {
            val count = minOf(batchSize, ecg.size - offset)
            val samples = ecg.copyOfRange(offset, offset + count)
            val ppg = ppgValues?.let { values ->
                val local = ArrayList<Int>()
                val offsets = ArrayList<Int>()
                val timestamps = ArrayList<Long>()
                for (index in 0 until count) {
                    val global = offset + index
                    if (global % 5 != 0) continue
                    val ppgIndex = global / 5
                    if (ppgIndex >= values.size) continue
                    offsets += index
                    local += values[ppgIndex]
                    timestamps += 1_000L + global * 2L
                }
                if (local.isEmpty()) {
                    null
                } else {
                    PpgGreenBatch(local.toIntArray(), offsets.toIntArray(), timestamps.toLongArray())
                }
            }
            processor.append(
                EcgBatch(
                    samplesMv = samples,
                    sensorTimestampsMs = LongArray(count) { 1_000L + (offset + it) * 2L },
                    sequence = sequence and 0xff,
                    leadOff = 0,
                    minThresholdMv = -5f,
                    maxThresholdMv = 5f,
                    sampleFlags = IntArray(count),
                    ppgGreen = ppg,
                ),
            )
            sequence += 1
            offset += count
        }
    }

    private fun batch(
        sampleCount: Int,
        ppgOffsets: IntArray,
        ppgValues: IntArray,
    ): EcgBatch = EcgBatch(
        samplesMv = FloatArray(sampleCount) { 0.1f },
        sensorTimestampsMs = LongArray(sampleCount) { 1_000L + it * 2L },
        sequence = 0,
        leadOff = 0,
        minThresholdMv = -5f,
        maxThresholdMv = 5f,
        sampleFlags = IntArray(sampleCount),
        ppgGreen = PpgGreenBatch(
            values = ppgValues,
            ecgSampleOffsets = ppgOffsets,
            sensorTimestampsMs = LongArray(ppgValues.size) { 1_000L + ppgOffsets[it] * 2L },
        ),
    )

    companion object {
        fun syntheticSparsePpg(ecgSamples: Int, bpm: Int, srHz: Int = EcgWearContract.DEFAULT_SR_HZ): IntArray {
            val step = 5
            val n = (ecgSamples + step - 1) / step
            val period = srHz * 60.0 / bpm
            val peak = period / 5.0
            val sigma = period * 0.08
            return IntArray(n) { index ->
                val t = index * step
                val phase = t % period
                val gauss = exp(-((phase - peak) * (phase - peak)) / (2.0 * sigma * sigma))
                (12_000 + 4_000 * gauss).toInt()
            }
        }

        fun syntheticQrs(
            seconds: Double,
            bpm: Double,
            srHz: Int = EcgWearContract.DEFAULT_SR_HZ,
        ): FloatArray {
            val n = (seconds * srHz).roundToInt()
            val out = DoubleArray(n)
            val period = srHz * 60.0 / bpm
            var peak = period * 0.5
            while (peak < n) {
                val r = peak.roundToInt()
                addGaussian(out, r - (0.18 * srHz).roundToInt(), 0.12, 0.035 * srHz)
                addGaussian(out, r - (0.025 * srHz).roundToInt(), -0.15, 0.008 * srHz)
                addGaussian(out, r, 1.20, 0.010 * srHz)
                addGaussian(out, r + (0.025 * srHz).roundToInt(), -0.28, 0.010 * srHz)
                addGaussian(out, r + (0.22 * srHz).roundToInt(), 0.30, 0.045 * srHz)
                peak += period
            }
            for (index in out.indices) {
                val t = index.toDouble() / srHz
                out[index] += 0.04 * sin(2 * PI * 0.25 * t)
            }
            return FloatArray(n) { out[it].toFloat() }
        }

        private fun addGaussian(out: DoubleArray, center: Int, amplitude: Double, sigma: Double) {
            if (sigma <= 0.0) return
            val radius = (sigma * 4.0).roundToInt().coerceAtLeast(1)
            val twoSigmaSq = 2.0 * sigma * sigma
            for (offset in -radius..radius) {
                val index = center + offset
                if (index in out.indices) {
                    out[index] += amplitude * exp(-(offset * offset) / twoSigmaSq)
                }
            }
        }
    }
}
