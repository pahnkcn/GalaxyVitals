package app.healthtrack.analysis

import android.content.Context
import android.util.AtomicFile
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.healthtrack.data.protocol.EcgFounderLabels
import app.healthtrack.data.protocol.EcgFounderPreprocess
import app.healthtrack.data.protocol.NaoDecision
import app.healthtrack.data.protocol.NaoLabel
import app.healthtrack.data.protocol.ParsedEcgFile
import app.healthtrack.data.protocol.SignalQuality
import app.healthtrack.domain.AnalysisStatus
import java.io.File
import java.nio.FloatBuffer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class AnalysisResult(
    val status: AnalysisStatus,
    val decision: NaoDecision?,
    val note: String,
)

class EcgFounderEngine(private val context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val sessionRef = AtomicReference<OrtSession?>()
    private val head: NaoLinearHead? = NaoCalibrator.load(context)

    fun analyze(parsed: ParsedEcgFile): AnalysisResult {
        if (parsed.samples.size < parsed.srHz * MIN_ANALYSIS_SECONDS ||
            parsed.durationSec < MIN_ANALYSIS_DURATION_SECONDS
        ) {
            return AnalysisResult(
                status = AnalysisStatus.LOW_QUALITY,
                decision = null,
                note = "Recording is too short for a rhythm result",
            )
        }
        val windows = EcgFounderPreprocess.windows(parsed.samples, parsed.srHz)
        if (windows.isEmpty()) {
            return AnalysisResult(AnalysisStatus.FAILED, null, "No ECG samples to analyse")
        }
        val quality = EcgFounderPreprocess.quality(windows, parsed.usablePct)
        if (!quality.usable) {
            return AnalysisResult(
                status = AnalysisStatus.LOW_QUALITY,
                decision = null,
                note = quality.reason,
            )
        }
        val session = session() ?: return AnalysisResult(
            status = AnalysisStatus.FAILED,
            decision = null,
            note = "Rhythm model is not installed in this build.",
        )
        val acc = FloatArray(EcgFounderLabels.ALL.size)
        for (window in windows) {
            val probs = infer(session, window.samples)
            for (i in acc.indices) acc[i] += probs[i]
        }
        val n = windows.size.toFloat()
        for (i in acc.indices) acc[i] /= n
        val decision = if (head != null) {
            EcgFounderLabels.decideLogistic(acc, head.coef, head.intercept)
        } else {
            EcgFounderLabels.decide(acc)
        }
        return AnalysisResult(
            status = AnalysisStatus.OK,
            decision = decision,
            note = qualityNote(quality, windows.size),
        )
    }

    private fun qualityNote(quality: SignalQuality, windowCount: Int): String {
        val headNote = if (head != null) "calibrated N/A/O" else "rule N/A/O"
        return "ECGFounder 1-lead · $headNote · $windowCount window(s) · RMS ${"%.3f".format(Locale.ROOT, quality.rms)} mV"
    }

    @Synchronized
    private fun session(): OrtSession? {
        val file = ensureModelFile() ?: return null
        sessionRef.get()?.let { return it }
        val created = try {
            OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(maxOf(2, Runtime.getRuntime().availableProcessors() / 2))
                env.createSession(file.absolutePath, opts)
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        sessionRef.set(created)
        return created
    }

    private fun ensureModelFile(): File? {
        val dest = File(context.filesDir, "ecgfounder_1lead.onnx")
        val assetLen = try {
            context.assets.openFd(MODEL_ASSET).use { it.length }
        } catch (_: Exception) {
            -1L
        }
        if (assetLen <= 0L) {
            sessionRef.getAndSet(null)?.close()
            return null
        }
        val stale = !dest.exists() || dest.length() == 0L ||
            dest.length() != assetLen
        if (stale) {
            sessionRef.getAndSet(null)?.close()
            try {
                dest.parentFile?.mkdirs()
                context.assets.open(MODEL_ASSET).use { input ->
                    val atomic = AtomicFile(dest)
                    val output = atomic.startWrite()
                    try {
                        input.copyTo(output)
                        atomic.finishWrite(output)
                    } catch (error: Exception) {
                        atomic.failWrite(output)
                        throw error
                    }
                }
            } catch (_: Exception) {
                return null
            }
        }
        return dest.takeIf { it.exists() && it.length() > 0L }
    }

    private fun infer(session: OrtSession, window: FloatArray): FloatArray {
        val shape = longArrayOf(1, 1, window.size.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), shape)
        tensor.use {
            session.run(Collections.singletonMap("ecg", it)).use { result ->
                val raw = result[0].value
                val probabilities = flattenProbs(raw)
                check(probabilities.size == EcgFounderLabels.ALL.size) {
                    "Unexpected ECGFounder output size: ${probabilities.size}"
                }
                check(probabilities.all { it.isFinite() && it in 0f..1f }) {
                    "ECGFounder returned an invalid probability"
                }
                return probabilities
            }
        }
    }

    private fun flattenProbs(raw: Any): FloatArray {
        return when (raw) {
            is Array<*> -> {
                val first = raw.firstOrNull()
                when (first) {
                    is FloatArray -> first
                    is Array<*> -> flattenProbs(first)
                    else -> error("Unexpected ECGFounder output element type")
                }
            }
            is FloatArray -> raw
            else -> error("Unexpected ECGFounder output type")
        }
    }

    companion object {
        const val MODEL_ASSET = "ecg/ecgfounder_1lead.onnx"
        private const val MIN_ANALYSIS_SECONDS = 5
        private const val MIN_ANALYSIS_DURATION_SECONDS = 4.5
    }
}

fun NaoLabel.displayName(): String = when (this) {
    NaoLabel.N -> "Normal"
    NaoLabel.A -> "Atrial fibrillation"
    NaoLabel.O -> "Other rhythm"
}

fun NaoLabel.shortHelp(): String = when (this) {
    NaoLabel.N -> "Looks like sinus / normal rhythm on this single-lead window."
    NaoLabel.A -> "Irregular rhythm consistent with AF or flutter. This is a screen, not a diagnosis."
    NaoLabel.O -> "Not clearly normal sinus and not clearly AF. A clinician should review the strip."
}
