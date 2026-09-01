package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sqrt

class EcgHrvAnalyzerTest {
    @Test
    fun timeDomainMetricsMatchTheirDefinitions() {
        val nn = List(40) { index -> if (index % 2 == 0) 900.0 else 1_000.0 }
        val result = EcgHrvAnalyzer.analyze(series(nn), analysedDurationMs = 40_000L)

        assertThat(result.status).isEqualTo(EcgHrvStatus.RELIABLE)
        assertThat(result.meanHrBpm!!).isWithin(0.01).of(60_000.0 / 950.0)
        // Alternating +-50 about the mean: SDNN is the sample SD of that.
        val expectedSdnn = sqrt(40 * 50.0 * 50.0 / 39.0)
        assertThat(result.sdnnMs!!).isWithin(0.01).of(expectedSdnn)
        // Every successive difference is 100 ms.
        assertThat(result.rmssdMs!!).isWithin(0.01).of(100.0)
        assertThat(result.pnn50Pct!!).isWithin(0.01).of(100.0)
        assertThat(result.nnCount).isEqualTo(40)
        assertThat(result.successivePairCount).isEqualTo(39)
    }

    @Test
    fun aPerfectlyRegularRhythmHasNoVariability() {
        val result = EcgHrvAnalyzer.analyze(series(List(40) { 1_000.0 }), analysedDurationMs = 40_000L)
        assertThat(result.status).isEqualTo(EcgHrvStatus.RELIABLE)
        assertThat(result.sdnnMs!!).isWithin(1e-9).of(0.0)
        assertThat(result.rmssdMs!!).isWithin(1e-9).of(0.0)
        assertThat(result.pnn50Pct!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun differencesAreNotTakenAcrossARemovedArtifact() {
        // Two clean runs of 20 with a corrected interval between them. The step
        // from 800 to 1000 must not become a 200 ms successive difference.
        val nn = List(20) { 800.0 } + List(20) { 1_000.0 }
        val successive = List(40) { index -> index != 0 && index != 20 }
        val result = EcgHrvAnalyzer.analyze(
            EcgRrSeries(
                allMs = nn,
                nnMs = nn,
                nnSuccessive = successive,
                missedBeatCount = 1,
                extraDetectionCount = 0,
                implausibleCount = 0,
                candidateCount = 41,
            ),
            analysedDurationMs = 40_000L,
        )
        assertThat(result.status).isEqualTo(EcgHrvStatus.RELIABLE)
        assertThat(result.successivePairCount).isEqualTo(38)
        assertThat(result.rmssdMs!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun abstainsWhenTooManyIntervalsHadToBeCorrected() {
        val nn = List(30) { 1_000.0 }
        val result = EcgHrvAnalyzer.analyze(
            EcgRrSeries(
                allMs = nn,
                nnMs = nn,
                nnSuccessive = List(30) { it != 0 },
                missedBeatCount = 10,
                extraDetectionCount = 0,
                implausibleCount = 0,
                candidateCount = 40,
            ),
            analysedDurationMs = 60_000L,
        )
        assertThat(result.status).isEqualTo(EcgHrvStatus.TOO_MANY_CORRECTIONS)
        assertThat(result.sdnnMs).isNull()
        assertThat(result.rmssdMs).isNull()
        assertThat(result.correctedRrFraction).isWithin(1e-9).of(0.25)
        assertThat(result.reason).isNotEmpty()
    }

    @Test
    fun abstainsOnTooFewIntervals() {
        val nn = List(10) { 1_000.0 }
        val result = EcgHrvAnalyzer.analyze(series(nn), analysedDurationMs = 30_000L)
        assertThat(result.status).isEqualTo(EcgHrvStatus.INSUFFICIENT_DATA)
        assertThat(result.meanHrBpm).isNull()
        assertThat(result.nnCount).isEqualTo(10)
    }

    @Test
    fun abstainsWhenAcceptedIntervalsCoverTooLittleOfTheRecording() {
        val nn = List(25) { 1_000.0 }
        val result = EcgHrvAnalyzer.analyze(series(nn), analysedDurationMs = 60_000L)
        assertThat(result.status).isEqualTo(EcgHrvStatus.INSUFFICIENT_DATA)
        assertThat(result.coveragePct).isWithin(0.01).of(25_000.0 * 100.0 / 60_000.0)
    }

    @Test
    fun abstainsWhenTheBeatDetectorItselfDidNot() {
        val nn = List(40) { 1_000.0 }
        val result = EcgHrvAnalyzer.analyze(
            series(nn),
            analysedDurationMs = 40_000L,
            beatStatus = EcgBpmStatus.DETECTOR_DISAGREEMENT,
        )
        assertThat(result.status).isEqualTo(EcgHrvStatus.LOW_QUALITY)
    }

    @Test
    fun readsTheRrSeriesStraightOffABeatResult() {
        val nn = List(40) { 1_000.0 }
        val beat = EcgBeatResult(
            status = EcgBpmStatus.RELIABLE,
            bpmMedian = 60.0,
            primaryPeaks = IntArray(41),
            secondaryPeaks = IntArray(41),
            matchedPeaks = IntArray(41),
            bSqi = 1.0,
            cleanDurationMs = 45_000L,
            reason = "",
            rr = series(nn),
        )
        val result = EcgHrvAnalyzer.analyze(beat)
        assertThat(result.status).isEqualTo(EcgHrvStatus.RELIABLE)
        assertThat(result.analysedDurationMs).isEqualTo(45_000L)
    }

    private fun series(nn: List<Double>) = EcgRrSeries(
        allMs = nn,
        nnMs = nn,
        nnSuccessive = List(nn.size) { it != 0 },
        missedBeatCount = 0,
        extraDetectionCount = 0,
        implausibleCount = 0,
        candidateCount = nn.size,
    )
}
