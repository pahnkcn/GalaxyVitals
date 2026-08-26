package com.samsung.android.service.health.tracking

import android.content.Context
import com.samsung.android.service.health.tracking.data.HealthTrackerType

/**
 * Compile-time stand-in for the official Samsung Privileged Health SDK client.
 * Drop `wear/libs/samsung-health-sensor-api.aar` to bind the real service.
 */
open class HealthTrackingService(
    private val connectionListener: ConnectionListener,
    @Suppress("unused") private val context: Context,
) {
    open fun connectService() {
        connectionListener.onConnectionFailed(
            HealthTrackerException(
                "Official Samsung Health Tracking client is not packaged. " +
                    "Place samsung-health-sensor-api.aar in wear/libs/.",
            ),
        )
    }

    open fun disconnectService() = Unit

    open fun getHealthTracker(@Suppress("unused") type: HealthTrackerType): HealthTracker = HealthTracker()

    open fun getTrackingCapability(): HealthTrackerCapability = HealthTrackerCapability()
}
