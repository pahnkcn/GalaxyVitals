package app.galaxyvitals.analysis

import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.EcgFounderPreprocess
import app.galaxyvitals.data.protocol.Nao3Preprocess
import app.galaxyvitals.data.protocol.NaoDecision
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.data.protocol.PreparedRecording
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
    fun analyze(parsed: ParsedEcgFile, classify: (FloatArray) -> Nao3Verdict): AnalysisResult {
        val prepared = EcgFounderPreprocess.prepare(parsed)
        val beat = EcgBeatAnalyzer.analyze(parsed, prepared)
        val window = Nao3Preprocess.selectExactWindow(parsed)
        if (window == null) {
            val tooShort = parsed.srHz <= 0 ||
                parsed.samples.size < parsed.srHz * Nao3Preprocess.DURATION_SECONDS
            return if (tooShort) {
                indeterminateResult(
                    quality = prepared.quality,
                    ecgHrMedian = beat.bpmMedian,
                    note = "Recording is shorter than 30 seconds; rhythm model abstained.",
                )
            } else {
                AnalysisResult(
                    status = AnalysisStatus.LOW_QUALITY,
                    decision = null,
                    note = "Low ECG quality: no continuous 30-second interval",
                    quality = prepared.quality,
                    ecgHrMedian = beat.bpmMedian,
                    analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
                )
            }
        }
        val intervalPrepared = EcgFounderPreprocess.prepare(window)
        if (!isExactCleanNao3Interval(intervalPrepared)) {
            return AnalysisResult(
                status = AnalysisStatus.LOW_QUALITY,
                decision = null,
                note = "Low ECG quality: contaminated 30-second model interval",
                quality = prepared.quality,
                ecgHrMedian = beat.bpmMedian,
                analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
            )
        }
        return try {
            val input = try {
                Nao3Preprocess.prepareExact(window)
            } catch (error: Exception) {
                throw ModelAnalysisException(ModelFailureStage.MODEL_PREPROCESS, error)
            }
            when (val verdict = classify(input)) {
                is Nao3Verdict.Classified -> successfulResult(
                    verdict.decision,
                    prepared.quality,
                    beat.bpmMedian,
                    qualityNote(intervalPrepared.quality, intervalPrepared.windows.size),
                )
                Nao3Verdict.Indeterminate -> indeterminateResult(
                    quality = prepared.quality,
                    ecgHrMedian = beat.bpmMedian,
                    note = "Rhythm model abstained (below decision policy).",
                )
            }
        } catch (error: PolicyIntegrityException) {
            indeterminateResult(
                quality = prepared.quality,
                ecgHrMedian = beat.bpmMedian,
                note = "Rhythm decision policy is missing or does not match the analysis bundle.",
            )
        } catch (error: Exception) {
            if (error.cause is PolicyIntegrityException) {
                return indeterminateResult(
                    quality = prepared.quality,
                    ecgHrMedian = beat.bpmMedian,
                    note = "Rhythm decision policy is missing or does not match the analysis bundle.",
                )
            }
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

internal fun isExactCleanNao3Interval(prepared: PreparedRecording): Boolean {
    val expectedHops = 1 + (
        (Nao3Preprocess.DURATION_SECONDS * 1_000L - EcgFounderPreprocess.WINDOW_MS) /
            EcgFounderPreprocess.HOP_MS
        ).toInt()
    return prepared.windows.size >= expectedHops && prepared.quality.segments.size == 1
}

internal fun indeterminateResult(
    quality: SignalQualityReport?,
    ecgHrMedian: Double?,
    note: String,
): AnalysisResult = AnalysisResult(
    status = AnalysisStatus.INDETERMINATE,
    decision = null,
    note = note,
    quality = quality,
    ecgHrMedian = ecgHrMedian,
    analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
)

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
    return "On-device N/A/O rhythm model · 30-second interval · " +
        "$windowCount clean quality window(s) · " +
        "${"%.0f".format(Locale.ROOT, quality.cleanCoveragePct)}% clean"
}
