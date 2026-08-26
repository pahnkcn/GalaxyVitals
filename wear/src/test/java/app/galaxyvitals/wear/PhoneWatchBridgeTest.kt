package app.galaxyvitals.wear

import app.galaxyvitals.data.protocol.EcgCsvParser
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.capture.EcgSessionRecorder
import app.galaxyvitals.wear.store.WatchEcgStore
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Simulates the watch writer and the phone listener without Play Services.
 * This is the seam both APKs share: file name, Data Layer path, and csv+gz bytes.
 */
class PhoneWatchBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun watchRecordingIsIngestedByPhoneParser() {
        val sessionId = "1700000012345"
        val start = 1_700_000_012_345L
        val recorder = EcgSessionRecorder()
        recorder.begin(sessionId, Wrist.LEFT, 1, start)
        repeat(30) { sec ->
            val batch = FloatArray(500) { i -> syntheticSampleMv(sec * 500 + i) }
            recorder.addEcg(batch)
            recorder.addHr(start + sec * 1000L, 64 + sec)
        }
        val recorded = recorder.finish("""{"model":"WatchTest","appVersionName":"0.1.0"}""")

        val watchDir = tmp.newFolder(EcgWearContract.WATCH_DIR)
        val watchFile = WatchEcgStore(watchDir).save(sessionId, recorded.gzip)

        val phoneInbox = tmp.newFolder(EcgWearContract.INBOX_DIR)
        val phoneFile = File(phoneInbox, EcgWearContract.inboxFileName(sessionId))
        watchFile.copyTo(phoneFile, overwrite = true)

        val parsed = EcgCsvParser.parseFile(phoneFile, sessionId)
        assertThat(parsed.sessionId).isEqualTo(sessionId)
        assertThat(parsed.srHz).isEqualTo(500)
        assertThat(parsed.samples.size).isEqualTo(15_000)
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.wrist).isEqualTo(Wrist.LEFT)
        assertThat(parsed.signFactor).isEqualTo(1)
        assertThat(parsed.polarityNormalized).isFalse()
        assertThat(parsed.hrMin).isNull()
        assertThat(parsed.captureSource.name).isEqualTo("HARDWARE")
        assertThat(parsed.durationSec).isWithin(0.01).of(29.998)
        assertThat(parsed.watchInfo).contains("WatchTest")
    }

    @Test
    fun dataLayerPathsAndCleanupFilenameMatch() {
        val sessionId = "42"
        assertThat(EcgWearContract.sessionPath(sessionId)).isEqualTo("/ecg/session/42")
        assertThat(EcgWearContract.cleanupPath(sessionId)).isEqualTo("/ecg/cleanup/42")
        assertThat(EcgWearContract.ackPath(sessionId)).isEqualTo("/ecg/ack/42")
        assertThat(EcgWearContract.inboxFileName(sessionId)).isEqualTo("ecg_42.csv.gz")
        assertThat(EcgWearContract.sessionIdFromFileName("ecg_42.csv.gz")).isEqualTo("42")

        val sync = EcgWearContract.syncNowPath("node-abc")
        assertThat(sync).isEqualTo("/rpc/req/node-abc/syncNow")
        val parts = sync.split('/')
        assertThat(parts[3]).isEqualTo("node-abc")
        assertThat(parts[4]).isEqualTo("syncNow")

        val watchDir = tmp.newFolder(EcgWearContract.WATCH_DIR)
        val store = WatchEcgStore(watchDir)
        val file = store.save(sessionId, byteArrayOf(1, 2, 3))
        val cleanupId = EcgWearContract.sessionIdFromFileName(
            "/ecg/cleanup/42".removePrefix(EcgWearContract.CLEANUP_PREFIX),
        )
        assertThat(cleanupId).isEqualTo(sessionId)
        assertThat(store.markSynced(cleanupId)).isTrue()
        assertThat(file.exists()).isTrue()
        assertThat(store.listPendingGzipFiles()).isEmpty()
        assertThat(EcgWearContract.deletePath(sessionId)).isEqualTo("/ecg/delete/42")
        assertThat(store.delete(sessionId)).isTrue()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun cleanupPathWithEcgPrefixMarksExactWatchFileSynced() {
        val sessionId = "42"
        val watchDir = tmp.newFolder("prefixed")
        val store = WatchEcgStore(watchDir)
        val file = store.save(sessionId, byteArrayOf(1, 2, 3))
        val fromCompanionStyle = EcgWearContract.sessionIdFromFileName("ecg_42")
        assertThat(fromCompanionStyle).isEqualTo(sessionId)
        assertThat(store.markSynced(fromCompanionStyle)).isTrue()
        assertThat(file.exists()).isTrue()
        assertThat(store.listPendingGzipFiles()).isEmpty()
    }

    @Test
    fun rightWristRecordingPreservesRawLeadAndPhoneSeesPolarityMetadata() {
        val recorder = EcgSessionRecorder()
        recorder.begin("9", Wrist.RIGHT, EcgWearContract.signFactorFor(Wrist.RIGHT), 1000L)
        recorder.addEcg(floatArrayOf(0.4f, 0.2f, 0.1f))
        recorder.addEcg(FloatArray(EcgSessionRecorder.EXPECTED_SAMPLES - 3) { 0.05f })
        recorder.addHr(1000L, 80)
        val parsed = EcgCsvParser.parseBytes(recorder.finish("w").gzip, gzip = true, sessionIdHint = "9")
        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.wrist).isEqualTo(Wrist.RIGHT)
        assertThat(parsed.signFactor).isEqualTo(-1)
        assertThat(parsed.samples.take(3).map { it.valueMv }).containsExactly(0.4f, 0.2f, 0.1f).inOrder()
    }

    private fun syntheticSampleMv(index: Int): Float {
        val t = index / EcgWearContract.DEFAULT_SR_HZ.toDouble()
        val beat = index % EcgWearContract.DEFAULT_SR_HZ
        return if (beat in 140..155) {
            val x = (beat - 147) / 3.0
            (1.4 * exp(-x * x)).toFloat()
        } else {
            (0.08 * sin(2 * PI * t * 1.2)).toFloat()
        }
    }
}
