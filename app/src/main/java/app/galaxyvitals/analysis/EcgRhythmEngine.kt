package app.galaxyvitals.analysis

import android.content.Context
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

data class AnalysisResult(
    val status: AnalysisStatus,
    val decision: NaoDecision?,
    val note: String,
    val quality: SignalQualityReport? = null,
    val ecgHrMedian: Double? = null,
    val analysisBundleId: String? = null,
    val failureStage: ModelFailureStage? = null,
    val cause: Throwable? = null,
)

class EcgRhythmEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val bundle: EcgAnalysisBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            EcgAnalysisBundle.load(appContext)
        } catch (error: PolicyIntegrityException) {
            throw error
        } catch (error: ModelAnalysisException) {
            throw error
        } catch (error: Exception) {
            throw ModelAnalysisException(ModelFailureStage.BUNDLE_LOAD, error)
        }
    }
    private val modelBuffer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            mapAsset(bundle.model.path)
        } catch (error: PolicyIntegrityException) {
            throw error
        } catch (error: ModelAnalysisException) {
            throw error
        } catch (error: Exception) {
            throw ModelAnalysisException(ModelFailureStage.BUNDLE_LOAD, error)
        }
    }
    private val interpreterLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::createInterpreter)
    private val inputBuffer = ByteBuffer.allocateDirect(INPUT_VALUE_COUNT * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = Array(1) { FloatArray(OUTPUT_VALUE_COUNT) }

    fun analyze(parsed: ParsedEcgFile): AnalysisResult {
        return EcgRhythmAnalysis.analyze(parsed, ::infer)
    }

    @Synchronized
    private fun infer(input: FloatArray): Nao3Verdict {
        try {
            require(input.size == INPUT_VALUE_COUNT) {
                "NAO3 input must contain exactly $INPUT_VALUE_COUNT samples"
            }
            require(input.all(Float::isFinite)) { "NAO3 input must be finite" }

            val policy = try {
                bundle.decisionPolicy
            } catch (error: PolicyIntegrityException) {
                throw error
            } catch (error: Exception) {
                throw ModelAnalysisException(ModelFailureStage.BUNDLE_LOAD, error)
            }

            inputBuffer.clear()
            input.forEach(inputBuffer::putFloat)
            inputBuffer.rewind()
            interpreterLazy.value.run(inputBuffer, outputBuffer)
            return Nao3Postprocessor.fromLogits(outputBuffer[0], policy)
        } catch (error: PolicyIntegrityException) {
            throw error
        } catch (error: ModelAnalysisException) {
            throw error
        } catch (error: Exception) {
            throw ModelAnalysisException(ModelFailureStage.INFERENCE, error)
        }
    }

    private fun createInterpreter(): Interpreter {
        val verifiedBundle = try {
            bundle
        } catch (error: PolicyIntegrityException) {
            throw error
        } catch (error: ModelAnalysisException) {
            throw error
        } catch (error: Exception) {
            throw ModelAnalysisException(ModelFailureStage.BUNDLE_LOAD, error)
        }
        val mappedModel = try {
            modelBuffer
        } catch (error: PolicyIntegrityException) {
            throw error
        } catch (error: ModelAnalysisException) {
            throw error
        } catch (error: Exception) {
            throw ModelAnalysisException(ModelFailureStage.BUNDLE_LOAD, error)
        }
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
            }
            val runtime = Interpreter(mappedModel, options)
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
        } catch (error: PolicyIntegrityException) {
            throw error
        } catch (error: ModelAnalysisException) {
            throw error
        } catch (error: Exception) {
            throw ModelAnalysisException(ModelFailureStage.INTERPRETER_INIT, error)
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
