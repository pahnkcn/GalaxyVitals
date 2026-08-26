package com.samsung.android.service.health.tracking

import com.samsung.android.service.health.tracking.data.DataPoint

open class HealthTracker {
    open fun setEventListener(listener: TrackerEventListener) {
        listener.onError(TrackerError.SDK_POLICY_ERROR)
    }

    open fun unsetEventListener() = Unit

    interface TrackerEventListener {
        fun onDataReceived(data: List<DataPoint>)
        fun onFlushCompleted()
        fun onError(error: TrackerError)
    }

    enum class TrackerError {
        SDK_POLICY_ERROR,
        PERMISSION_ERROR,
    }
}
