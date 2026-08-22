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
    override val kind: SensorKind = SensorKind.SAMSUNG

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
    private var ecgListening = false

    override fun connect(onResult: (SensorAvailability) -> Unit) {
        disconnect()
        val token = synchronized(connectionLock) { connectionToken }
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
                            kind = SensorKind.SAMSUNG,
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
                    SensorAvailability(SensorKind.SAMSUNG, ready = true),
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
                        kind = SensorKind.SAMSUNG,
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
    ) {
        val tracker = ecgTracker
        if (tracker == null) {
            onError(EcgSensorError(EcgSensorErrorCode.NOT_CONNECTED, "Samsung ECG is not connected."))
            return
        }
        synchronized(connectionLock) {
            if (ecgListening) return
        }
        val listener = object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(data: List<DataPoint>) {
                    if (data.isEmpty()) return
                    val batch = try {
                        mapBatch(data)
                    } catch (error: Exception) {
                        acquisition.execute {
                            onError(
                                EcgSensorError(
                                    EcgSensorErrorCode.INVALID_BATCH,
                                    error.message ?: "Samsung returned an invalid ECG batch.",
                                ),
                            )
                        }
                        return
                    }
                    acquisition.execute { onBatch(batch) }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    val code = if (error == HealthTracker.TrackerError.SDK_POLICY_ERROR) {
                        EcgSensorErrorCode.SDK_POLICY
                    } else {
                        EcgSensorErrorCode.TRACKER
                    }
                    acquisition.execute {
                        onError(EcgSensorError(code, "Samsung ECG tracker error: $error"))
                    }
                }
            }
        try {
            tracker.setEventListener(listener)
            synchronized(connectionLock) { ecgListening = true }
        } catch (error: Exception) {
            synchronized(connectionLock) { ecgListening = false }
            onError(
                EcgSensorError(
                    EcgSensorErrorCode.START_FAILED,
                    error.message ?: "Samsung ECG listener could not start.",
                ),
            )
        }
    }

    override fun stopEcg() {
        val tracker = synchronized(connectionLock) {
            ecgTracker.takeIf { ecgListening }.also { ecgListening = false }
        }
        try {
            tracker?.unsetEventListener()
        } catch (_: Exception) {
            // Listener may already be unset after an SDK error.
        }
    }

    override fun stop() {
        val listeners = synchronized(connectionLock) {
            ListenerHandles(ecg = ecgTracker.takeIf { ecgListening }).also { ecgListening = false }
        }
        stopListeners(listeners)
    }

    override fun disconnect() {
        val connection = synchronized(connectionLock) {
            connectionToken += 1
            ConnectionHandles(
                service = service,
                listeners = ListenerHandles(
                    ecg = ecgTracker.takeIf { ecgListening },
                ),
            ).also {
                service = null
                ecgTracker = null
                ecgListening = false
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
            if (value <= minThreshold || value >= maxThreshold) {
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
        kind = SensorKind.SAMSUNG,
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
