package app.galaxyvitals.analysis

import android.content.Context
import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.EcgFounderPreprocess
import app.galaxyvitals.data.protocol.Nao3Preprocess
import app.galaxyvitals.data.protocol.NaoDecision
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.data.protocol.SignalQualityReport
import app.galaxyvitals.domain.AnalysisStatus
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

data class AnalysisResult(
    val status: AnalysisStatus,
    val decision: NaoDecision?,
    val note: String,
    val quality: SignalQualityReport? = null,
    val ecgHrMedian: Double? = null,
    val analysisBundleId: String? = null,
)

class EcgRhythmEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val bundle: EcgAnalysisBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EcgAnalysisBundle.load(appContext)
    }
    private val modelBuffer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        mapAsset(bundle.model.path)
    }
    private val interpreterLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createInterpreter)
    private val inputBuffer = ByteBuffer.allocateDirect(INPUT_VALUE_COUNT * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = Array(1) { FloatArray(OUTPUT_VALUE_COUNT) }

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
                analysisBundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
            )
        }

        val modelInput = Nao3Preprocess.prepare(parsed)
        val decision = infer(modelInput)
        return AnalysisResult(
            status = AnalysisStatus.OK,
            decision = decision,
            note = qualityNote(quality, prepared.windows.size),
            quality = quality,
            ecgHrMedian = beat.bpmMedian,
            analysisBundleId = bundle.compatibilityId,
        )
    }

    @Synchronized
    private fun infer(input: FloatArray): NaoDecision {
        require(input.size == INPUT_VALUE_COUNT) {
            "NAO3 input must contain exactly $INPUT_VALUE_COUNT samples"
        }
        require(input.all(Float::isFinite)) { "NAO3 input must be finite" }

        inputBuffer.clear()
        input.forEach(inputBuffer::putFloat)
        inputBuffer.rewind()
        interpreterLazy.value.run(inputBuffer, outputBuffer)
        return Nao3Postprocessor.fromLogits(outputBuffer[0])
    }

    private fun createInterpreter(): Interpreter {
        val verifiedBundle = bundle
        val options = Interpreter.Options().apply {
            setNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        }
        val runtime = Interpreter(modelBuffer, options)
        try {
            runtime.allocateTensors()
            require(runtime.inputTensorCount == 1) { "NAO3 must have exactly one input tensor" }
            require(runtime.outputTensorCount == 1) { "NAO3 must have exactly one output tensor" }
            val inputTensor = runtime.getInputTensor(0)
            val outputTensor = runtime.getOutputTensor(0)
            require(inputTensor.shape().contentEquals(verifiedBundle.input.shape.toIntArray())) {
                "Unexpected NAO3 input shape: ${inputTensor.shape().contentToString()}"
            }
            require(outputTensor.shape().contentEquals(verifiedBundle.output.shape.toIntArray())) {
                "Unexpected NAO3 output shape: ${outputTensor.shape().contentToString()}"
            }
            require(inputTensor.dataType() == DataType.FLOAT32) {
                "NAO3 input must be FLOAT32"
            }
            require(outputTensor.dataType() == DataType.FLOAT32) {
                "NAO3 output must be FLOAT32"
            }
            return runtime
        } catch (error: Exception) {
            runtime.close()
            throw error
        }
    }

    private fun mapAsset(path: String): MappedByteBuffer {
        return appContext.assets.openFd(path).use { descriptor ->
            require(descriptor.declaredLength > 0L) { "NAO3 model asset is empty" }
            descriptor.createInputStream().channel.use { channel ->
                channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
    }

    @Synchronized
    override fun close() {
        if (interpreterLazy.isInitialized()) interpreterLazy.value.close()
    }

    private fun qualityNote(quality: SignalQualityReport, windowCount: Int): String {
        return "On-device N/A/O rhythm model · full-record analysis · " +
            "$windowCount clean quality window(s) · " +
            "${"%.0f".format(Locale.ROOT, quality.cleanCoveragePct)}% clean"
    }

    companion object {
        private const val INPUT_VALUE_COUNT = 7_680
        private const val OUTPUT_VALUE_COUNT = 3
    }
}

fun NaoLabel.displayName(): String = when (this) {
    NaoLabel.N -> "Normal"
    NaoLabel.A -> "Atrial fibrillation"
    NaoLabel.O -> "Other rhythm"
}

fun NaoLabel.shortHelp(): String = when (this) {
    NaoLabel.N -> "Looks like sinus / normal rhythm on this single-lead recording."
    NaoLabel.A -> "Irregular rhythm consistent with AF or flutter. This is a screen, not a diagnosis."
    NaoLabel.O -> "Not clearly normal sinus and not clearly AF. A clinician should review the strip."
}
