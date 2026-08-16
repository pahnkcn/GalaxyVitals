package app.healthtrack.data.protocol

import app.healthtrack.domain.Wrist
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Synthetic 500 Hz trace that matches the watch csv+gz contract. */
object DemoEcg {

    fun sampleMv(index: Int, srHz: Int = EcgWearContract.DEFAULT_SR_HZ): Float {
        val t = index.toDouble() / srHz
        val beat = index % srHz
        return if (beat in 140..155) {
            val x = (beat - 147) / 3.0
            (1.4 * exp(-x * x)).toFloat()
        } else {
            (0.08 * sin(2 * PI * t * 1.2)).toFloat()
        }
    }

    fun gzipBytes(sessionId: String = "demo", seconds: Int = 8): ByteArray {
        val sr = EcgWearContract.DEFAULT_SR_HZ
        val n = sr * seconds
        val start = 1_700_000_000_000L
        val values = FloatArray(n) { sampleMv(it, sr) }
        val hr = List(seconds) { sec ->
            HrStamp(start + sec * 1000L, 68)
        }
        return EcgCsvWriter.encodeCaptureGzip(
            sessionStartMs = start,
            valuesMv = values,
            hrStamps = hr,
            wrist = Wrist.LEFT,
            signFactor = 1,
            watchInfo = "demo",
        )
    }
}
