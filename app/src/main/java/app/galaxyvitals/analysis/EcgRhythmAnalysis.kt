package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.EcgFounderPreprocess
import app.galaxyvitals.data.protocol.Nao3Preprocess
import app.galaxyvitals.data.protocol.NaoDecision
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.data.protocol.SignalQualityReport
import app.galaxyvitals.data.userFacingAnalysisError
import app.galaxyvitals.domain.AnalysisStatus
import java.util.Locale

enum class ModelFailureStage {
    BUNDLE_LOAD,
    MODEL_PREPROCESS,
    INTERPRETER_INIT,
    INFERENCE,
}

class ModelAnalysisException(
    val stage: ModelFailureStage,
    cause: Throwable,
) : RuntimeException(cause)

object EcgRhythmAnalysis {
    fun analyze(parsed: ParsedEcgFile, classify: (FloatArray) -> NaoDecision): AnalysisResult {
        val prepared = EcgFounderPreprocess.prepare(parsed)
        val beat = EcgBeatAnalyzer.analyze(parsed, prepared)
        if (!prepared.quality.usableForAnalysis) {
            return AnalysisResult(
                status = AnalysisStatus.LOW_QUALITY,
                decision = null,
                note = "Low ECG quality: ${prepared.quality.flags.joinToString { it.name }}",
                quality = prepared.quality,
                ecgHrMedian = beat.bpmMedian,
                analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
            )
        }
        return try {
            val input = try {
                Nao3Preprocess.prepare(parsed)
            } catch (error: Exception) {
                throw ModelAnalysisException(ModelFailureStage.MODEL_PREPROCESS, error)
            }
            val decision = classify(input)
            successfulResult(
                decision,
                prepared.quality,
                beat.bpmMedian,
                qualityNote(prepared.quality, prepared.windows.size),
            )
        } catch (error: Exception) {
            val stage = (error as? ModelAnalysisException)?.stage ?: ModelFailureStage.INFERENCE
            failedModelResult(
                quality = prepared.quality,
                ecgHrMedian = beat.bpmMedian,
                bundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
                cause = error,
                stage = stage,
            )
        }
    }
}

internal fun successfulResult(
    decision: NaoDecision,
    quality: SignalQualityReport,
    ecgHrMedian: Double?,
    note: String,
): AnalysisResult = AnalysisResult(
    status = AnalysisStatus.OK,
    decision = decision,
    note = note,
    quality = quality,
    ecgHrMedian = ecgHrMedian,
    analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
)

internal fun failedModelResult(
    quality: SignalQualityReport?,
    ecgHrMedian: Double?,
    bundleId: String,
    cause: Throwable,
    stage: ModelFailureStage,
): AnalysisResult = AnalysisResult(
    status = AnalysisStatus.FAILED,
    decision = null,
    note = userFacingAnalysisError(cause),
    quality = quality,
    ecgHrMedian = ecgHrMedian,
    analysisBundleId = bundleId,
    failureStage = stage,
    cause = cause,
)

internal fun qualityNote(quality: SignalQualityReport, windowCount: Int): String {
    return "On-device N/A/O rhythm model · full-record analysis · " +
        "$windowCount clean quality window(s) · " +
        "${"%.0f".format(Locale.ROOT, quality.cleanCoveragePct)}% clean"
}
