package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Opt-in PhysioNet engineering benchmark. Skipped unless
 * `GALAXYVITALS_PHYSIONET_BENCHMARK=1` and `_analysis/ecg_benchmark` is prepared.
 * Default `.\gradlew.bat :protocol:test` must not download PhysioNet or fail.
 */
class PhysioNetBpmBenchmarkTest {
    private lateinit var records: List<PreparedRecord>

    @Before
    fun skipUnlessOptInAndPrepared() {
        val enabled = System.getenv(ENV_FLAG) == "1"
        val manifest = File(repoRoot(), "_analysis/ecg_benchmark/manifest.csv")
        Assume.assumeTrue(
            "set $ENV_FLAG=1 and run tools/ecg_benchmark/prepare_physionet.py",
            enabled && manifest.isFile,
        )
        records = readManifest(manifest)
        Assume.assumeTrue("manifest.csv has no records", records.isNotEmpty())
    }

    @Test
    fun mitbihNonPaced_rPeakSensitivityPpvAndHrMae() {
        val mitdb = records.filter { it.dataset == "mitdb" }
        Assume.assumeTrue("no prepared MIT-BIH records", mitdb.isNotEmpty())
        val metrics = evaluate(mitdb)
        assertThat(metrics.tp + metrics.fn).isGreaterThan(0)
        assertThat(metrics.sensitivity).isAtLeast(0.99)
        assertThat(metrics.ppv).isAtLeast(0.99)
        assertThat(metrics.acceptedWithGt).isGreaterThan(0)
        assertThat(metrics.medianHrMae).isAtMost(2.0)
    }

    @Test
    fun nstdbSnrAtLeast12db_coverageAndHrMae() {
        val cleanish = records.filter { it.dataset == "nstdb" && it.snrDb != null && it.snrDb >= 12 }
        Assume.assumeTrue("no prepared NSTDB records with SNR >= 12 dB", cleanish.isNotEmpty())
        val metrics = evaluate(cleanish)
        assertThat(metrics.scoredWindows).isGreaterThan(0)
        assertThat(metrics.coverage).isAtLeast(0.80)
        assertThat(metrics.acceptedWithGt).isGreaterThan(0)
        assertThat(metrics.medianHrMae).isAtMost(5.0)
    }

    @Test
    fun nstdbHighNoise_reportedErrorRate() {
        val noisy = records.filter { it.dataset == "nstdb" && it.snrDb != null && it.snrDb < 12 }
        Assume.assumeTrue("no prepared high-noise NSTDB records", noisy.isNotEmpty())
        val metrics = evaluate(noisy)
        assertThat(metrics.largeErrorFraction).isAtMost(0.05)
    }

    private fun evaluate(subset: List<PreparedRecord>): Metrics {
        var tp = 0
        var fp = 0
        var fn = 0
        var scoredWindows = 0
        var accepted = 0
        val errors = ArrayList<Double>()
        var largeErrors = 0
        for (record in subset) {
            require(record.signalFile.isFile) { "missing ${record.signalFile.path}" }
            require(record.beatsFile.isFile) { "missing ${record.beatsFile.path}" }
            val samples = readFloat32Le(record.signalFile)
            val beats = readBeats(record.beatsFile)
            val windowSamples = record.srHz * WINDOW_SECONDS
            var start = 0
            while (start + windowSamples <= samples.size) {
                val windowStartMs = start * 1000L / record.srHz
                val windowEndMs = (start + windowSamples) * 1000L / record.srHz
                val slice = samples.copyOfRange(start, start + windowSamples)
                val result = EcgBeatAnalyzer.analyzeWindow(slice, record.srHz, record.signFactor)
                val scoreFrom = windowStartMs + WARMUP_MS
                val scoreTo = windowEndMs - TAIL_MS
                val reference = beats.filter { it in scoreFrom until scoreTo }
                val detected = result.primaryPeaks.map { peak ->
                    windowStartMs + peak * 1000L / record.srHz
                }.filter { it in scoreFrom until scoreTo }
                val match = matchPeaks(reference, detected, MATCH_TOLERANCE_MS)
                tp += match.first
                fp += match.second
                fn += match.third

                val gtBpm = annotationBpm(beats.filter { it in windowStartMs until windowEndMs })
                if (gtBpm != null) {
                    scoredWindows += 1
                    val reported = result.status == EcgBpmStatus.RELIABLE && result.bpmMedian != null
                    if (reported) {
                        accepted += 1
                        val error = abs(result.bpmMedian!! - gtBpm)
                        errors += error
                        if (error > 10.0) largeErrors += 1
                    }
                }
                start += windowSamples
            }
        }
        return Metrics(
            tp = tp,
            fp = fp,
            fn = fn,
            scoredWindows = scoredWindows,
            acceptedWithGt = accepted,
            medianHrMae = median(errors),
            largeErrorFraction = if (accepted == 0) 0.0 else largeErrors.toDouble() / accepted,
        )
    }

    private data class PreparedRecord(
        val dataset: String,
        val recordId: String,
        val snrDb: Int?,
        val signFactor: Int,
        val srHz: Int,
        val signalFile: File,
        val beatsFile: File,
    )

    private data class Metrics(
        val tp: Int,
        val fp: Int,
        val fn: Int,
        val scoredWindows: Int,
        val acceptedWithGt: Int,
        val medianHrMae: Double,
        val largeErrorFraction: Double,
    ) {
        val sensitivity: Double get() = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
        val ppv: Double get() = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        val coverage: Double get() = if (scoredWindows == 0) 0.0 else acceptedWithGt.toDouble() / scoredWindows
    }

    private companion object {
        const val ENV_FLAG = "GALAXYVITALS_PHYSIONET_BENCHMARK"
        const val WINDOW_SECONDS = 10
        const val WARMUP_MS = 1_000L
        const val TAIL_MS = 200L
        const val MATCH_TOLERANCE_MS = 150L
        const val MIN_RR_MS = 333.0
        const val MAX_RR_MS = 1_500.0
        const val MIN_RR_COUNT = 4
        const val MIN_BPM = 40.0
        const val MAX_BPM = 180.0

        fun repoRoot(): File {
            var dir = File(System.getProperty("user.dir")).canonicalFile
            while (true) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
            }
        }

        fun readManifest(manifest: File): List<PreparedRecord> {
            val root = manifest.parentFile
            val lines = manifest.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            require(lines.isNotEmpty()) { "empty manifest" }
            val header = lines.first().split(',')
            val index = header.withIndex().associate { it.value to it.index }
            fun col(name: String): Int = index[name] ?: error("manifest missing column $name")
            val datasetCol = col("dataset")
            val recordCol = col("record_id")
            val pathCol = col("path")
            val snrCol = col("snr_db")
            val signCol = col("sign_factor")
            val srCol = col("sr_hz")
            return lines.drop(1).map { line ->
                val cells = line.split(',')
                val relative = File(root, cells[pathCol])
                PreparedRecord(
                    dataset = cells[datasetCol],
                    recordId = cells[recordCol],
                    snrDb = cells[snrCol].toIntOrNull(),
                    signFactor = cells[signCol].toInt(),
                    srHz = cells[srCol].toInt(),
                    signalFile = File(relative, "signal.f32"),
                    beatsFile = File(relative, "beats.csv"),
                )
            }
        }

        fun readFloat32Le(file: File): FloatArray {
            FileInputStream(file).channel.use { channel ->
                val size = channel.size()
                require(size % 4L == 0L) { "signal.f32 length is not a multiple of 4: ${file.path}" }
                val bytes = ByteArray(size.toInt())
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break
                }
                buffer.flip()
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val floats = buffer.asFloatBuffer()
                val out = FloatArray(floats.remaining())
                floats.get(out)
                return out
            }
        }

        fun readBeats(file: File): List<Long> {
            return file.readLines(Charsets.UTF_8).drop(1).mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                line.split(',').first().toLong()
            }
        }

        fun matchPeaks(referenceMs: List<Long>, detectedMs: List<Long>, toleranceMs: Long): Triple<Int, Int, Int> {
            val ref = referenceMs.sorted()
            val det = detectedMs.sorted()
            var i = 0
            var j = 0
            var tp = 0
            while (i < ref.size && j < det.size) {
                val delta = det[j] - ref[i]
                when {
                    delta < -toleranceMs -> j++
                    delta > toleranceMs -> i++
                    else -> {
                        tp++
                        i++
                        j++
                    }
                }
            }
            return Triple(tp, det.size - tp, ref.size - tp)
        }

        fun annotationBpm(timesMs: List<Long>): Double? {
            if (timesMs.size < MIN_RR_COUNT + 1) return null
            val sorted = timesMs.sorted()
            val rr = ArrayList<Double>(sorted.size - 1)
            for (index in 1 until sorted.size) {
                val interval = (sorted[index] - sorted[index - 1]).toDouble()
                if (interval in MIN_RR_MS..MAX_RR_MS) rr += interval
            }
            if (rr.size < MIN_RR_COUNT) return null
            val bpm = 60_000.0 / median(rr)
            return if (bpm in MIN_BPM..MAX_BPM) bpm else null
        }

        fun median(values: List<Double>): Double {
            if (values.isEmpty()) return Double.NaN
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) / 2.0
            }
        }
    }
}
