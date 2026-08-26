package app.galaxyvitals.wear.store

import app.galaxyvitals.data.protocol.EcgSyncSemantics
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.data.protocol.EcgCsvWriter
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WatchEcgStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun saveIsAtomicCappedAndMakesReplacementPending() {
        val dir = tmp.newFolder("store")
        val store = WatchEcgStore(dir)

        val saved = store.save("42", byteArrayOf(1, 2, 3))
        assertThat(saved.readBytes().asList())
            .containsExactly(1.toByte(), 2.toByte(), 3.toByte())
            .inOrder()
        assertThat(dir.listFiles()?.map(File::getName)).containsExactly("ecg_42.csv.gz")

        assertThat(store.markSynced("42")).isTrue()
        assertThat(store.listPendingGzipFiles()).isEmpty()
        store.save("42", byteArrayOf(4, 5))

        assertThat(store.listPendingGzipFiles().map(File::getName))
            .containsExactly("ecg_42.csv.gz")
        assertThat(dir.listFiles()?.none { it.name.endsWith(".tmp") }).isTrue()

        val error = runCatching {
            store.save("too-large", ByteArray(WatchEcgStore.MAX_GZIP_BYTES + 1))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.fileFor("too-large").exists()).isFalse()
    }

    @Test
    fun strictSessionIdsCannotEscapeStoreDirectory() {
        val store = WatchEcgStore(tmp.newFolder("strict"))

        listOf("", "../escape", "a/b", "a\\b", ".hidden", "x".repeat(129)).forEach { id ->
            val error = runCatching { store.save(id, byteArrayOf(1)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }

        val compatible = store.save("ecg_42.csv.gz", byteArrayOf(7))
        assertThat(compatible.name).isEqualTo(EcgWearContract.inboxFileName("42"))
        assertThat(store.save("session.42", byteArrayOf(8)).isFile).isTrue()
    }

    @Test
    fun exactAckMarkerExcludesOnlyMatchingPendingFile() {
        val dir = tmp.newFolder("acks")
        val store = WatchEcgStore(dir)
        store.save("1", byteArrayOf(1))
        store.save("10", byteArrayOf(10))

        assertThat(store.markSynced("1")).isTrue()

        assertThat(store.listPendingGzipFiles().map(File::getName))
            .containsExactly(EcgWearContract.inboxFileName("10"))
        assertThat(File(dir, "ecg_1.csv.gz${WatchEcgStore.SYNCED_SUFFIX}").isFile).isTrue()
        assertThat(File(dir, "ecg_10.csv.gz${WatchEcgStore.SYNCED_SUFFIX}").exists()).isFalse()
        assertThat(store.syncStatus("1")).isEqualTo(EcgSyncSemantics.ACKNOWLEDGED)
        assertThat(store.syncStatus("10")).isEqualTo(EcgSyncSemantics.QUEUED)
        assertThat(store.syncStatus("1")).isNotEqualTo(store.syncStatus("10"))
    }

    @Test
    fun pruningNeverDeletesUnacknowledgedHistory() {
        val dir = tmp.newFolder("prune")
        val store = WatchEcgStore(dir)
        repeat(10) { index ->
            val file = store.save(index.toString(), byteArrayOf(index.toByte()))
            assertThat(file.setLastModified(1_000L + index)).isTrue()
        }
        store.markSynced("0")
        store.markSynced("9")

        store.pruneAcknowledgedHistory()

        assertThat(store.fileFor("0").exists()).isFalse()
        assertThat(store.fileFor("1").exists()).isTrue()
        assertThat(store.fileFor("9").exists()).isTrue()
        assertThat(File(dir, "ecg_0.csv.gz${WatchEcgStore.SYNCED_SUFFIX}").exists()).isFalse()
        assertThat(File(dir, "ecg_9.csv.gz${WatchEcgStore.SYNCED_SUFFIX}").exists()).isTrue()
    }

    @Test
    fun deleteRemovesRecordingAndItsAckMarker() {
        val dir = tmp.newFolder("delete")
        val store = WatchEcgStore(dir)
        store.save("42", byteArrayOf(1))
        store.markSynced("42")

        assertThat(store.delete("ecg_42")).isTrue()
        assertThat(dir.listFiles()).isEmpty()
    }

    @Test
    fun deleteAllRemovesPendingAndAcknowledgedRecordings() {
        val dir = tmp.newFolder("delete-all")
        val store = WatchEcgStore(dir)
        store.save("pending", byteArrayOf(1))
        store.save("acked", byteArrayOf(2))
        store.markSynced("acked")

        assertThat(store.deleteAll()).isEqualTo(2)
        assertThat(dir.listFiles()).isEmpty()
        assertThat(store.parseAll()).isEmpty()
    }

    @Test
    fun startupCleanupRemovesOnlyExplicitDemoAndMarker() {
        val dir = tmp.newFolder("demo-cleanup")
        val hardwareBytes = EcgCsvWriter.gzipBytes(
            EcgCsvWriter.encodeCaptureV2(
                wallStartMs = 1L,
                sensorStartMs = 1L,
                valuesMv = floatArrayOf(0.1f),
                relMs = longArrayOf(0L),
                sampleFlags = intArrayOf(0),
                wrist = Wrist.LEFT,
                signFactor = 1,
                watchInfo = "watch",
                captureSource = CaptureSource.HARDWARE,
            ),
        )
        val demoText = EcgCsvWriter.encodeCaptureV2(
                wallStartMs = 1L,
                sensorStartMs = 1L,
                valuesMv = floatArrayOf(0.1f),
                relMs = longArrayOf(0L),
                sampleFlags = intArrayOf(0),
                wrist = Wrist.LEFT,
                signFactor = 1,
                watchInfo = "watch",
                captureSource = CaptureSource.HARDWARE,
            ).toString(Charsets.UTF_8)
            .replace("\"capture_source\":\"HARDWARE\"", "\"capture_source\":\"DEMO\"")
        val demoBytes = EcgCsvWriter.gzipBytes(demoText.toByteArray())
        File(dir, "ecg_demo.csv.gz").writeBytes(demoBytes)
        File(dir, "ecg_demo.csv.gz${WatchEcgStore.SYNCED_SUFFIX}").writeBytes(byteArrayOf())
        File(dir, "ecg_hardware.csv.gz").writeBytes(hardwareBytes)

        WatchEcgStore(dir)

        assertThat(File(dir, "ecg_demo.csv.gz").exists()).isFalse()
        assertThat(File(dir, "ecg_demo.csv.gz${WatchEcgStore.SYNCED_SUFFIX}").exists()).isFalse()
        assertThat(File(dir, "ecg_hardware.csv.gz").isFile).isTrue()
    }
}
