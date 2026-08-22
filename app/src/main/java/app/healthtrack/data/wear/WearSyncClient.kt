package app.healthtrack.data.wear

import android.content.Context
import app.healthtrack.data.protocol.EcgWearContract
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
                    "No Wear OS node for app.healthtrack. Install the GalaxyBridge watch app and keep the watch nearby."
                } else {
                    "Connected to ${nodes.size} node(s) with the same application id."
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WearLinkStatus(
                available = false,
                nodes = emptyList(),
                note = "Wearable API unavailable.",
            )
        }
    }

    suspend fun requestSyncNow(): Int {
        val nodes = nodeClient.connectedNodes.await()
        nodes.forEach { node ->
            messageClient.sendMessage(
                node.id,
                EcgWearContract.syncNowPath(node.id),
                "ok".toByteArray(),
            ).await()
        }
        return nodes.size
    }

    suspend fun sendCleanup(sessionId: String) {
        val safeSessionId = EcgWearContract.requireSessionId(sessionId)
        val nodes = nodeClient.connectedNodes.await()
        nodes.forEach { node ->
            messageClient.sendMessage(
                node.id,
                EcgWearContract.cleanupPath(safeSessionId),
                byteArrayOf(1),
            ).await()
        }
    }
}
