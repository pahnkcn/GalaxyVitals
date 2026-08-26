package com.samsung.android.service.health.tracking

import android.app.Activity
import android.content.pm.PackageManager

class HealthTrackerException(
    message: String,
    private val errorCode: Int = 99,
    hasResolution: Boolean = false,
    private val onResolve: ((Activity) -> Unit)? = null,
) : Exception(message) {
    constructor(@Suppress("unused") packageManager: PackageManager) : this("Connection failed to SHS.")

    private val resolvable = hasResolution

    fun hasResolution(): Boolean = resolvable

    fun getErrorCode(): Int = errorCode

    fun resolve(activity: Activity) {
        onResolve?.invoke(activity)
    }

    companion object {
        const val PACKAGE_NOT_INSTALLED = 0
        const val OLD_PLATFORM_VERSION = 1
    }
}
