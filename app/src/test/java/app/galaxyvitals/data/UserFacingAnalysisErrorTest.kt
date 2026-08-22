package app.galaxyvitals.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserFacingAnalysisErrorTest {
    @Test
    fun convIntegerBecomesFriendlyMessage() {
        val err = IllegalStateException(
            "Error code - ORT_NOT_IMPLEMENTED - message: Could not find an implementation for ConvInteger(10)",
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
}
