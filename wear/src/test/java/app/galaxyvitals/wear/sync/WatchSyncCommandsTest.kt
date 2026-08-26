package app.galaxyvitals.wear.sync

import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.wear.store.WatchEcgStore
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchSyncCommandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun phoneDeleteRemovesThatWatchCopyAndLeavesOthers() {
        val store = WatchEcgStore(tmp.newFolder("delete-one"))
        store.save("1", byteArrayOf(1))
        store.save("10", byteArrayOf(10))
        store.markSynced("1")

        val changed = recordingCommands(store).handle(EcgWearContract.deletePath("1"))

        assertThat(changed.handled).isTrue()
        assertThat(changed.storeChanged).isTrue()
        assertThat(store.fileFor("1").exists()).isFalse()
        assertThat(store.fileFor("10").isFile).isTrue()
        assertThat(store.listPendingGzipFiles().map { it.name })
            .containsExactly(EcgWearContract.inboxFileName("10"))
    }

    @Test
    fun phoneDeleteAllWipesAcknowledgedAndPendingHistory() {
        val store = WatchEcgStore(tmp.newFolder("delete-all"))
        store.save("a", byteArrayOf(1))
        store.save("b", byteArrayOf(2))
        store.markSynced("a")

        val changed = recordingCommands(store).handle(EcgWearContract.DELETE_ALL)

        assertThat(changed.handled).isTrue()
        assertThat(changed.storeChanged).isTrue()
        assertThat(store.parseAll()).isEmpty()
        assertThat(store.listPendingGzipFiles()).isEmpty()
        assertThat(store.fileFor("a").exists()).isFalse()
        assertThat(store.fileFor("b").exists()).isFalse()
    }

    @Test
    fun malformedDeleteDoesNotTouchStore() {
        val store = WatchEcgStore(tmp.newFolder("bad-delete"))
        store.save("keep", byteArrayOf(9))

        val changed = recordingCommands(store).handle("${EcgWearContract.DELETE_PREFIX}../escape")

        assertThat(changed.handled).isTrue()
        assertThat(changed.storeChanged).isFalse()
        assertThat(store.fileFor("keep").isFile).isTrue()
    }

    @Test
    fun cleanupStillOnlyMarksSynced() {
        val store = WatchEcgStore(tmp.newFolder("cleanup"))
        val file = store.save("42", byteArrayOf(3))

        val changed = recordingCommands(store).handle(EcgWearContract.cleanupPath("42"))

        assertThat(changed.handled).isTrue()
        assertThat(file.isFile).isTrue()
        assertThat(store.listPendingGzipFiles()).isEmpty()
    }

    @Test
    fun ackPathMarksExactSessionAcknowledged() {
        val store = WatchEcgStore(tmp.newFolder("ack"))
        store.save("42", byteArrayOf(3))
        store.save("420", byteArrayOf(4))

        val changed = recordingCommands(store).handle(EcgWearContract.ackPath("42"))

        assertThat(changed.handled).isTrue()
        assertThat(changed.storeChanged).isTrue()
        assertThat(store.syncStatus("42")).isEqualTo("ACKNOWLEDGED")
        assertThat(store.syncStatus("420")).isEqualTo("QUEUED")
        assertThat(store.listPendingGzipFiles().map { it.name })
            .containsExactly(EcgWearContract.inboxFileName("420"))
    }

    private fun recordingCommands(store: WatchEcgStore): Recording {
        var storeChanged = false
        val commands = WatchSyncCommands(
            store = store,
            onStoreChanged = { storeChanged = true },
        )
        return Recording(commands) { storeChanged }
    }

    private class Recording(
        private val commands: WatchSyncCommands,
        private val storeChangedFlag: () -> Boolean,
    ) {
        fun handle(path: String): Result {
            val handled = commands.handle(path)
            return Result(handled = handled, storeChanged = storeChangedFlag())
        }
    }

    private data class Result(val handled: Boolean, val storeChanged: Boolean)
}
