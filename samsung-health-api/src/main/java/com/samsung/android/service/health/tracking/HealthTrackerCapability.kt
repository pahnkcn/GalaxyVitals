package com.samsung.android.service.health.tracking

import com.samsung.android.service.health.tracking.data.HealthTrackerType

class HealthTrackerCapability {
    fun getSupportHealthTrackerTypes(): List<HealthTrackerType> = emptyList()

    fun getVersion(): String = "stub"
}
