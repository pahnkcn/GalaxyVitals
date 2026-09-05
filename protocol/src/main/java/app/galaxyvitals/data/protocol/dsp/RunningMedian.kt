package app.galaxyvitals.data.protocol.dsp

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sliding median over a window kept sorted by insertion.
 *
 * The median matters to ECG because, unlike a linear high-pass, it cannot ring:
 * the electrode-polarisation step at the head of a capture is absorbed rather
 * than turned into a multi-second swing. Keeping one sorted window and moving
 * two elements per sample makes that affordable at 500 Hz - a per-sample sort
 * would not be.
 *
 * The streaming twin used on the watch is in EcgCausalConditioning, which
 * drives the same [removeSorted] / [insertSorted] pair.
 */

/** Nearest odd sample count to [milliseconds] at [srHz], never below 3. */
internal fun oddKernel(milliseconds: Int, srHz: Double): Int {
    var kernel = (milliseconds * srHz / 1_000.0).roundToInt()
    if (kernel % 2 == 0) kernel += 1
    return max(3, kernel)
}

/** Sliding median over a reflected signal; O(n·k) moves, no per-sample sort. */
internal fun runningMedian(values: DoubleArray, kernel: Int): DoubleArray {
    val n = values.size
    if (n == 0) return DoubleArray(0)
    if (kernel <= 1 || kernel > n) return values.copyOf()
    val radius = kernel / 2
    val padded = DoubleArray(n + 2 * radius)
    java.util.Arrays.fill(padded, 0, radius, values[0])
    System.arraycopy(values, 0, padded, radius, n)
    java.util.Arrays.fill(padded, radius + n, padded.size, values[n - 1])

    val window = DoubleArray(kernel)
    System.arraycopy(padded, 0, window, 0, kernel)
    window.sort()
    val out = DoubleArray(n)
    out[0] = window[radius]
    for (i in 1 until n) {
        removeSorted(window, kernel, padded[i - 1])
        insertSorted(window, kernel, padded[i + kernel - 1])
        out[i] = window[radius]
    }
    return out
}

internal fun removeSorted(window: DoubleArray, size: Int, value: Double) {
    var position = lowerBound(window, size, value)
    if (position >= size || window[position] != value) {
        // Guard against a binary-search miss on repeated values.
        position = window.indexOfFirst(size) { it == value }
        if (position < 0) return
    }
    System.arraycopy(window, position + 1, window, position, size - position - 1)
}

internal fun insertSorted(window: DoubleArray, size: Int, value: Double) {
    val position = lowerBound(window, size - 1, value)
    System.arraycopy(window, position, window, position + 1, size - position - 1)
    window[position] = value
}

private fun lowerBound(window: DoubleArray, size: Int, value: Double): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (window[mid] < value) low = mid + 1 else high = mid
    }
    return low
}

private inline fun DoubleArray.indexOfFirst(size: Int, predicate: (Double) -> Boolean): Int {
    for (i in 0 until size) if (predicate(this[i])) return i
    return -1
}
