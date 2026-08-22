package app.galaxyvitals.analysis

import android.content.Context
import android.util.AtomicFile
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.galaxyvitals.data.protocol.EcgFounderLabels
import app.galaxyvitals.data.protocol.EcgFounderPreprocess
import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.NaoDecision
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.data.protocol.SignalQualityReport
import app.galaxyvitals.domain.AnalysisStatus
import java.io.File
import java.nio.FloatBuffer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class AnalysisResult(
    val status: AnalysisStatus,
    val decision: NaoDecision?,
    val note: String,
    val quality: SignalQualityReport? = null,
    val ecgHrMedian: Double? = null,
    val analysisBundleId: String? = null,
)

class EcgFounderEngine(private val context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val sessionRef = AtomicReference<OrtSession?>()
    private val bundle: AnalysisBundle by lazy { AnalysisBundle.load(context) }
    private val head: NaoLinearHead by lazy { NaoCalibrator.load(context) }

    fun analyze(parsed: ParsedEcgFile): AnalysisResult {
        val prepared = EcgFounderPreprocess.prepare(parsed)
        val quality = prepared.quality
        val beat = EcgBeatAnalyzer.analyze(parsed, prepared)
        if (!quality.usableForAnalysis) {
            return AnalysisResult(
                status = AnalysisStatus.LOW_QUALITY,
                decision = null,
                note = "Low ECG quality: ${quality.flags.joinToString { it.name }}",
                quality = quality,
                ecgHrMedian = beat.bpmMedian,
            )
        }
        val verifiedBundle = bundle
        val session = session(verifiedBundle) ?: return AnalysisResult(
            status = AnalysisStatus.FAILED,
            decision = null,
            note = "Rhythm model is not installed in this build.",
            quality = quality,
            ecgHrMedian = beat.bpmMedian,
            analysisBundleId = verifiedBundle.compatibilityId,
        )
        val acc = FloatArray(EcgFounderLabels.ALL.size)
        val windowDecisions = ArrayList<NaoDecision>(prepared.windows.size)
        for (window in prepared.windows) {
            val probs = infer(session, window.samples)
            for (i in acc.indices) acc[i] += probs[i]
            windowDecisions += EcgFounderLabels.decideLogistic(probs, head.coef, head.intercept)
        }
        val n = prepared.windows.size.toFloat()
        for (i in acc.indices) acc[i] /= n
        val decision = EcgFounderLabels.decideLogistic(acc, head.coef, head.intercept)
        val consensus = windowDecisions.count { it.label == decision.label }.toFloat() / windowDecisions.size
        val classThreshold = verifiedBundle.classThresholds.getValue(decision.label)
        if (decision.confidence < classThreshold || consensus < verifiedBundle.minimumWindowConsensus) {
            return AnalysisResult(
                status = AnalysisStatus.INDETERMINATE,
                decision = null,
                note = "Model abstained: score or window consensus was below the validated proxy threshold",
                quality = quality,
                ecgHrMedian = beat.bpmMedian,
                analysisBundleId = verifiedBundle.compatibilityId,
            )
        }
        return AnalysisResult(
            status = AnalysisStatus.OK,
            decision = decision,
            note = qualityNote(quality, prepared.windows.size, consensus),
            quality = quality,
            ecgHrMedian = beat.bpmMedian,
            analysisBundleId = verifiedBundle.compatibilityId,
        )
    }

    private fun qualityNote(quality: SignalQualityReport, windowCount: Int, consensus: Float): String {
        return "ECGFounder 1-lead · calibrated N/A/O · $windowCount clean window(s) · " +
            "${"%.0f".format(Locale.ROOT, quality.cleanCoveragePct)}% clean · " +
            "${"%.0f".format(Locale.ROOT, consensus * 100)}% consensus"
    }

    @Synchronized
    private fun session(bundle: AnalysisBundle): OrtSession? {
        val file = ensureModelFile(bundle) ?: return null
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

    private fun ensureModelFile(bundle: AnalysisBundle): File? {
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
        val stale = !dest.exists() || dest.length() == 0L || dest.length() != assetLen ||
            runCatching { bundle.verifyModelFile(dest) }.isFailure
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
        bundle.verifyModelFile(dest)
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
