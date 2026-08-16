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
    private var service: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var ecgTracker: HealthTracker? = null
    private var hrListening = false
    private var ecgListening = false

    override fun connect(onResult: (SensorAvailability) -> Unit) {
        disconnect()
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                val svc = service
                if (svc == null) {
                    onResult(unavailable("Health tracking service missing after connect."))
                    return
                }
                val supported = svc.getTrackingCapability().getSupportHealthTrackerTypes()
                if (!supported.contains(HealthTrackerType.ECG_ON_DEMAND)) {
                    onResult(
                        SensorAvailability(
                            kind = SensorKind.SAMSUNG,
                            ready = false,
                            reason = "ECG_ON_DEMAND is not available for ${app.packageName}.",
                            policyDenied = true,
                        ),
                    )
                    return
                }
                hrTracker = if (supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)) {
                    svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
                } else {
                    null
                }
                ecgTracker = svc.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND)
                onResult(SensorAvailability(SensorKind.SAMSUNG, ready = true))
            }

            override fun onConnectionEnded() = Unit

            override fun onConnectionFailed(exception: HealthTrackerException) {
                val activity = host.get()
                if (exception.hasResolution() && activity != null && !activity.isFinishing) {
                    runCatching { exception.resolve(activity) }
                }
                onResult(
                    SensorAvailability(
                        kind = SensorKind.SAMSUNG,
                        ready = false,
                        reason = exception.message ?: "Samsung Health connection failed.",
                        policyDenied = true,
                    ),
                )
            }
        }
        val svc = HealthTrackingService(listener, app)
        service = svc
        svc.connectService()
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
        runCatching {
            if (hrListening) hrTracker?.unsetEventListener()
            if (ecgListening) ecgTracker?.unsetEventListener()
        }
        hrListening = false
        ecgListening = false
    }

    override fun disconnect() {
        stop()
        runCatching { service?.disconnectService() }
        service = null
        hrTracker = null
        ecgTracker = null
    }

    private fun unavailable(reason: String) = SensorAvailability(
        kind = SensorKind.SAMSUNG,
        ready = false,
        reason = reason,
        policyDenied = true,
    )
}
