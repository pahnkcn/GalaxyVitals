package com.samsung.android.service.health.tracking.data

class ValueKey<T> private constructor() {
    class EcgSet private constructor() {
        companion object {
            @JvmField
            val LEAD_OFF = ValueKey<Int>()

            @JvmField
            val ECG_MV = ValueKey<Float>()
        }
    }

    class HeartRateSet private constructor() {
        companion object {
            @JvmField
            val HEART_RATE = ValueKey<Int>()

            @JvmField
            val HEART_RATE_STATUS = ValueKey<Int>()
        }
    }
}
