package app.galaxyvitals.data.protocol.csv

import app.galaxyvitals.data.protocol.EcgParseException
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Refuses to hand out more than [maxBytes].
 *
 * Both the compressed payload and its inflated contents are wrapped, so a
 * decompression bomb is stopped at the second wrapper rather than after the
 * heap is gone.
 */
internal class LimitedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    private val description: String,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        if (count >= maxBytes) {
            val extra = super.read()
            if (extra < 0) return -1
            throw EcgParseException("$description exceeds $maxBytes bytes")
        }
        val value = super.read()
        if (value >= 0) count++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (count >= maxBytes) return read().let { if (it < 0) -1 else 1 }
        val allowed = minOf(length.toLong(), maxBytes - count).toInt()
        val read = super.read(buffer, offset, allowed)
        if (read > 0) count += read
        return read
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0L) return 0L
        val buffer = ByteArray(minOf(8_192L, byteCount).toInt())
        var skipped = 0L
        while (skipped < byteCount) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), byteCount - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }
}
