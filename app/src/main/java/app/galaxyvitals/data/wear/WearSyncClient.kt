package app.galaxyvitals.data.wear

import android.content.Context
import app.galaxyvitals.R
import app.galaxyvitals.data.protocol.EcgWearContract
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

data class WearLinkStatus(
    val available: Boolean,
    val nodes: List<String>,
    val note: String,
)

class WearSyncClient(context: Context) {
    private val appContext = context.applicationContext
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)

    suspend fun status(): WearLinkStatus {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            WearLinkStatus(
                available = nodes.isNotEmpty(),
                nodes = nodes.map(Node::getDisplayName),
                note = if (nodes.isEmpty()) {
                    appContext.getString(R.string.wear_no_node)
                } else {
                    appContext.getString(R.string.wear_connected, nodes.size)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WearLinkStatus(
                available = false,
                nodes = emptyList(),
                note = appContext.getString(R.string.wear_api_unavailable),
            )
        }
    }

    suspend fun requestSyncNow(): Int {
        val nodes = nodeClient.connectedNodes.await()
        nodes.forEach { node ->
            send(node, EcgWearContract.syncNowPath(node.id), "ok".toByteArray())
        }
        return nodes.size
    }

    suspend fun sendAck(sessionId: String) {
        sendToConnected(
            EcgWearContract.ackPath(EcgWearContract.requireSessionId(sessionId)),
            byteArrayOf(1),
        )
    }

    suspend fun sendCleanup(sessionId: String) {
        sendToConnected(
            EcgWearContract.cleanupPath(EcgWearContract.requireSessionId(sessionId)),
            byteArrayOf(1),
        )
    }

    suspend fun sendDelete(sessionId: String) {
        sendToConnected(
            EcgWearContract.deletePath(EcgWearContract.requireSessionId(sessionId)),
            deletePayload(),
        )
    }

    suspend fun sendDeleteAll() {
        sendToConnected(EcgWearContract.DELETE_ALL, deletePayload())
    }

    private suspend fun sendToConnected(path: String, payload: ByteArray) {
        nodeClient.connectedNodes.await().forEach { node -> send(node, path, payload) }
    }

    private suspend fun send(node: Node, path: String, payload: ByteArray) {
        messageClient.sendMessage(node.id, path, payload).await()
    }

    private fun deletePayload(): ByteArray =
        """{"ts":${System.currentTimeMillis()}}""".toByteArray()
}
