package app.galaxyvitals.data.protocol

import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSampleFlags
import app.galaxyvitals.domain.SignalQualityStatus
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

class EcgBeatAnalyzerTest {
    @Test
    fun detectorConfigIsVersionedFromDevSplit() {
        val config = EcgBeatDetectorConfig.DEFAULT
        assertThat(config.version).isEqualTo(EcgBeatDetectorConfig.VERSION)
        assertThat(config.version).isEqualTo(4)
        assertThat(config.provenance).contains("physionet-dev-split-v1")
        assertThat(config.provenance).contains("minPeakToMedian")
        assertThat(config.provenance).contains("no-tile")
        assertThat(config.provenance).contains("conditioned-detector-input")
        assertThat(config.provenance).contains("subsample-rr")
        assertThat(config.matchToleranceMs).isEqualTo(50)
        assertThat(config.refineRadiusMs).isEqualTo(50)
        assertThat(config.thresholdNoiseWeight).isEqualTo(0.375)
        assertThat(config.primaryRefractoryMs).isEqualTo(300)
        assertThat(config.secondaryRefractoryMs).isEqualTo(300)
        assertThat(config.secondaryTwave).isTrue()
        assertThat(config.dualPolarity).isTrue()
        assertThat(config.minBsqi).isEqualTo(0.80)
        assertThat(config.minEnvelopeSnr).isEqualTo(3.0)
        assertThat(config.snrBypassBsqi).isEqualTo(0.95)
        assertThat(config.minPeakToMedian).isEqualTo(0.20)
        assertThat(config.provenance).doesNotContain("maxRrCv")
    }

    @Test
    fun analyzeWindowRecovers72Bpm() {
        assertWindowBpm(72.0, tolerance = 2.0)
    }

    @Test
    fun analyzeWindowRecovers72BpmAt250Hz() {
        assertWindowBpm(72.0, seconds = 16.0, tolerance = 3.0, srHz = 250)
    }

    @Test
    fun analyzeWindowRecovers72BpmAt300Hz() {
        assertWindowBpm(72.0, seconds = 16.0, tolerance = 3.0, srHz = 300)
    }

    @Test
    fun analyzeWindowRecovers40Bpm() {
        assertWindowBpm(40.0, seconds = 20.0, tolerance = 3.0)
    }

    @Test
    fun analyzeWindowRecovers60Bpm() {
        assertWindowBpm(60.0)
    }

    @Test
    fun analyzeWindowRecovers120Bpm() {
        assertWindowBpm(120.0)
    }

    @Test
    fun analyzeWindowRecovers180Bpm() {
        assertWindowBpm(180.0, tolerance = 4.0)
    }

    @Test
    fun analyzeWindowRecoversRightWristInversion() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, invert = true)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = -1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(2.0).of(72.0)
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
    }

    @Test
    fun analyzeWindowIgnoresDcOffset() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, dcOffsetMv = 100.0)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(2.0).of(72.0)
    }

    @Test
    fun analyzeWindowIgnoresTallTWave() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, tWaveMv = 0.85)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
    }

    @Test
    fun analyzeWindowMissedBeatDoesNotHalveRate() {
        val samples = syntheticQrs(seconds = 16.0, bpm = 72.0, missedBeatIndex = 5)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(4.0).of(72.0)
        assertThat(result.bpmMedian!!).isGreaterThan(50.0)
    }

    @Test
    fun analyzeWindowTallArtifactDoesNotCollapseRate() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, artifactAtSec = 3.4, artifactMv = 6.0)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(4.0).of(72.0)
    }

    @Test
    fun analyzeWindowRecoversThroughBaselineDrift() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, driftMvPerSec = 0.08)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
    }

    @Test
    fun analyzeWindowRecoversThroughNoise() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, noiseRms = 0.04)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(4.0).of(72.0)
    }

    @Test
    fun analyzeWindowKeepsReliableOnCleanVentricularBigeminy() {
        val samples = syntheticBigeminy(seconds = 16.0, shortMs = 450.0, longMs = 900.0)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isAtLeast(50.0)
        assertThat(result.bpmMedian!!).isAtMost(180.0)
        assertThat(result.matchedPeaks.size).isAtLeast(8)
        assertThat(result.reason).isNotEqualTo("RR intervals are too irregular")
    }

    @Test
    fun analyzeWindowRecoversInvertedQrsWithoutCallerSignFlip() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0, invert = true)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
        val peakValues = result.primaryPeaks.map { samples[it] }
        assertThat(peakValues.size).isAtLeast(5)
        val medianPeak = peakValues.sorted()[peakValues.size / 2]
        assertThat(medianPeak).isLessThan(-0.5f)
    }

    @Test
    fun analyzeWindowAbstainsOnLeadNoiseWithoutReportingBpm() {
        val samples = FloatArray(6_000) { index ->
            java.util.Random(index.toLong() * 17L + 3L).nextGaussian().toFloat() * 0.85f
        }
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.status).isNotEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bpmMedian).isNull()
    }

    @Test
    fun bSqiUsesUnionDenominatorNotMaxDetectorCount() {
        val samples = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)

        val denominator = result.primaryPeaks.size + result.secondaryPeaks.size - result.matchedPeaks.size
        val expected = if (denominator == 0) 0.0 else result.matchedPeaks.size.toDouble() / denominator
        assertThat(result.bSqi).isWithin(1e-12).of(expected)
        assertThat(result.bSqi).isAtLeast(0.0)
        assertThat(result.bSqi).isAtMost(1.0)

        val maxCount = maxOf(result.primaryPeaks.size, result.secondaryPeaks.size)
        if (result.primaryPeaks.size != result.secondaryPeaks.size && maxCount > 0) {
            val oldAgreement = result.matchedPeaks.size.toDouble() / maxCount
            assertThat(result.bSqi).isNotEqualTo(oldAgreement)
        }
        assertThat(denominator).isEqualTo(
            result.primaryPeaks.size + result.secondaryPeaks.size - result.matchedPeaks.size,
        )
        assertThat(result.matchedPeaks.size).isGreaterThan(0)
    }

    @Test
    fun gapDoesNotFormRrAcrossDiscontinuity() {
        val left = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val right = syntheticQrs(seconds = 10.0, bpm = 72.0)
        val gapMs = 400L
        val samples = ArrayList<EcgSample>()
        left.forEachIndexed { index, value ->
            samples += EcgSample(
                relMs = index * 2L,
                valueMv = value,
                hrBpm = null,
                sampleIndex = index,
            )
        }
        val rightStartMs = left.size * 2L + gapMs
        right.forEachIndexed { index, value ->
            samples += EcgSample(
                relMs = rightStartMs + index * 2L,
                valueMv = value,
                hrBpm = null,
                sampleIndex = left.size + index,
                flags = if (index == 0) EcgSampleFlags.TIMESTAMP_GAP else 0,
            )
        }
        val parsed = parsedRecording(samples)
        val split = left.size
        val leftSeg = ContinuousSegment(samples.subList(0, split), samples.first().relMs, samples[split - 1].relMs)
        val rightSeg = ContinuousSegment(
            samples.subList(split, samples.size),
            samples[split].relMs,
            samples.last().relMs,
        )
        val prepared = PreparedRecording(
            windows = emptyList(),
            quality = SignalQualityReport(
                status = SignalQualityStatus.GOOD,
                flags = emptySet(),
                effectiveHz = 500.0,
                gapCount = 1,
                missingSampleCount = 0,
                clippedSampleCount = 0,
                longestFlatRunMs = 0L,
                cleanCoveragePct = 100.0,
                cleanUnionMs = 20_000L,
                cleanWindowCount = 3,
                segments = listOf(leftSeg, rightSeg),
                cleanRanges = listOf(
                    leftSeg.startRelMs..leftSeg.endRelMs,
                    rightSeg.startRelMs..rightSeg.endRelMs,
                ),
            ),
        )

        val result = EcgBeatAnalyzer.analyze(parsed, prepared)
        val leftEnd = (leftSeg.endRelMs * 500L / 1_000L).toInt()
        val rightStart = (rightSeg.startRelMs * 500L / 1_000L).toInt()
        val warmupEnd = rightStart + 500
        assertThat(result.primaryPeaks.any { it <= leftEnd }).isTrue()
        assertThat(result.primaryPeaks.any { it >= warmupEnd }).isTrue()
        assertThat(result.primaryPeaks.none { it in rightStart until warmupEnd }).isTrue()
        assertThat(result.matchedPeaks.none { it in rightStart until warmupEnd }).isTrue()
        for (index in 1 until result.matchedPeaks.size) {
            val previous = result.matchedPeaks[index - 1]
            val current = result.matchedPeaks[index]
            val spansGap = previous <= leftEnd && current >= rightStart
            if (spansGap) {
                val intervalMs = (current - previous) * 1_000.0 / 500.0
                assertThat(intervalMs < 333.0 || intervalMs > 1_500.0).isTrue()
            }
        }
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.cleanDurationMs).isEqualTo(20_000L)
    }

    @Test
    fun analyzeReturnsLowQualityWhenRecordingIsUnusable() {
        val samples = List(2_500) { index ->
            EcgSample(relMs = index * 2L, valueMv = 0.02f, hrBpm = null, sampleIndex = index)
        }
        val result = EcgBeatAnalyzer.analyze(parsedRecording(samples))
        assertThat(result.status).isEqualTo(EcgBpmStatus.LOW_QUALITY)
        assertThat(result.bpmMedian).isNull()
    }

    @Test
    fun analyzePostMeasurementRecovers72BpmOnThirtySecondCapture() {
        val samples = syntheticQrs(seconds = 30.0, bpm = 72.0).toSamples()
        val result = EcgBeatAnalyzer.analyze(parsedRecording(samples))
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(2.0).of(72.0)
        assertThat(result.bSqi).isAtLeast(0.80)
        assertThat(result.cleanDurationMs).isAtLeast(20_000L)
    }

    @Test
    fun analyzePostMeasurementRecovers80BpmOnThirtySecondCapture() {
        val samples = syntheticQrs(seconds = 30.0, bpm = 80.0).toSamples()
        val result = EcgBeatAnalyzer.analyze(parsedRecording(samples))
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(80.0)
    }

    @Test
    fun analyzeWindowReportsBeatsAfterFirstTenSecondsOnSixteenSecondCapture() {
        val samples = syntheticQrs(seconds = 16.0, bpm = 72.0)
        val result = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
        val afterTenSeconds = 10 * 500
        assertThat(result.primaryPeaks.any { it >= afterTenSeconds }).isTrue()
        assertThat(result.matchedPeaks.any { it >= afterTenSeconds }).isTrue()
    }

    @Test
    fun analyzeThirtySecondCaptureDoesNotBlankPerTileWarmup() {
        val samples = syntheticQrs(seconds = 30.0, bpm = 72.0).toSamples()
        val result = EcgBeatAnalyzer.analyze(parsedRecording(samples))
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        val secondTileWarmup = 5_000 until 5_500
        val thirdTileWarmup = 10_000 until 10_500
        assertThat(result.primaryPeaks.any { it in secondTileWarmup }).isTrue()
        assertThat(result.primaryPeaks.any { it in thirdTileWarmup }).isTrue()
        assertThat(result.primaryPeaks.size).isAtLeast(32)
    }

    @Test
    fun analyzeRecoversBpmOnGappedThirtySecondCaptureWithEnoughCleanWindows() {
        val samples = gappedThirtySecondCapture(bpm = 72.0)
        val parsed = parsedRecording(samples)
        val prepared = EcgFounderPreprocess.prepare(parsed)

        assertThat(parsed.samples.any { it.flags and EcgSampleFlags.TIMESTAMP_GAP != 0 }).isTrue()
        assertThat(prepared.quality.flags).contains(QualityFlag.TIMESTAMP_GAP)
        assertThat(prepared.quality.usableForAnalysis).isFalse()
        assertThat(prepared.quality.cleanWindowCount).isAtLeast(3)
        assertThat(prepared.quality.cleanUnionMs).isAtLeast(20_000L)
        assertThat(prepared.quality.segments.size).isAtLeast(2)

        val result = EcgBeatAnalyzer.analyze(parsed)
        assertThat(result.status).isNotEqualTo(EcgBpmStatus.LOW_QUALITY)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.cleanDurationMs).isAtLeast(20_000L)
        val leftEnd = (prepared.quality.segments.first().endRelMs * 500L / 1_000L).toInt()
        val rightStart = (prepared.quality.segments.last().startRelMs * 500L / 1_000L).toInt()
        assertThat(result.primaryPeaks.any { it <= leftEnd }).isTrue()
        assertThat(result.primaryPeaks.any { it >= rightStart + 500 }).isTrue()
        assertThat(result.primaryPeaks.none { it in rightStart until rightStart + 500 }).isTrue()
        assertNonOverlapping(prepared.cleanRanges)
        assertThat(prepared.cleanRanges.size).isAtLeast(2)
    }

    @Test
    fun analyzeUsesMergedCleanRangesNotFullQualitySegments() {
        val samples = mixedRateCapture(cleanBpm = 72.0, rejectedBpm = 180.0)
        val parsed = parsedRecording(samples)
        val prepared = preparedCleanRanges(
            samples,
            listOf(0L..10_000L, 20_000L..30_000L),
        )

        assertThat(prepared.quality.segments).hasSize(1)
        assertThat(prepared.quality.segments.single().endRelMs).isAtLeast(29_000L)

        val result = EcgBeatAnalyzer.analyze(parsed, prepared)
        val rejected = 5_000 until 10_000
        assertThat(result.primaryPeaks.none { it in rejected }).isTrue()
        assertThat(result.matchedPeaks.none { it in rejected }).isTrue()
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
        assertThat(result.bpmMedian!!).isLessThan(100.0)
    }

    @Test
    fun analyzeDoesNotFormRrAcrossRejectedRange() {
        val samples = mixedRateCapture(cleanBpm = 72.0, rejectedBpm = 180.0)
        val prepared = preparedCleanRanges(
            samples,
            listOf(0L..10_000L, 20_000L..30_000L),
        )
        val result = EcgBeatAnalyzer.analyze(parsedRecording(samples), prepared)

        val leftEnd = 10_000L * 500L / 1_000L
        val rightStart = 20_000L * 500L / 1_000L
        for (index in 1 until result.matchedPeaks.size) {
            val previous = result.matchedPeaks[index - 1]
            val current = result.matchedPeaks[index]
            val spansRejected = previous <= leftEnd && current >= rightStart
            if (spansRejected) {
                val intervalMs = (current - previous) * 1_000.0 / 500.0
                assertThat(intervalMs < 333.0 || intervalMs > 1_500.0).isTrue()
            }
        }
        assertThat(result.matchedPeaks.any { it <= leftEnd }).isTrue()
        assertThat(result.matchedPeaks.any { it >= rightStart }).isTrue()
        assertThat(result.bpmMedian!!).isWithin(3.0).of(72.0)
    }

    @Test
    fun analyzeDoesNotCountDuplicateBeatsFromOverlappingWindows() {
        val values = syntheticQrs(seconds = 30.0, bpm = 72.0)
        val samples = values.toSamples()
        val parsed = parsedRecording(samples)
        val prepared = EcgFounderPreprocess.prepare(parsed)

        assertThat(prepared.quality.cleanWindowCount).isAtLeast(5)
        assertThat(prepared.cleanRanges).hasSize(1)
        assertNonOverlapping(prepared.cleanRanges)

        val result = EcgBeatAnalyzer.analyze(parsed, prepared)
        assertThat(result.matchedPeaks.toSet().size).isEqualTo(result.matchedPeaks.size)
        val fullWindow = EcgBeatAnalyzer.analyzeWindow(values, srHz = 500, signFactor = 1)
        assertThat(result.matchedPeaks.size).isAtMost(fullWindow.matchedPeaks.size + 2)
        assertThat(result.bpmMedian!!).isWithin(2.0).of(72.0)
    }

    @Test
    fun analyzeOmitsBpmWhenCleanRangesYieldTooFewRr() {
        val samples = syntheticQrs(seconds = 30.0, bpm = 72.0).toSamples()
        val prepared = preparedCleanRanges(samples, listOf(0L..1_000L))

        val result = EcgBeatAnalyzer.analyze(parsedRecording(samples), prepared)
        assertThat(result.bpmMedian).isNull()
        assertThat(result.status).isEqualTo(EcgBpmStatus.INSUFFICIENT_DATA)
    }

    @Test
    fun analyzeDoesNotMutateAlreadySavedCaptureBytes() {
        val parsed = parsedRecording(syntheticQrs(seconds = 12.0, bpm = 72.0).toSamples())
        val encoded = EcgCsvWriter.encodeParsed(parsed)
        val snapshot = parsed.samples.map { it.copy() }

        EcgBeatAnalyzer.analyze(parsed)

        assertThat(EcgCsvWriter.encodeParsed(parsed)).isEqualTo(encoded)
        assertThat(parsed.samples).isEqualTo(snapshot)
    }

    @Test
    fun delayCompensationUndoesHalfTheMovingAverageWindow() {
        // A trailing average of width W delays by (W-1)/2, not by W.
        assertThat(EcgBeatAnalyzer.delayCompensate(intArrayOf(1_000), windowWidth = 75))
            .isEqualTo(intArrayOf(1_000 - 37))
        assertThat(EcgBeatAnalyzer.delayCompensate(intArrayOf(1_000), windowWidth = 40))
            .isEqualTo(intArrayOf(1_000 - 20))
        assertThat(EcgBeatAnalyzer.delayCompensate(intArrayOf(1_000), windowWidth = 1))
            .isEqualTo(intArrayOf(1_000))
    }

    @Test
    fun delayCompensationAlsoUndoesTheQrsFilterGroupDelay() {
        // Forward-only band-pass filtering is not phase-linear; without this the
        // 150 ms and 80 ms envelopes both land ~72 ms past the R wave.
        assertThat(EcgQrsFilter.GROUP_DELAY_SAMPLES).isWithin(6.0).of(37.0)
        val primary = EcgBeatAnalyzer.delayCompensate(
            intArrayOf(1_000),
            windowWidth = 75,
            filterDelaySamples = EcgQrsFilter.GROUP_DELAY_SAMPLES,
        )
        val secondary = EcgBeatAnalyzer.delayCompensate(
            intArrayOf(1_000),
            windowWidth = 40,
            filterDelaySamples = EcgQrsFilter.GROUP_DELAY_SAMPLES,
        )
        // The two detectors must start from the same place, or the match
        // tolerance is spent before any noise arrives.
        assertThat(kotlin.math.abs(primary[0] - secondary[0])).isAtMost(20)
    }

    @Test
    fun delayCompensationDoesNotCollapseTwoBeatsIntoOne() {
        val compensated = EcgBeatAnalyzer.delayCompensate(intArrayOf(600, 1_000), windowWidth = 75)
        assertThat(compensated.size).isEqualTo(2)
        assertThat(compensated.toSet()).hasSize(2)
    }

    @Test
    fun parabolicInterpolationFindsTheSubSampleVertex() {
        // Vertex of a parabola sampled at 9, 10, 11 with its true peak at 10.25.
        val signal = FloatArray(21)
        for (index in signal.indices) {
            val offset = index - 10.25
            signal[index] = (4.0 - offset * offset).toFloat()
        }
        assertThat(EcgBeatAnalyzer.subSamplePeak(signal, 10)).isWithin(0.02).of(10.25)

        val inverted = FloatArray(signal.size) { -signal[it] }
        assertThat(EcgBeatAnalyzer.subSamplePeak(inverted, 10)).isWithin(0.02).of(10.25)

        // Never leaves its own sample: an interpolated peak more than half a
        // sample away means the wrong sample was picked, not a better estimate.
        assertThat(EcgBeatAnalyzer.subSamplePeak(floatArrayOf(0f, 1f, 0f), 1)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun refinedPeaksStayWithinFiveMsOfTruthThroughBaselineWander() {
        val srHz = 500
        val bpm = 60.0
        val samples = syntheticQrs(seconds = 14.0, bpm = bpm)
        // Wander larger than the QRS itself, which is what a wrist capture
        // actually looks like: 0.9 mV peak-to-peak under a 1.2 mV R wave.
        val wandered = FloatArray(samples.size) { index ->
            val t = index.toDouble() / srHz
            samples[index] + (0.45 * sin(2 * PI * 0.28 * t) + 0.25 * sin(2 * PI * 0.11 * t)).toFloat()
        }
        val result = EcgBeatAnalyzer.analyzeWindow(wandered, srHz = srHz, signFactor = 1)
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)

        val period = srHz * 60.0 / bpm
        val truth = generateSequence(period * 0.5) { it + period }
            .takeWhile { it < samples.size }
            .map { it.roundToInt() }
            .toList()
        val warmup = EcgQrsFilter.WARMUP_SAMPLES
        val displacements = result.primaryPeaks
            .filter { it > warmup }
            .map { peak -> truth.minOf { kotlin.math.abs(it - peak) } * 1_000.0 / srHz }
        assertThat(displacements).isNotEmpty()
        assertThat(displacements.max()).isAtMost(5.0)
    }

    @Test
    fun rrSeriesClassifiesMissedBeatsInsteadOfDroppingThem() {
        val config = EcgBeatDetectorConfig.DEFAULT
        // 60 bpm at 500 Hz: 500-sample intervals, with one beat not detected.
        val positions = doubleArrayOf(0.0, 500.0, 1_000.0, 2_000.0, 2_500.0, 3_000.0, 3_500.0)
        val series = EcgBeatAnalyzer.rrSeries(positions, config, analysisSrHz = 500.0)

        assertThat(series.nnMs).hasSize(5)
        assertThat(series.missedBeatCount).isEqualTo(1)
        assertThat(series.extraDetectionCount).isEqualTo(0)
        assertThat(series.candidateCount).isEqualTo(6)
        assertThat(series.correctedFraction).isWithin(1e-9).of(1.0 / 6.0)
        // The 2000 ms gap must not sit next to its neighbour in a difference.
        assertThat(series.nnSuccessive).containsExactly(false, true, false, true, true).inOrder()
    }

    @Test
    fun rrSeriesClassifiesAnExtraDetection() {
        val positions = doubleArrayOf(0.0, 500.0, 1_000.0, 1_250.0, 1_500.0, 2_000.0, 2_500.0)
        val series = EcgBeatAnalyzer.rrSeries(
            positions,
            EcgBeatDetectorConfig.DEFAULT,
            analysisSrHz = 500.0,
        )
        assertThat(series.extraDetectionCount).isEqualTo(2)
        assertThat(series.missedBeatCount).isEqualTo(0)
        assertThat(series.nnMs).hasSize(4)
    }

    @Test
    fun adaptivePlausibilityAcceptsBradycardiaAFixedWindowWouldReject() {
        // 42 bpm is 1429 ms - inside the old fixed 333-1500 ms window only just,
        // and the point of the adaptive check is that it tracks the subject.
        val period = 1_429.0 * 500.0 / 1_000.0
        val positions = DoubleArray(12) { it * period }
        val series = EcgBeatAnalyzer.rrSeries(
            positions,
            EcgBeatDetectorConfig.DEFAULT,
            analysisSrHz = 500.0,
        )
        assertThat(series.nnMs).hasSize(11)
        assertThat(series.correctedCount).isEqualTo(0)
    }

    @Test
    fun subSampleRrBeatsWholeSampleResolution() {
        // Peaks spaced 500.5 samples apart: unreachable without interpolation.
        val positions = DoubleArray(10) { it * 500.5 }
        val series = EcgBeatAnalyzer.rrSeries(
            positions,
            EcgBeatDetectorConfig.DEFAULT,
            analysisSrHz = 500.0,
        )
        assertThat(series.nnMs).isNotEmpty()
        series.nnMs.forEach { assertThat(it).isWithin(1e-9).of(1_001.0) }
    }

    @Test
    fun liveModeSkipsConditioningTheCallerAlreadyDid() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0)
        val conditioned = run {
            val values = DoubleArray(samples.size) { samples[it].toDouble() }
            val filtered = EcgSignalChain.filter(values, 500.0, EcgBandwidth.MONITOR, null)
            FloatArray(filtered.size) { filtered[it].toFloat() }
        }
        val preconditioned = EcgBeatAnalyzer.analyzeWindow(
            samplesMv = conditioned,
            srHz = 500,
            signFactor = 1,
            config = EcgBeatDetectorConfig.DEFAULT,
            input = EcgDetectorInput.CONDITIONED,
        )
        assertThat(preconditioned.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(preconditioned.bpmMedian!!).isWithin(2.0).of(72.0)
    }

    @Test
    fun liveRateCorrectionShiftsBpmByTheClockError() {
        val samples = syntheticQrs(seconds = 12.0, bpm = 72.0)
        val declared = EcgBeatAnalyzer.analyzeWindow(samples, srHz = 500, signFactor = 1)
        val corrected = EcgBeatAnalyzer.analyzeWindow(
            samplesMv = samples,
            srHz = 500,
            signFactor = 1,
            config = EcgBeatDetectorConfig.DEFAULT,
            effectiveSrHz = 501.67,
        )
        assertThat(declared.bpmMedian).isNotNull()
        assertThat(corrected.bpmMedian).isNotNull()
        assertThat(corrected.bpmMedian!! / declared.bpmMedian!!).isWithin(1e-4).of(501.67 / 500.0)
    }

    private fun assertWindowBpm(bpm: Double, seconds: Double = 12.0, tolerance: Double = 3.0, srHz: Int = 500) {
        val result = EcgBeatAnalyzer.analyzeWindow(syntheticQrs(seconds, bpm, srHz), srHz = srHz, signFactor = 1)
        assertThat(result.bpmMedian).isNotNull()
        assertThat(result.bpmMedian!!).isWithin(tolerance).of(bpm)
        assertThat(result.status).isEqualTo(EcgBpmStatus.RELIABLE)
        assertThat(result.bSqi).isAtLeast(0.80)
        assertThat(result.matchedPeaks.size).isAtLeast(5)
    }

    private fun syntheticBigeminy(
        seconds: Double,
        shortMs: Double,
        longMs: Double,
        srHz: Int = 500,
    ): FloatArray {
        val n = (seconds * srHz).roundToInt()
        val out = DoubleArray(n)
        var peak = 0.4 * srHz
        var shortNext = true
        while (peak < n) {
            val r = peak.roundToInt()
            addGaussian(out, r - (0.18 * srHz).roundToInt(), 0.12, 0.035 * srHz)
            addGaussian(out, r - (0.025 * srHz).roundToInt(), -0.15, 0.008 * srHz)
            addGaussian(out, r, 1.20, 0.010 * srHz)
            addGaussian(out, r + (0.025 * srHz).roundToInt(), -0.28, 0.010 * srHz)
            addGaussian(out, r + (0.22 * srHz).roundToInt(), 0.30, 0.045 * srHz)
            peak += (if (shortNext) shortMs else longMs) * srHz / 1000.0
            shortNext = !shortNext
        }
        return FloatArray(n) { out[it].toFloat() }
    }

    private fun syntheticQrs(
        seconds: Double,
        bpm: Double,
        srHz: Int = 500,
        invert: Boolean = false,
        dcOffsetMv: Double = 0.0,
        tWaveMv: Double = 0.30,
        missedBeatIndex: Int? = null,
        artifactAtSec: Double? = null,
        artifactMv: Double = 6.0,
        noiseRms: Double = 0.0,
        driftMvPerSec: Double = 0.0,
        seed: Long = 1L,
    ): FloatArray {
        val n = (seconds * srHz).roundToInt()
        val out = DoubleArray(n)
        val period = srHz * 60.0 / bpm
        var beat = 0
        var peak = period * 0.5
        while (peak < n) {
            val r = peak.roundToInt()
            if (missedBeatIndex != beat) {
                addGaussian(out, r - (0.18 * srHz).roundToInt(), 0.12, 0.035 * srHz)
                addGaussian(out, r - (0.025 * srHz).roundToInt(), -0.15, 0.008 * srHz)
                addGaussian(out, r, 1.20, 0.010 * srHz)
                addGaussian(out, r + (0.025 * srHz).roundToInt(), -0.28, 0.010 * srHz)
                addGaussian(out, r + (0.22 * srHz).roundToInt(), tWaveMv, 0.045 * srHz)
            }
            beat++
            peak += period
        }
        val rng = java.util.Random(seed)
        for (index in out.indices) {
            val t = index.toDouble() / srHz
            out[index] += 0.04 * sin(2 * PI * 0.25 * t)
            out[index] += driftMvPerSec * t
            out[index] += dcOffsetMv
            if (noiseRms > 0.0) out[index] += rng.nextGaussian() * noiseRms
        }
        artifactAtSec?.let { sec ->
            val index = (sec * srHz).roundToInt()
            if (index in out.indices) out[index] = artifactMv
        }
        val sign = if (invert) -1.0 else 1.0
        return FloatArray(n) { (sign * out[it]).toFloat() }
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

    private fun FloatArray.toSamples(): List<EcgSample> = indices.map { index ->
        EcgSample(relMs = index * 2L, valueMv = this[index], hrBpm = null, sampleIndex = index)
    }

    private fun gappedThirtySecondCapture(bpm: Double): List<EcgSample> {
        val values = syntheticQrs(seconds = 30.0, bpm = bpm)
        val split = values.size / 2
        return values.indices.map { index ->
            EcgSample(
                relMs = index * 2L,
                valueMv = values[index],
                hrBpm = null,
                sampleIndex = index,
                flags = if (index == split) EcgSampleFlags.TIMESTAMP_GAP else 0,
            )
        }
    }

    private fun mixedRateCapture(cleanBpm: Double, rejectedBpm: Double): List<EcgSample> {
        val values = syntheticQrs(seconds = 10.0, bpm = cleanBpm) +
            syntheticQrs(seconds = 10.0, bpm = rejectedBpm) +
            syntheticQrs(seconds = 10.0, bpm = cleanBpm)
        return values.toSamples()
    }

    private fun preparedCleanRanges(
        samples: List<EcgSample>,
        ranges: List<LongRange>,
    ): PreparedRecording {
        val segment = ContinuousSegment(samples, samples.first().relMs, samples.last().relMs)
        val union = SignalQualityAnalyzer.mergeRanges(ranges).sumOf { it.last - it.first }
        return PreparedRecording(
            windows = emptyList(),
            quality = SignalQualityReport(
                status = SignalQualityStatus.GOOD,
                flags = emptySet(),
                effectiveHz = 500.0,
                gapCount = 0,
                missingSampleCount = 0,
                clippedSampleCount = 0,
                longestFlatRunMs = 0L,
                cleanCoveragePct = 100.0,
                cleanUnionMs = maxOf(union, 20_000L),
                cleanWindowCount = 3,
                segments = listOf(segment),
                cleanRanges = ranges,
            ),
        )
    }

    private fun assertNonOverlapping(ranges: List<LongRange>) {
        for (index in 1 until ranges.size) {
            assertThat(ranges[index].first).isAtLeast(ranges[index - 1].last)
        }
    }

    private fun parsedRecording(samples: List<EcgSample>): ParsedEcgFile = EcgCsvParser.summarize(
        sessionId = "beat-analyzer",
        srHz = 500,
        unit = "mV",
        tsStartMs = 1L,
        wrist = Wrist.LEFT,
        signFactor = 1,
        polarityNormalized = false,
        watchInfo = "test",
        samples = samples,
        schemaVersion = 2,
        captureSource = CaptureSource.HARDWARE,
        timingTrust = TimingTrust.SENSOR,
    )
}
