package app.galaxyvitals.data

import app.galaxyvitals.data.protocol.EcgCsvParser
import app.galaxyvitals.data.protocol.EcgCsvWriter
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.LiveBpmObservation
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EcgWearAckTest {
    @Test
    fun v3GzipHashMustMatchStoredBytesBeforeAck() {
        val incoming = v3Gzip()
        val sha256 = EcgWearContract.sha256(incoming)
        val parsed = EcgCsvParser.parseBytes(incoming, gzip = true, sessionIdHint = "ack-v3")
        val reencoded = EcgCsvWriter.gzipBytes(EcgCsvWriter.encodeParsed(parsed))

        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(EcgWearContract.mayAcknowledgeStoredPayload(incoming, incoming, sha256)).isTrue()
        assertThat(
            EcgWearContract.mayAcknowledgeStoredPayload(
                incoming,
                incoming.copyOf(incoming.size - 1),
                sha256,
            ),
        ).isFalse()
        assertThat(
            EcgWearContract.mayAcknowledgeStoredPayload(incoming, incoming, "0".repeat(64)),
        ).isFalse()

        if (!reencoded.contentEquals(incoming)) {
            assertThat(
                EcgWearContract.mayAcknowledgeStoredPayload(incoming, reencoded, sha256),
            ).isFalse()
        }
    }

    @Test
    fun ingestOfV3KeepsIncomingBytesNotReencodedCanonicalForm() {
        val incoming = v3Gzip()
        val parsed = EcgCsvParser.parseBytes(incoming, gzip = true, sessionIdHint = "opaque-v3")
        val stored = EcgWearContract.bytesToPersist(
            schemaVersion = parsed.schemaVersion,
            incomingGzip = incoming,
            canonicalGzip = EcgCsvWriter.gzipBytes(EcgCsvWriter.encodeParsed(parsed)),
        )
        assertThat(stored.asList()).isEqualTo(incoming.asList())
        assertThat(EcgWearContract.sha256(stored)).isEqualTo(EcgWearContract.sha256(incoming))
        assertThat(
            EcgWearContract.mayAcknowledgeStoredPayload(
                incoming,
                stored,
                EcgWearContract.sha256(incoming),
            ),
        ).isTrue()
    }

    private fun v3Gzip(): ByteArray = EcgCsvWriter.gzipBytes(
        EcgCsvWriter.encodeCaptureV3(
            wallStartMs = 1_700_000_000_000L,
            sensorStartMs = 1_000L,
            valuesMv = floatArrayOf(-0.12f, -0.11f, 0.4f),
            sampleFlags = intArrayOf(0, 0, 0),
            sensorTimestampsMsRaw = longArrayOf(1_000L, 1_000L, 1_002L),
            batchSequence = intArrayOf(0, 0, 1),
            batchSampleOffset = intArrayOf(0, 1, 0),
            batchSize = intArrayOf(2, 2, 1),
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = """{"sensorSdk":"1.4.1"}""",
            captureSource = CaptureSource.HARDWARE,
            bpmObservations = listOf(
                LiveBpmObservation(
                    atSampleIndex = 2,
                    observedCaptureElapsedMs = 4,
                    status = "RELIABLE",
                    displayedBpm = 88.0,
                    rawBpm = 88.2,
                    source = "APP_ECG_RR",
                    bSqi = 0.91,
                    rrCount = 5,
                    estimateAgeMs = 100L,
                ),
            ),
        ),
    )
}
