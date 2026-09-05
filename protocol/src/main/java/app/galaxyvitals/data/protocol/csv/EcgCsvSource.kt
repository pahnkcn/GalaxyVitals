package app.galaxyvitals.data.protocol.csv

import java.io.InputStream
import java.io.PushbackInputStream

/** A bounded stream plus what the magic bytes at its head said about it. */
internal class SniffedStream(val stream: InputStream, val gzip: Boolean)

/**
 * Opening an ECG payload: bound it, then decide whether it is gzipped.
 *
 * An ECG file arrives either from the watch over the Data Layer or from a
 * user-chosen document, and neither is trusted to declare its own encoding
 * honestly, so the decision is made from the bytes. Both entry points wanted
 * the same peek-without-consuming dance, and it used to be written out twice.
 */
internal object EcgCsvSource {

    private val GZIP_MAGIC = byteArrayOf(0x1f, 0x8b.toByte())

    fun looksGzipped(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    /**
     * Wraps [input] so it can never yield more than [maxBytes], reporting itself
     * as [description] when it refuses.
     */
    fun bound(input: InputStream, maxBytes: Long, description: String): InputStream =
        LimitedInputStream(input, maxBytes, description)

    /**
     * Bounds [input] at [maxCompressedBytes] and reads the gzip magic back off
     * the front, so the returned stream still starts at byte zero.
     */
    fun sniffGzip(input: InputStream, maxCompressedBytes: Long): SniffedStream {
        val bounded = bound(input, maxCompressedBytes, COMPRESSED_DESCRIPTION)
        val pushback = PushbackInputStream(bounded, GZIP_MAGIC.size)
        val prefix = ByteArray(GZIP_MAGIC.size)
        var count = 0
        while (count < prefix.size) {
            val read = pushback.read(prefix, count, prefix.size - count)
            if (read < 0) break
            count += read
        }
        if (count > 0) pushback.unread(prefix, 0, count)
        val gzip = count == GZIP_MAGIC.size && prefix.contentEquals(GZIP_MAGIC)
        return SniffedStream(pushback, gzip)
    }

    const val COMPRESSED_DESCRIPTION = "ECG compressed data"
    const val UNCOMPRESSED_DESCRIPTION = "ECG uncompressed data"
}
