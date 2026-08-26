package app.galaxyvitals.wear.sensors

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import org.junit.Test

class SamsungEcgSensorContractTest {
    @Test
    fun startEcgRejectsDurationOver30000WithoutOpeningListener() {
        val tracker = RecordingHealthTracker()
        val session = session(tracker)
        val error = runCatching {
            session.startEcg(
                maxDurationMs = 30_001,
                onError = {},
                onBatch = {},
                onDeadline = {},
            )
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(tracker.setCount).isEqualTo(0)
        assertThat(tracker.unsetCount).isEqualTo(0)
    }

    @Test
    fun startEcgAcceptsDurationOf30000() {
        val tracker = RecordingHealthTracker()
        val session = session(tracker)
        val subscription = session.startEcg(
            maxDurationMs = 30_000,
            onError = {},
            onBatch = {},
            onDeadline = {},
        )
        assertThat(tracker.setCount).isEqualTo(1)
        subscription.close()
    }

    @Test
    fun subscriptionCloseIsIdempotent() {
        val tracker = RecordingHealthTracker()
        val subscription = session(tracker).startEcg(
            maxDurationMs = 30_000,
            onError = {},
            onBatch = {},
            onDeadline = {},
        )
        subscription.close()
        subscription.close()
        assertThat(tracker.unsetCount).isEqualTo(1)
    }

    @Test
    fun deadlineUnsetsListenerThenFiresOnDeadline() {
        val tracker = RecordingHealthTracker()
        val scheduler = ManualDeadlineScheduler()
        val events = arrayListOf<String>()
        session(tracker, scheduler).startEcg(
            maxDurationMs = 30_000,
            onError = {},
            onBatch = {},
            onDeadline = { events += "deadline" },
        )
        tracker.onUnset = { events += "unset" }
        assertThat(scheduler.tasks).hasSize(1)
        assertThat(scheduler.tasks.single().delayMs).isEqualTo(30_000L)
        scheduler.fireNext()
        assertThat(events).containsExactly("unset", "deadline").inOrder()
        assertThat(tracker.unsetCount).isEqualTo(1)
    }

    @Test
    fun inFlightBatchIsDeliveredBeforeDeadlineAfterUnset() {
        val tracker = RecordingHealthTracker()
        val scheduler = ManualDeadlineScheduler()
        val events = arrayListOf<String>()
        var current = true
        val queued = ArrayList<() -> Unit>()
        val session = SamsungEcgOnDemandSession(
            tracker = tracker,
            scheduler = scheduler,
            isCurrent = { current },
            postMain = { it() },
            execute = { queued += it },
        )
        session.startEcg(
            maxDurationMs = 30_000,
            onError = {},
            onBatch = { events += "batch" },
            onDeadline = { events += "deadline" },
        )
        tracker.listener!!.onDataReceived(ecgPoints())
        tracker.onUnset = { current = false }
        scheduler.fireNext()
        queued.forEach { it() }
        assertThat(events).containsExactly("batch", "deadline").inOrder()
    }

    @Test
    fun closeCancelsDeadlineAndUnsetsOnce() {
        val tracker = RecordingHealthTracker()
        val scheduler = ManualDeadlineScheduler()
        var deadline = 0
        val subscription = session(tracker, scheduler).startEcg(
            maxDurationMs = 30_000,
            onError = {},
            onBatch = {},
            onDeadline = { deadline += 1 },
        )
        subscription.close()
        scheduler.fireNext()
        assertThat(tracker.unsetCount).isEqualTo(1)
        assertThat(deadline).isEqualTo(0)
    }

    @Test
    fun resolvePendingIsNoOpUnlessHasResolution() {
        val resolution = SamsungEcgResolution()
        var resolved = 0
        val withoutResolution = HealthTrackerException(
            message = "old platform",
            errorCode = HealthTrackerException.OLD_PLATFORM_VERSION,
            hasResolution = false,
            onResolve = { resolved += 1 },
        )
        resolution.remember(withoutResolution)
        assertThat(resolution.resolvePending(Activity())).isFalse()
        assertThat(resolved).isEqualTo(0)

        val withResolution = HealthTrackerException(
            message = "not installed",
            errorCode = HealthTrackerException.PACKAGE_NOT_INSTALLED,
            hasResolution = true,
            onResolve = { resolved += 1 },
        )
        resolution.remember(withResolution)
        assertThat(resolution.resolvePending(Activity())).isTrue()
        assertThat(resolved).isEqualTo(1)
        assertThat(resolution.resolvePending(Activity())).isTrue()
    }

    @Test
    fun trackerPermissionErrorDeliversRequestPermissionIssue() {
        val tracker = RecordingHealthTracker()
        val errors = arrayListOf<EcgSensorError>()
        session(tracker).startEcg(
            maxDurationMs = 30_000,
            onError = { errors += it },
            onBatch = {},
            onDeadline = {},
        )
        tracker.listener!!.onError(HealthTracker.TrackerError.PERMISSION_ERROR)
        assertThat(errors).hasSize(1)
        assertThat(errors.single().issue?.code).isEqualTo(SensorIssueCode.PERMISSION_ERROR)
        assertThat(errors.single().issue?.recovery).isEqualTo(SensorRecovery.REQUEST_PERMISSION)
        assertThat(errors.single().code).isNotEqualTo(EcgSensorErrorCode.SDK_POLICY)
    }

    @Test
    fun connectionFailureNeverAutoResolves() {
        val resolution = SamsungEcgResolution()
        var resolved = 0
        val exception = HealthTrackerException(
            message = "not installed",
            errorCode = HealthTrackerException.PACKAGE_NOT_INSTALLED,
            hasResolution = true,
            onResolve = { resolved += 1 },
        )
        val issue = resolution.remember(exception)
        assertThat(issue.code).isEqualTo(SensorIssueCode.PACKAGE_NOT_INSTALLED)
        assertThat(resolved).isEqualTo(0)
        assertThat(resolution.resolvePending(Activity())).isTrue()
        assertThat(resolved).isEqualTo(1)
    }

    private fun ecgPoints(size: Int = 5): List<DataPoint> = List(size) { index ->
        DataPoint(
            values = mapOf(
                ValueKey.EcgSet.ECG_MV to 0.1f,
                ValueKey.EcgSet.LEAD_OFF to 0,
                ValueKey.EcgSet.SEQUENCE to 1,
                ValueKey.EcgSet.MIN_THRESHOLD_MV to -5f,
                ValueKey.EcgSet.MAX_THRESHOLD_MV to 5f,
            ),
            timestamp = 1_000L + index * 2L,
        )
    }

    private fun session(
        tracker: HealthTracker,
        scheduler: SamsungDeadlineScheduler = ManualDeadlineScheduler(),
    ) = SamsungEcgOnDemandSession(
        tracker = tracker,
        scheduler = scheduler,
    )

    private class RecordingHealthTracker : HealthTracker() {
        var setCount = 0
        var unsetCount = 0
        var listener: TrackerEventListener? = null
        var onUnset: (() -> Unit)? = null

        override fun setEventListener(listener: TrackerEventListener) {
            setCount += 1
            this.listener = listener
        }

        override fun unsetEventListener() {
            unsetCount += 1
            onUnset?.invoke()
        }
    }

    private class ManualDeadlineScheduler : SamsungDeadlineScheduler {
        data class Task(
            val delayMs: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        val tasks = arrayListOf<Task>()

        override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
            val task = Task(delayMs, action)
            tasks += task
            return AutoCloseable { task.cancelled = true }
        }

        fun fireNext() {
            val task = tasks.firstOrNull { !it.cancelled } ?: return
            task.cancelled = true
            task.action()
        }
    }
}
