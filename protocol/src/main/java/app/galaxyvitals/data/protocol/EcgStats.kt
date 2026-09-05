package app.galaxyvitals.data.protocol

/**
 * The order statistics the ECG code actually uses.
 *
 * Almost every stage here prefers a median to a mean, because a wrist capture's
 * outliers are motion artifacts rather than signal: the clock fit trims on MAD,
 * the line-noise floor is a median of neighbouring bins, the baseline is a
 * median cascade, and the reported rate is the median RR. That made five
 * near-identical private copies of the same eight lines across two modules.
 *
 * The empty case is the only thing they disagreed on, so callers say what they
 * want rather than inheriting whichever copy they happened to reach for.
 */
object EcgStats {

    /** Median of [values], or [whenEmpty] when there is nothing to rank. */
    fun median(values: List<Double>, whenEmpty: Double): Double {
        if (values.isEmpty()) return whenEmpty
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /** Median of [values], or [whenEmpty] when there is nothing to rank. [values] is not modified. */
    fun median(values: DoubleArray, whenEmpty: Double): Double {
        if (values.isEmpty()) return whenEmpty
        val sorted = values.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /**
     * Median of a non-empty [values]. [values] is not modified.
     *
     * There is no empty case: the one caller is the display autoscale, which
     * has already returned on an empty frame before it gets here.
     */
    fun median(values: FloatArray): Float {
        val sorted = values.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    /** Median of an already-ascending, non-empty [sorted]. */
    fun medianOfSorted(sorted: List<Int>): Double {
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2].toDouble()
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }
}
