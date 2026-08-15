package app.healthtrack.data.protocol

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Synthetic 500 Hz trace that matches the watch csv+gz contract. */
object DemoEcg {
    fun gzipBytes(sessionId: String = "demo"): ByteArray {
        val sr = 500
        val seconds = 8
        val n = sr * seconds
        val start = 1_700_000_000_000L
        val body = buildString {
            append("#meta={\"sr_hz\":500,\"unit\":\"mV\",\"ts_start\":")
            append(start)
            append(",\"format\":\"csv_mv\",\"wrist\":\"LEFT\",\"signFactor\":1,\"polarityNormalized\":true,\"watch_info\":\"demo\"}\n")
            append("rel_ms,value_mv,hr_bpm\n")
            repeat(n) { i ->
                val t = i.toDouble() / sr
                val beat = (i % sr)
                val qrs = if (beat in 140..155) {
                    val x = (beat - 147) / 3.0
                    1.4 * exp(-x * x)
                } else {
                    0.08 * sin(2 * PI * t * 1.2)
                }
                val hr = if (i % 4 == 0) "68" else ""
                append(i * 2).append(',').append("%.3f".format(Locale.US, qrs)).append(',').append(hr).append('\n')
            }
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(body.toByteArray()) }
        return out.toByteArray()
    }
}
