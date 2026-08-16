package com.samsung.android.service.health.tracking

import android.app.Activity
import android.content.pm.PackageManager

class HealthTrackerException : Exception {
    constructor(message: String) : super(message)
    constructor(@Suppress("unused") packageManager: PackageManager) : super("Connection failed to SHS.")

    fun hasResolution(): Boolean = false

    fun getErrorCode(): Int = 0

    fun resolve(@Suppress("unused") activity: Activity) = Unit
}
