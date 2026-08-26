package com.samsung.android.service.health.tracking.data

class DataPoint(
    private val values: Map<ValueKey<*>, Any?> = emptyMap(),
    val timestamp: Long = 0L,
) {
    @Suppress("UNCHECKED_CAST")
    fun <T> getValue(key: ValueKey<T>): T = values[key] as T
}
