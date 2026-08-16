package com.samsung.android.service.health.tracking.data

class HealthTrackerType private constructor(private val label: String) {
    override fun toString(): String = label

    companion object {
        @JvmField
        val HEART_RATE_CONTINUOUS = HealthTrackerType("HEART_RATE_CONTINUOUS")

        @JvmField
        val ECG_ON_DEMAND = HealthTrackerType("ECG_ON_DEMAND")
    }
}
