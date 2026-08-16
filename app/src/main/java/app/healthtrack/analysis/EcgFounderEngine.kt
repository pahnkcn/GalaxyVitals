package app.healthtrack.analysis

import android.content.Context
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
import java.util.concurrent.atomic.AtomicReference

data class AnalysisResult(
    val status: AnalysisStatus,
    val decision: NaoDecision?,
    val note: String,
)

class EcgFounderEngine(private val context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val sessionRef = AtomicReference<OrtSession?>()

    fun analyze(parsed: ParsedEcgFile): AnalysisResult {
        val windows = EcgFounderPreprocess.windows(parsed.samples, parsed.srHz)
        val quality = EcgFounderPreprocess.quality(windows, parsed.usablePct)
        val session = session() ?: return AnalysisResult(
            status = AnalysisStatus.FAILED,
            decision = null,
            note = "ECGFounder model is not installed. Run tools/ecgfounder/export_ecgfounder.py",
        )
        if (windows.isEmpty()) {
            return AnalysisResult(AnalysisStatus.FAILED, null, "No ECG samples to analyse")
        }
        val acc = FloatArray(EcgFounderLabels.ALL.size)
        for (window in windows) {
            val probs = infer(session, window.samples)
            for (i in acc.indices) acc[i] += probs[i]
        }
        val n = windows.size.toFloat()
        for (i in acc.indices) acc[i] /= n
        val decision = EcgFounderLabels.decide(acc)
        if (!quality.usable) {
            return AnalysisResult(
                status = AnalysisStatus.LOW_QUALITY,
                decision = decision,
                note = quality.reason,
            )
        }
        return AnalysisResult(
            status = AnalysisStatus.OK,
            decision = decision,
            note = qualityNote(quality, windows.size),
        )
    }

    private fun qualityNote(quality: SignalQuality, windowCount: Int): String {
        return "ECGFounder 1-lead · $windowCount window(s) · RMS ${"%.3f".format(quality.rms)} mV"
    }

    @Synchronized
    private fun session(): OrtSession? {
        sessionRef.get()?.let { return it }
        val file = File(context.filesDir, "ecgfounder_1lead.onnx")
        if (!file.exists() || file.length() == 0L) {
            runCatching {
                context.assets.open(MODEL_ASSET).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }.getOrElse { return null }
        }
        if (!file.exists() || file.length() == 0L) return null
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(maxOf(2, Runtime.getRuntime().availableProcessors() / 2))
        val created = env.createSession(file.absolutePath, opts)
        sessionRef.set(created)
        return created
    }

    private fun infer(session: OrtSession, window: FloatArray): FloatArray {
        val shape = longArrayOf(1, 1, window.size.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(window), shape)
        tensor.use {
            session.run(Collections.singletonMap("ecg", it)).use { result ->
                val raw = result[0].value
                return flattenProbs(raw)
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
                    else -> FloatArray(EcgFounderLabels.ALL.size)
                }
            }
            is FloatArray -> raw
            else -> FloatArray(EcgFounderLabels.ALL.size)
        }
    }

    companion object {
        const val MODEL_ASSET = "ecg/ecgfounder_1lead.onnx"
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
