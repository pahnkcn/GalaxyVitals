package app.galaxyvitals.wear.capture

import app.galaxyvitals.wear.sensors.EcgBatch
import app.galaxyvitals.wear.sensors.PpgGreenBatch

/**
 * The first [count] samples of a batch, PPG included.
 *
 * The last batch of a capture usually overruns the 30 s the recorder will
 * accept. Truncating the ECG alone would leave PPG points pointing at samples
 * that were never stored, so the green channel is filtered to the offsets that
 * survive rather than carried across whole.
 */
internal fun EcgBatch.keepPrefix(count: Int): EcgBatch {
    if (count >= samplesMv.size) return this
    val ppg = ppgGreen?.let { green ->
        val kept = green.ecgSampleOffsets.indices.filter { index -> green.ecgSampleOffsets[index] < count }
        if (kept.isEmpty()) {
            null
        } else {
            PpgGreenBatch(
                values = IntArray(kept.size) { green.values[kept[it]] },
                ecgSampleOffsets = IntArray(kept.size) { green.ecgSampleOffsets[kept[it]] },
                sensorTimestampsMs = LongArray(kept.size) { green.sensorTimestampsMs[kept[it]] },
                nominalSampleRateHz = green.nominalSampleRateHz,
            )
        }
    }
    return copy(
        samplesMv = samplesMv.copyOf(count),
        sensorTimestampsMs = sensorTimestampsMs.copyOf(count),
        sampleFlags = sampleFlags.copyOf(count),
        ppgGreen = ppg,
    )
}
