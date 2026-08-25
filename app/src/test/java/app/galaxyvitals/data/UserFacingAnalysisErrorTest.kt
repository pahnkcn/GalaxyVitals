package app.galaxyvitals.data

import app.galaxyvitals.analysis.EcgAnalysisBundle
import app.galaxyvitals.analysis.ModelFailureStage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserFacingAnalysisErrorTest {
    @Test
    fun nao3RuntimeFailureBecomesFriendlyMessage() {
        val err = IllegalStateException(
            "Unexpected NAO3 input shape: [1, 1, 7680]",
        )
        assertThat(userFacingAnalysisError(err)).isEqualTo("Rhythm model could not run on this device.")
    }

    @Test
    fun blankFallsBack() {
        assertThat(userFacingAnalysisError(RuntimeException())).isEqualTo("Analysis failed.")
    }

    @Test
    fun importEmptyAndMissingMetaAreFriendly() {
        assertThat(userFacingImportError(IllegalStateException("Empty file")))
            .isEqualTo("That file is empty.")
        assertThat(userFacingImportError(IllegalStateException("Missing #meta header")))
            .isEqualTo("Not an ECG recording (missing #meta header).")
        assertThat(userFacingImportError(IllegalStateException("No ECG samples")))
            .isEqualTo("That file has no ECG samples.")
    }

    @Test
    fun arbitraryExceptionDetailsAreNotExposed() {
        val secretPath = "C:\\private\\patient-123\\ecg.csv.gz"
        assertThat(userFacingImportError(IllegalStateException("failed at $secretPath")))
            .isEqualTo("Import failed.")
        assertThat(userFacingAnalysisError(IllegalStateException("failed at $secretPath")))
            .isEqualTo("Analysis failed.")
    }

    @Test
    fun importLimitsAndInvalidMetadataHaveFixedMessages() {
        assertThat(userFacingImportError(IllegalStateException("decoded data exceeds 123 bytes")))
            .isEqualTo("That ECG file is too large.")
        assertThat(userFacingImportError(IllegalStateException("Unsupported ECG row format")))
            .isEqualTo("That file has invalid ECG metadata.")
        assertThat(userFacingImportError(IllegalStateException("Invalid gzip ECG data")))
            .isEqualTo("That file is not a valid gzip ECG.")
    }

    @Test
    fun analysisFailureLogMessageIncludesStageAndBundleWithoutMillivolts() {
        val leak = "0.12mV,1.3,csv row..."
        val error = IllegalStateException(leak)
        val message = analysisFailureLogMessage(
            stage = ModelFailureStage.INFERENCE,
            bundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
            error = error,
        )

        assertThat(message).contains(ModelFailureStage.INFERENCE.name)
        assertThat(message).contains(EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID)
        assertThat(message).contains(error.javaClass.simpleName)
        assertThat(message).doesNotContain(leak)
        assertThat(message).doesNotContain("0.12mV")
        assertThat(message).doesNotContain("1.3")
        assertThat(message).doesNotContain("csv row")
        assertThat(message).doesNotContain("mV")
    }

    @Test
    fun analysisFailureLogThrowableKeepsStackWithoutOriginalMessage() {
        val leak = "0.12mV,1.3,csv row..."
        val error = IllegalStateException(leak)
        val logged = analysisFailureLogThrowable(
            stage = ModelFailureStage.INFERENCE,
            bundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
            error = error,
        )
        val printed = logged.stackTraceToString()
        val expected = analysisFailureLogMessage(
            stage = ModelFailureStage.INFERENCE,
            bundleId = EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
            error = error,
        )

        assertThat(logged.message).isEqualTo(expected)
        assertThat(logged.cause).isNotNull()
        assertThat(logged.cause!!.stackTrace).isEqualTo(error.stackTrace)
        assertThat(printed).doesNotContain(leak)
        assertThat(printed).doesNotContain("0.12mV")
        assertThat(printed).doesNotContain("csv row")
    }
}
