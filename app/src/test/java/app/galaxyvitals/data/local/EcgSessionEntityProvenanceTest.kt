package app.galaxyvitals.data.local

import app.galaxyvitals.data.protocol.LiveBpmSummarizer
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.EcgSample
import app.galaxyvitals.domain.EcgSource
import app.galaxyvitals.domain.TimingTrust
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EcgSessionEntityProvenanceTest {
    @Test
    fun fromParsedStoresLiveBpmSeparatelyFromHrMedian() {
        val parsed = ParsedEcgFile(
            sessionId = "prov-1",
            srHz = 500,
            unit = "mV",
            tsStartMs = 1L,
            wrist = Wrist.LEFT,
            signFactor = 1,
            polarityNormalized = false,
            watchInfo = "watch",
            samples = listOf(
                EcgSample(relMs = 0L, valueMv = 0.1f, hrBpm = 64, sampleIndex = 0),
            ),
            hrMedian = 64.0,
            hrMin = 64,
            hrMax = 64,
            hrCoveragePct = 100.0,
            usablePct = 100.0,
            durationSec = 0.0,
            schemaVersion = 3,
            captureSource = CaptureSource.HARDWARE,
            timingTrust = TimingTrust.SEQUENCE_RECONSTRUCTED,
            rawTimingTrust = TimingTrust.UNVERIFIED,
            liveBpmMedian = 88.0,
            liveBpmMin = 80.0,
            liveBpmMax = 96.0,
            liveBpmReliableCoveragePct = 42.5,
            liveBpmAlgorithmId = LiveBpmSummarizer.ALGORITHM_ID,
            liveBpmObservationCount = 7,
        )

        val entity = EcgSessionEntity.from(
            parsed = parsed,
            filePath = "ecg_prov-1.csv.gz",
            source = EcgSource.WEAR,
            now = 9L,
            payloadSha256 = "ab".repeat(32),
        )
        val domain = entity.toDomain()

        assertThat(entity.hrMedian).isEqualTo(64.0)
        assertThat(entity.liveBpmMedian).isEqualTo(88.0)
        assertThat(entity.liveBpmMin).isEqualTo(80.0)
        assertThat(entity.liveBpmMax).isEqualTo(96.0)
        assertThat(entity.liveBpmReliableCoveragePct).isEqualTo(42.5)
        assertThat(entity.liveBpmAlgorithmId).isEqualTo(LiveBpmSummarizer.ALGORITHM_ID)
        assertThat(entity.liveBpmObservationCount).isEqualTo(7)
        assertThat(entity.rawTimingTrust).isEqualTo(TimingTrust.UNVERIFIED.name)
        assertThat(entity.timingTrust).isEqualTo(TimingTrust.SEQUENCE_RECONSTRUCTED.name)
        assertThat(domain.hrMedian).isEqualTo(64.0)
        assertThat(domain.liveBpmMedian).isEqualTo(88.0)
        assertThat(domain.ecgHrMedian).isNull()
    }
}
