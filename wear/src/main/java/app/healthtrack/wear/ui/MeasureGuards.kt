package app.healthtrack.wear.ui

import app.healthtrack.data.protocol.EcgWearContract

internal fun isRecentHeartRate(
    hrOk: Boolean,
    lastGoodAt: Long,
    now: Long,
): Boolean = hrOk &&
    lastGoodAt > 0L &&
    now >= lastGoodAt &&
    now - lastGoodAt <= EcgWearContract.HR_LOST_ABORT_MS
