package app.galaxyvitals.wear.sensors

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class SamsungEcgSensor(
    context: Context,
    private val hasRequiredSensorPermissions: () -> Boolean = {
        SensorPermissions.hasAll(context.applicationContext)
    },
) : EcgSensor {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val acquisition = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "samsung-ecg-acquisition").apply { isDaemon = true }
    }
    private var host = WeakReference<Activity>(null)
    private val scheduler = HandlerDeadlineScheduler(main)
    private val resolution = SamsungEcgResolution()

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
    private var onDemandClose: EcgSubscription? = null
    private var heartRateTracker: HealthTracker? = null
    private var heartRateSubscriptionEpoch = 0L
    private var activeHeartRateSubscriptionEpoch = 0L
    private var heartRateClose: EcgSubscription? = null
    private val ppgLogLock = Any()
    private var ppgLogWindowStartMs = 0L
    private var ppgLogBatches = 0
    private var ppgLogDecodedBatches = 0
    private var ppgLogDroppedBatches = 0
    private var ppgLogDecodedCount = 0
    private var ppgLogOffsetMin = Int.MAX_VALUE
    private var ppgLogOffsetMax = Int.MIN_VALUE
    private var ppgLogTsMin = Long.MAX_VALUE
    private var ppgLogTsMax = Long.MIN_VALUE

    override fun connect(onResult: (SensorAvailability) -> Unit) {
        val hasExistingConnection = synchronized(connectionLock) {
            service != null ||
                ecgTracker != null ||
                heartRateTracker != null ||
                activeSubscriptionEpoch != 0L ||
                activeHeartRateSubscriptionEpoch != 0L
        }
        if (hasExistingConnection) disconnect()
        SamsungEcgMapping.connectBlockedByPermissions(hasRequiredSensorPermissions())?.let { denied ->
            main.post { onResult(denied) }
            return
        }
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
                            issue = SamsungEcgMapping.missingOnDemandTracker(app.packageName),
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
                            ready = false,
                            reason = "HEART_RATE_CONTINUOUS is not available for ${app.packageName}.",
                            issue = SamsungEcgMapping.missingHeartRateTracker(app.packageName),
                        ),
                    )
                    return
                }
                val candidateEcg: HealthTracker
                val candidateHeartRate: HealthTracker
                try {
                    candidateEcg = candidate.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND)
                    candidateHeartRate = candidate.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
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
                        heartRateTracker = candidateHeartRate
                        true
                    } else {
                        false
                    }
                }
                if (!installed) {
                    disconnectStale(candidate)
                    return
                }
                resolution.clear()
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
                // User-initiated resolvePending() only. Never call exception.resolve() here.
                val issue = resolution.remember(exception)
                deliverResult(
                    candidate,
                    token,
                    onResult,
                    SensorAvailability(
                        ready = false,
                        reason = issue.message,
                        issue = issue,
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

    override fun resolvePending(activity: Activity): Boolean = resolution.resolvePending(activity)

    override fun startHeartRate(
        onError: (EcgSensorError) -> Unit,
        onBatch: (HeartRateBatch) -> Unit,
    ): EcgSubscription {
        val tracker = synchronized(connectionLock) { heartRateTracker }
        if (tracker == null) {
            onError(
                EcgSensorError(
                    EcgSensorErrorCode.NOT_CONNECTED,
                    "Samsung continuous heart rate is not connected.",
                ),
            )
            return EcgSubscription { }
        }
        val epoch = synchronized(connectionLock) {
            check(activeHeartRateSubscriptionEpoch == 0L) {
                "Samsung heart-rate listener is already active."
            }
            ++heartRateSubscriptionEpoch
            activeHeartRateSubscriptionEpoch = heartRateSubscriptionEpoch
            heartRateSubscriptionEpoch
        }
        val session = SamsungHeartRateSession(
            tracker = tracker,
            isCurrent = { isCurrentHeartRateSubscription(epoch) },
            postMain = { block -> main.post { block() } },
            execute = { block -> acquisition.execute(block) },
        )
        val subscription = try {
            session.start(onError = onError, onBatch = onBatch)
        } catch (error: Exception) {
            synchronized(connectionLock) {
                if (activeHeartRateSubscriptionEpoch == epoch) {
                    activeHeartRateSubscriptionEpoch = 0L
                }
            }
            onError(
                EcgSensorError(
                    EcgSensorErrorCode.START_FAILED,
                    error.message ?: "Samsung heart-rate listener could not start.",
                ),
            )
            return EcgSubscription { }
        }
        val accepted = synchronized(connectionLock) {
            if (activeHeartRateSubscriptionEpoch == epoch) {
                heartRateClose = subscription
                true
            } else {
                false
            }
        }
        if (!accepted) {
            subscription.close()
            return EcgSubscription { }
        }
        return EcgSubscription { closeHeartRate(epoch, subscription) }
    }

    override fun startEcg(
        maxDurationMs: Long,
        onError: (EcgSensorError) -> Unit,
        onBatch: (EcgBatch) -> Unit,
        onDeadline: () -> Unit,
    ): EcgSubscription {
        SamsungEcgMapping.requireOnDemandDuration(maxDurationMs)
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
        val session = SamsungEcgOnDemandSession(
            tracker = tracker,
            scheduler = scheduler,
            isCurrent = { isCurrentSubscription(epoch) },
            postMain = { block -> main.post { block() } },
            execute = { block -> acquisition.execute(block) },
        )
        val subscription = try {
            session.startEcg(
                maxDurationMs = maxDurationMs,
                onError = onError,
                onBatch = { batch ->
                    logPpgDecode(batchSize = batch.samplesMv.size, ppgGreen = batch.ppgGreen)
                    onBatch(batch)
                },
                onDeadline = {
                    synchronized(connectionLock) {
                        if (activeSubscriptionEpoch == epoch) {
                            activeSubscriptionEpoch = 0L
                            subscriptionEpoch += 1
                        }
                    }
                    onDeadline()
                },
            )
        } catch (error: Exception) {
            synchronized(connectionLock) {
                if (activeSubscriptionEpoch == epoch) activeSubscriptionEpoch = 0L
            }
            if (error is IllegalArgumentException) throw error
            onError(
                EcgSensorError(
                    EcgSensorErrorCode.START_FAILED,
                    error.message ?: "Samsung ECG listener could not start.",
                ),
            )
            return EcgSubscription { }
        }
        val accepted = synchronized(connectionLock) {
            if (activeSubscriptionEpoch == epoch) {
                onDemandClose = subscription
                true
            } else {
                false
            }
        }
        if (!accepted) {
            subscription.close()
            return EcgSubscription { }
        }
        return EcgSubscription { closeOnDemand(epoch, subscription) }
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
                listeners = ListenerHandles(
                    ecg = ecgTracker.takeIf { activeSubscriptionEpoch != 0L },
                    ecgSession = onDemandClose,
                    heartRate = heartRateTracker.takeIf {
                        activeHeartRateSubscriptionEpoch != 0L
                    },
                    heartRateSession = heartRateClose,
                ),
            ).also {
                service = null
                ecgTracker = null
                heartRateTracker = null
                onDemandClose = null
                heartRateClose = null
                activeSubscriptionEpoch = 0L
                activeHeartRateSubscriptionEpoch = 0L
                subscriptionEpoch += 1
                heartRateSubscriptionEpoch += 1
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

    private fun isCurrentHeartRateSubscription(epoch: Long): Boolean = synchronized(connectionLock) {
        activeHeartRateSubscriptionEpoch == epoch
    }

    private fun closeOnDemand(epoch: Long, subscription: EcgSubscription) {
        val current = synchronized(connectionLock) {
            if (onDemandClose === subscription) onDemandClose = null
            if (activeSubscriptionEpoch != epoch) return
            activeSubscriptionEpoch = 0L
            subscriptionEpoch += 1
            subscription
        }
        current.close()
    }

    private fun closeHeartRate(epoch: Long, subscription: EcgSubscription) {
        val current = synchronized(connectionLock) {
            if (heartRateClose === subscription) heartRateClose = null
            if (activeHeartRateSubscriptionEpoch != epoch) return
            activeHeartRateSubscriptionEpoch = 0L
            heartRateSubscriptionEpoch += 1
            subscription
        }
        current.close()
    }

    private fun takeActiveListener(): ListenerHandles = synchronized(connectionLock) {
        ListenerHandles(
            ecg = ecgTracker.takeIf { activeSubscriptionEpoch != 0L },
            ecgSession = onDemandClose,
            heartRate = heartRateTracker.takeIf { activeHeartRateSubscriptionEpoch != 0L },
            heartRateSession = heartRateClose,
        ).also {
            onDemandClose = null
            heartRateClose = null
            activeSubscriptionEpoch = 0L
            activeHeartRateSubscriptionEpoch = 0L
            subscriptionEpoch += 1
            heartRateSubscriptionEpoch += 1
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
        if (listeners.ecgSession != null) {
            listeners.ecgSession.close()
        } else {
            try {
                listeners.ecg?.unsetEventListener()
            } catch (_: Exception) {
                // Listener may already be unset.
            }
        }
        if (listeners.heartRateSession != null) {
            listeners.heartRateSession.close()
        } else {
            try {
                listeners.heartRate?.unsetEventListener()
            } catch (_: Exception) {
                // Listener may already be unset.
            }
        }
    }

    private fun logPpgDecode(batchSize: Int, ppgGreen: PpgGreenBatch?) {
        val now = SystemClock.elapsedRealtime()
        val aggregate: String? = synchronized(ppgLogLock) {
            if (ppgLogWindowStartMs == 0L) ppgLogWindowStartMs = now
            ppgLogBatches += 1
            if (ppgGreen == null) {
                ppgLogDroppedBatches += 1
            } else {
                ppgLogDecodedBatches += 1
                ppgLogDecodedCount += ppgGreen.values.size
                val offsets = ppgGreen.ecgSampleOffsets
                if (offsets.isNotEmpty()) {
                    val first = offsets.first()
                    val last = offsets.last()
                    if (first < ppgLogOffsetMin) ppgLogOffsetMin = first
                    if (last > ppgLogOffsetMax) ppgLogOffsetMax = last
                }
                val timestamps = ppgGreen.sensorTimestampsMs
                if (timestamps.isNotEmpty()) {
                    var tsMin = timestamps[0]
                    var tsMax = timestamps[0]
                    for (index in 1 until timestamps.size) {
                        val ts = timestamps[index]
                        if (ts < tsMin) tsMin = ts
                        if (ts > tsMax) tsMax = ts
                    }
                    if (tsMin < ppgLogTsMin) ppgLogTsMin = tsMin
                    if (tsMax > ppgLogTsMax) ppgLogTsMax = tsMax
                }
            }
            if (now - ppgLogWindowStartMs < PPG_LOG_INTERVAL_MS) {
                null
            } else {
                formatPpgLogAggregate().also { resetPpgLogWindow(now) }
            }
        }
        if (aggregate != null) Log.i(ECG_ACQUISITION_TAG, aggregate)
    }

    private fun formatPpgLogAggregate(): String {
        val hasOffsets = ppgLogOffsetMin != Int.MAX_VALUE
        val hasTs = ppgLogTsMin != Long.MAX_VALUE
        val offsetSpan = if (hasOffsets) ppgLogOffsetMax - ppgLogOffsetMin else 0
        val tsMin = if (hasTs) ppgLogTsMin else 0L
        val tsMax = if (hasTs) ppgLogTsMax else 0L
        return "ppg 1s batches=$ppgLogBatches decodedBatches=$ppgLogDecodedBatches " +
            "droppedBatches=$ppgLogDroppedBatches count=$ppgLogDecodedCount " +
            "offsets=${if (hasOffsets) "[$ppgLogOffsetMin..$ppgLogOffsetMax]" else "[]"} " +
            "offsetSpan=$offsetSpan tsMin=$tsMin tsMax=$tsMax tsSpan=${tsMax - tsMin}"
    }

    private fun resetPpgLogWindow(now: Long) {
        ppgLogWindowStartMs = now
        ppgLogBatches = 0
        ppgLogDecodedBatches = 0
        ppgLogDroppedBatches = 0
        ppgLogDecodedCount = 0
        ppgLogOffsetMin = Int.MAX_VALUE
        ppgLogOffsetMax = Int.MIN_VALUE
        ppgLogTsMin = Long.MAX_VALUE
        ppgLogTsMax = Long.MIN_VALUE
    }

    private fun unavailable(reason: String) = SensorAvailability(
        ready = false,
        reason = reason,
        issue = SensorIssue(SensorIssueCode.CONNECTION_FAILED, reason, SensorRecovery.RETRY),
    )

    private data class ListenerHandles(
        val ecg: HealthTracker?,
        val ecgSession: EcgSubscription? = null,
        val heartRate: HealthTracker?,
        val heartRateSession: EcgSubscription? = null,
    )

    private data class ConnectionHandles(
        val service: HealthTrackingService?,
        val listeners: ListenerHandles,
    )

    companion object {
        private const val ECG_ACQUISITION_TAG = "EcgAcquisition"
        private const val PPG_LOG_INTERVAL_MS = 1_000L
    }
}
