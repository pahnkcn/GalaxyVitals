package com.samsung.android.service.health.tracking

import com.samsung.android.service.health.tracking.data.HealthTrackerType

open class HealthTrackerCapability {
    open fun getSupportHealthTrackerTypes(): List<HealthTrackerType> = emptyList()

    open fun getVersion(): String = "stub"
}
