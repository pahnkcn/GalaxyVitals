package app.healthtrack.wear.sensors

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.healthtrack.data.protocol.EcgWearContract
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.lang.ref.WeakReference

class SamsungEcgSensor(context: Context) : EcgSensor {
    override val kind: SensorKind = SensorKind.SAMSUNG

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var host = WeakReference<Activity>(null)

    fun attach(activity: Activity) {
        host = WeakReference(activity)
    }
    private val connectionLock = Any()
    @Volatile
    private var service: HealthTrackingService? = null
    @Volatile
    private var connectionToken = 0L
    private var hrTracker: HealthTracker? = null
    private var ecgTracker: HealthTracker? = null
    private var hrListening = false
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
                if (!supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
                    deliverResult(
                        candidate,
                        token,
                        onResult,
                        SensorAvailability(
                            kind = SensorKind.SAMSUNG,
                            ready = false,
                            reason = "HEART_RATE_CONTINUOUS is required for ECG recording.",
                        ),
                    )
                    return
                }
                val candidateHr: HealthTracker
                val candidateEcg: HealthTracker
                try {
                    candidateHr = candidate.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
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
                        hrTracker = candidateHr
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

    override fun startHr(onHr: (bpm: Int, status: Int) -> Unit) {
        val tracker = hrTracker ?: return
        if (!hrListening) {
            hrListening = true
            tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(data: List<DataPoint>) {
                    if (data.isEmpty()) return
                    val last = data.last()
                    val bpm = last.getValue(ValueKey.HeartRateSet.HEART_RATE)
                    val status = last.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                    main.post { onHr(bpm, status) }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) = Unit
            })
        }
    }

    override fun startEcg(onBatch: (mv: FloatArray, leadOff: Boolean) -> Unit) {
        val tracker = ecgTracker ?: return
        if (!ecgListening) {
            ecgListening = true
            tracker.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(data: List<DataPoint>) {
                    if (data.isEmpty()) return
                    val leadOff = data.any {
                        it.getValue(ValueKey.EcgSet.LEAD_OFF) == EcgWearContract.LEAD_OFF_NO_CONTACT
                    }
                    val mv = FloatArray(data.size) { i -> data[i].getValue(ValueKey.EcgSet.ECG_MV) }
                    main.post { onBatch(mv, leadOff) }
                }

                override fun onFlushCompleted() = Unit

                override fun onError(error: HealthTracker.TrackerError) {
                    if (error == HealthTracker.TrackerError.SDK_POLICY_ERROR) {
                        main.post { onBatch(floatArrayOf(), true) }
                    }
                }
            })
        }
    }

    override fun stop() {
        val listeners = synchronized(connectionLock) {
            ListenerHandles(
                hr = hrTracker.takeIf { hrListening },
                ecg = ecgTracker.takeIf { ecgListening },
            ).also {
                hrListening = false
                ecgListening = false
            }
        }
        stopListeners(listeners)
    }

    override fun disconnect() {
        val connection = synchronized(connectionLock) {
            connectionToken += 1
            ConnectionHandles(
                service = service,
                listeners = ListenerHandles(
                    hr = hrTracker.takeIf { hrListening },
                    ecg = ecgTracker.takeIf { ecgListening },
                ),
            ).also {
                service = null
                hrTracker = null
                ecgTracker = null
                hrListening = false
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
            listeners.hr?.unsetEventListener()
        } catch (_: Exception) {
            // Listener may already be unset.
        }
        try {
            listeners.ecg?.unsetEventListener()
        } catch (_: Exception) {
            // Listener may already be unset.
        }
    }

    private fun unavailable(reason: String) = SensorAvailability(
        kind = SensorKind.SAMSUNG,
        ready = false,
        reason = reason,
        policyDenied = true,
    )

    private data class ListenerHandles(
        val hr: HealthTracker?,
        val ecg: HealthTracker?,
    )

    private data class ConnectionHandles(
        val service: HealthTrackingService?,
        val listeners: ListenerHandles,
    )
}
