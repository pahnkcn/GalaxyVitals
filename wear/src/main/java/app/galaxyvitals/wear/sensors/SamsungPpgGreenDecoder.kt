package app.galaxyvitals.wear.sensors

internal object SamsungPpgGreenDecoder {
    fun decode(
        batchSize: Int,
        timestampAt: (Int) -> Long,
        valueAt: (Int) -> Int?,
    ): PpgGreenBatch? {
        val offsets = when (batchSize) {
            5 -> intArrayOf(0)
            10 -> intArrayOf(0, 5)
            else -> return null
        }

        val values = IntArray(offsets.size)
        val timestamps = LongArray(offsets.size)
        for (i in offsets.indices) {
            val offset = offsets[i]
            values[i] = valueAt(offset) ?: return null
            timestamps[i] = timestampAt(offset)
        }
        return PpgGreenBatch(values, offsets, timestamps)
    }
}
