package com.samsung.android.service.health.tracking

interface ConnectionListener {
    fun onConnectionSuccess()
    fun onConnectionEnded()
    fun onConnectionFailed(exception: HealthTrackerException)
}
