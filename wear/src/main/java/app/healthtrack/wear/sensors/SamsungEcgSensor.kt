package app.healthtrack.wear.sensors

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.healthtrack.domain.EcgSampleFlags
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class SamsungEcgSensor(context: Context) : EcgSensor {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val acquisition = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "samsung-ecg-acquisition").apply { isDaemon = true }
    }
    private var host = WeakReference<Activity>(null)

    fun attach(activity: Activity) {
        host = WeakReference(activity)
    }
    private val connectionLock = Any()
    @Volatile
    private var service: HealthTrackingService? = null
    @Volatile
    private var connectionToken = 0L
    private var ecgTracker: HealthTracker? = null
    private var subscriptionEpoch = 0L
    private var activeSubscriptionEpoch = 0L

    override fun connect(onResult: (SensorAvailability) -> Unit) {
        val hasExistingConnection = synchronized(connectionLock) {
            service != null || ecgTracker != null || activeSubscriptionEpoch != 0L
        }
        if (hasExistingConnection) disconnect()
        val token = synchronized(connectionLock) {
            connectionToken += 1
            connectionToken
        }
        lateinit var candidate: HealthTrackingService
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                if (!isCurrentConnection(candidate, token)) {
                    disconnectStale(candidate)
                    return
                }
                val supported = try {
                    candidate.getTrackingCapability().getSupportHealthTrackerTypes()
                } catch (error: RuntimeException) {
                    deliverResult(
                        candidate,
                        token,
                        onResult,
                        unavailable(error.message ?: "Unable to read tracker capabilities."),
                    )
                    return
                }
                if (!supported.contains(HealthTrackerType.ECG_ON_DEMAND)) {
                    deliverResult(
                        candidate,
                        token,
                        onResult,
                        SensorAvailability(
                            ready = false,
                            reason = "ECG_ON_DEMAND is not available for ${app.packageName}.",
                            policyDenied = true,
                        ),
                    )
                    return
                }
                val candidateEcg: HealthTracker
                try {
                    candidateEcg = candidate.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND)
                } catch (error: RuntimeException) {
                    deliverResult(
                        candidate,
                        token,
                        onResult,
                        unavailable(error.message ?: "Unable to create Samsung trackers."),
                    )
                    return
                }
                val installed = synchronized(connectionLock) {
                    if (connectionToken == token && service === candidate) {
                        ecgTracker = candidateEcg
                        true
                    } else {
                        false
                    }
                }
                if (!installed) {
                    disconnectStale(candidate)
                    return
                }
                deliverResult(
                    candidate,
                    token,
                    onResult,
                    SensorAvailability(ready = true),
                )
            }

            override fun onConnectionEnded() {
                main.post {
                    if (isCurrentConnection(candidate, token)) {
                        onResult(unavailable("Samsung Health connection ended."))
                    }
                }
            }

            override fun onConnectionFailed(exception: HealthTrackerException) {
                // Never auto-resolve. resolve() opens Play Store / account setup on
                // emulator and non-Samsung watches, covering the measure UI.
                deliverResult(
                    candidate,
                    token,
                    onResult,
                    SensorAvailability(
                        ready = false,
                        reason = exception.message ?: "Samsung Health connection failed.",
                        policyDenied = true,
                    ),
                )
            }
        }
        candidate = HealthTrackingService(listener, app)
        val accepted = synchronized(connectionLock) {
            if (connectionToken == token && service == null) {
                service = candidate
                true
            } else {
                false
            }
        }
        if (!accepted) {
            disconnectStale(candidate)
            return
        }
        candidate.connectService()
    }

    override fun startEcg(
        onError: (EcgSensorError) -> Unit,
        onBatch: (EcgBatch) -> Unit,
    ): EcgSubscription {
        val tracker = synchronized(connectionLock) { ecgTracker }
        if (tracker == null) {
            onError(EcgSensorError(EcgSensorErrorCode.NOT_CONNECTED, "Samsung ECG is not connected."))
            return EcgSubscription { }
        }
        val epoch = synchronized(connectionLock) {
            check(activeSubscriptionEpoch == 0L) { "Samsung ECG listener is already active." }
            ++subscriptionEpoch
            activeSubscriptionEpoch = subscriptionEpoch
            subscriptionEpoch
        }
        val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(data: List<DataPoint>) {
                    if (data.isEmpty() || !isCurrentSubscription(epoch)) return
                    val batch = try {
                        mapBatch(data)
                    } catch (error: Exception) {
                        deliverSubscriptionError(
                            epoch,
                            onError,
                            EcgSensorError(
                                EcgSensorErrorCode.INVALID_BATCH,
                                error.message ?: "Samsung returned an invalid ECG batch.",
                            ),
                        )
                        return
                    }
                    acquisition.execute {
                        if (!isCurrentSubscription(epoch)) return@execute
                        main.post {
                            if (isCurrentSubscription(epoch)) onBatch(batch)
                        }
                    }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    val code = if (error == HealthTracker.TrackerError.SDK_POLICY_ERROR) {
                        EcgSensorErrorCode.SDK_POLICY
                    } else {
                        EcgSensorErrorCode.TRACKER
                    }
                    deliverSubscriptionError(
                        epoch,
                        onError,
                        EcgSensorError(code, "Samsung ECG tracker error: $error"),
                    )
                }
            }
        try {
            tracker.setEventListener(listener)
        } catch (error: Exception) {
            synchronized(connectionLock) {
                if (activeSubscriptionEpoch == epoch) activeSubscriptionEpoch = 0L
            }
            onError(
                EcgSensorError(
                    EcgSensorErrorCode.START_FAILED,
                    error.message ?: "Samsung ECG listener could not start.",
                ),
            )
        }
        return EcgSubscription { closeSubscription(epoch) }
    }

    override fun stop() {
        val listeners = takeActiveListener()
        stopListeners(listeners)
    }

    override fun disconnect() {
        val connection = synchronized(connectionLock) {
            connectionToken += 1
            ConnectionHandles(
                service = service,
                listeners = ListenerHandles(ecg = ecgTracker.takeIf { activeSubscriptionEpoch != 0L }),
            ).also {
                service = null
                ecgTracker = null
                activeSubscriptionEpoch = 0L
                subscriptionEpoch += 1
            }
        }
        stopListeners(connection.listeners)
        try {
            connection.service?.disconnectService()
        } catch (_: Exception) {
            // Already disconnected or superseded.
        }
    }

    private fun isCurrentConnection(candidate: HealthTrackingService, token: Long): Boolean =
        synchronized(connectionLock) {
            connectionToken == token && service === candidate
        }

    private fun isCurrentSubscription(epoch: Long): Boolean = synchronized(connectionLock) {
        activeSubscriptionEpoch == epoch
    }

    private fun deliverSubscriptionError(
        epoch: Long,
        onError: (EcgSensorError) -> Unit,
        error: EcgSensorError,
    ) {
        acquisition.execute {
            if (!isCurrentSubscription(epoch)) return@execute
            main.post {
                if (isCurrentSubscription(epoch)) onError(error)
            }
        }
    }

    private fun closeSubscription(epoch: Long) {
        val tracker = synchronized(connectionLock) {
            if (activeSubscriptionEpoch != epoch) return
            activeSubscriptionEpoch = 0L
            subscriptionEpoch += 1
            ecgTracker
        }
        try {
            tracker?.unsetEventListener()
        } catch (_: Exception) {
            // Listener may already be unset after an SDK error.
        }
    }

    private fun takeActiveListener(): ListenerHandles = synchronized(connectionLock) {
        ListenerHandles(ecg = ecgTracker.takeIf { activeSubscriptionEpoch != 0L }).also {
            activeSubscriptionEpoch = 0L
            subscriptionEpoch += 1
        }
    }

    private fun deliverResult(
        candidate: HealthTrackingService,
        token: Long,
        onResult: (SensorAvailability) -> Unit,
        result: SensorAvailability,
    ) {
        main.post {
            if (isCurrentConnection(candidate, token)) {
                onResult(result)
            } else {
                disconnectStale(candidate)
            }
        }
    }

    private fun disconnectStale(candidate: HealthTrackingService) {
        try {
            candidate.disconnectService()
        } catch (_: Exception) {
            // Stale attempts may already be disconnected.
        }
    }

    private fun stopListeners(listeners: ListenerHandles) {
        try {
            listeners.ecg?.unsetEventListener()
        } catch (_: Exception) {
            // Listener may already be unset.
        }
    }

    private fun mapBatch(data: List<DataPoint>): EcgBatch {
        require(data.isNotEmpty()) { "Empty Samsung ECG batch" }
        val first = data.first()
        val leadOff = first.getValue(ValueKey.EcgSet.LEAD_OFF)
        val sequence = first.getValue(ValueKey.EcgSet.SEQUENCE).toInt() and 0xff
        val minThreshold = first.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV)
        val maxThreshold = first.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV)
        require(minThreshold.isFinite() && maxThreshold.isFinite() && minThreshold < maxThreshold) {
            "Invalid Samsung ECG thresholds"
        }
        val samples = FloatArray(data.size)
        val timestamps = LongArray(data.size)
        val flags = IntArray(data.size)
        data.forEachIndexed { index, point ->
            val value = point.getValue(ValueKey.EcgSet.ECG_MV)
            require(value.isFinite()) { "Non-finite Samsung ECG sample" }
            samples[index] = value
            timestamps[index] = point.timestamp
            var sampleFlags = EcgSampleFlags.NONE
            if (leadOff != 0) sampleFlags = sampleFlags or EcgSampleFlags.CONTACT_LOSS
            if (value < minThreshold || value > maxThreshold) {
                sampleFlags = sampleFlags or EcgSampleFlags.CLIPPED
            }
            flags[index] = sampleFlags
        }
        return EcgBatch(
            samplesMv = samples,
            sensorTimestampsMs = timestamps,
            sequence = sequence,
            leadOff = leadOff,
            minThresholdMv = minThreshold,
            maxThresholdMv = maxThreshold,
            sampleFlags = flags,
        )
    }

    private fun unavailable(reason: String) = SensorAvailability(
        ready = false,
        reason = reason,
        policyDenied = true,
    )

    private data class ListenerHandles(
        val ecg: HealthTracker?,
    )

    private data class ConnectionHandles(
        val service: HealthTrackingService?,
        val listeners: ListenerHandles,
    )
}
