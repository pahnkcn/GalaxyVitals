package app.galaxyvitals.export

import app.galaxyvitals.analysis.EcgAnalysisFixtures
import app.galaxyvitals.data.protocol.EcgBandwidth
import app.galaxyvitals.data.protocol.StripSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EcgReportBuilderTest {

    @Test
    fun aCleanRecordingReportsRateRrAndHrv() {
        val report = build(EcgAnalysisFixtures.clean72BpmRecording())

        val rate = report.measurement(MeasurementKey.HEART_RATE)!!
        assertThat(rate.isAvailable).isTrue()
        assertThat(rate.value!!).isWithin(3.0).of(72.0)

        val rr = report.measurement(MeasurementKey.RR_MEAN)!!
        assertThat(rr.value!!).isWithin(30.0).of(60_000.0 / 72.0)

        assertThat(report.verdict.rateStatus).isEqualTo(RateStatus.RELIABLE)
        assertThat(report.verdict.hrvStatus).isEqualTo(HrvStatus.RELIABLE)
        listOf(MeasurementKey.SDNN, MeasurementKey.RMSSD, MeasurementKey.PNN50).forEach { key ->
            assertThat(report.measurement(key)!!.isAvailable).isTrue()
        }
    }

    @Test
    fun heartRateRangeRunsSlowestToFastest() {
        val report = build(EcgAnalysisFixtures.clean72BpmRecording())

        val range = report.measurement(MeasurementKey.HEART_RATE_RANGE)!!

        assertThat(range.value!!).isAtMost(range.spread!!)
        assertThat(range.value!!).isGreaterThan(30.0)
        assertThat(range.spread!!).isLessThan(220.0)
    }

    @Test
    fun rPeaksAreMillisecondsInsideTheRecordingAndMatchTheBeatCount() {
        val report = build(EcgAnalysisFixtures.clean72BpmRecording())

        val peaks = report.beats.rPeaksMs
        val beatCount = report.measurement(MeasurementKey.BEAT_COUNT)!!.value!!.toInt()

        assertThat(peaks).hasSize(beatCount)
        assertThat(peaks).isInOrder()
        assertThat(peaks.first()).isAtLeast(0.0)
        assertThat(peaks.last()).isLessThan(report.header.durationSec * 1000.0)
        // 30 s at 72 bpm is 36 beats; allow the detector its edge beats.
        assertThat(peaks.size).isIn(32..40)
    }

    @Test
    fun aContaminatedRecordingSurfacesQualityFlagsAndShrinksTheAnalysedWindow() {
        val report = build(EcgAnalysisFixtures.contaminated30sRecording())

        assertThat(report.quality.flags).isNotEmpty()
        val analysed = report.measurement(MeasurementKey.ANALYSED_DURATION)!!
        assertThat(analysed.value!!).isLessThan(analysed.spread!!)
        assertThat(report.quality.noisyRangesMs).isNotEmpty()
    }

    @Test
    fun aFlatRecordingReportsWhyTheNumbersAreMissingRatherThanGuessing() {
        val report = build(EcgAnalysisFixtures.lowQualityRecording())

        val rate = report.measurement(MeasurementKey.HEART_RATE)!!
        assertThat(rate.isAvailable).isFalse()
        assertThat(rate.availability).isNotEqualTo(MetricAvailability.AVAILABLE)
        assertThat(report.measurement(MeasurementKey.SDNN)!!.isAvailable).isFalse()
    }

    @Test
    fun rawSamplesAreNeverTheFilteredOnes() {
        val parsed = EcgAnalysisFixtures.clean72BpmRecording()

        val report = build(parsed)

        assertThat(report.rawSamples).hasSize(parsed.samples.size)
        assertThat(report.displaySamples).hasSize(parsed.samples.size)
        assertThat(report.rawSamples).isEqualTo(parsed.samples)
        assertThat(report.displaySamples).isNotEqualTo(parsed.samples)
    }

    @Test
    fun theReportDefaultsToDiagnosticBandwidthAndStandardPaperScale() {
        val report = build(EcgAnalysisFixtures.clean72BpmRecording())

        assertThat(report.header.bandwidth).isEqualTo(EcgBandwidth.DIAGNOSTIC)
        assertThat(report.header.speedMmPerSec).isEqualTo(25.0)
        assertThat(report.header.gainMmPerMv).isEqualTo(10.0)
        assertThat(report.header.effectiveSrHz).isWithin(5.0).of(500.0)
    }

    @Test
    fun aNoteIsCarriedTrimmedAndIsEmptyWhenNotGiven() {
        val withNote = build(EcgAnalysisFixtures.clean72BpmRecording(), note = "  chest tightness  ")

        assertThat(withNote.header.note).isEqualTo("chest tightness")
        assertThat(build(EcgAnalysisFixtures.clean72BpmRecording()).header.note).isEmpty()
    }

    @Test
    fun noisyRangesAreTheComplementOfTheCleanOnes() {
        assertThat(EcgReportBuilder.complementOf(emptyList(), 30_000L))
            .containsExactly(0L..30_000L)
        assertThat(EcgReportBuilder.complementOf(listOf(0L..30_000L), 30_000L)).isEmpty()
        assertThat(EcgReportBuilder.complementOf(listOf(0L..10_000L, 20_000L..30_000L), 30_000L))
            .containsExactly(10_000L..20_000L)
        // Overlapping clean ranges merge instead of producing a phantom gap.
        assertThat(EcgReportBuilder.complementOf(listOf(0L..15_000L, 10_000L..30_000L), 30_000L))
            .isEmpty()
        assertThat(EcgReportBuilder.complementOf(listOf(5_000L..25_000L), 30_000L))
            .containsExactly(0L..5_000L, 25_000L..30_000L)
            .inOrder()
    }

    private fun build(
        parsed: app.galaxyvitals.data.protocol.ParsedEcgFile,
        note: String = "",
    ) = EcgReportBuilder.build(
        parsed = parsed,
        session = null,
        appVersion = "test",
        spec = StripSpec(),
        note = note,
    )
}
