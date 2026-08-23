package app.galaxyvitals.data.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class EcgWearContractTest {

    @Test
    fun strictSessionIdsRoundTripThroughPathsAndFileNames() {
        val sessionId = "A_1700.test-1"

        assertThat(EcgWearContract.requireSessionId(sessionId)).isEqualTo(sessionId)
        assertThat(EcgWearContract.sessionPath(sessionId)).isEqualTo("/ecg/session/$sessionId")
        assertThat(EcgWearContract.cleanupPath(sessionId)).isEqualTo("/ecg/cleanup/$sessionId")
        assertThat(EcgWearContract.deletePath(sessionId)).isEqualTo("/ecg/delete/$sessionId")
        assertThat(EcgWearContract.inboxFileName(sessionId)).isEqualTo("ecg_$sessionId.csv.gz")
        assertThat(EcgWearContract.sessionIdFromFileName("ecg_$sessionId.csv.gz"))
            .isEqualTo(sessionId)
    }

    @Test
    fun pathBuildersRejectTraversalSeparatorsAndOverlongIds() {
        val invalid = listOf(
            "",
            ".hidden",
            "../escape",
            "bad/name",
            "bad\\name",
            "bad name",
            "a".repeat(EcgWearContract.MAX_SESSION_ID_LENGTH + 1),
        )

        invalid.forEach { sessionId ->
            assertThrows(IllegalArgumentException::class.java) {
                EcgWearContract.sessionPath(sessionId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                EcgWearContract.cleanupPath(sessionId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                EcgWearContract.deletePath(sessionId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                EcgWearContract.inboxFileName(sessionId)
            }
        }
    }

    @Test
    fun sanitizesOnlyTheFinalFilenameSegment() {
        assertThat(
            EcgWearContract.sanitizeSessionId("ecg_bad/../../42.csv.gz", "import"),
        ).isEqualTo("42")
        assertThat(
            EcgWearContract.sanitizeSessionId("C:\\shared\\patient one.csv", "import"),
        ).isEqualTo("patient_one")
        assertThat(EcgWearContract.sanitizeSessionId("...", "import"))
            .isEqualTo("import")
        assertThat(EcgWearContract.sanitizeSessionId("ecg_ABC-12.csv.gz", "import"))
            .isEqualTo("ABC-12")
    }

    @Test
    fun strictFilenameParserRejectsPathSegments() {
        assertThrows(IllegalArgumentException::class.java) {
            EcgWearContract.sessionIdFromFileName("../ecg_42.csv.gz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EcgWearContract.sessionIdFromFileName("folder/ecg_42.csv.gz")
        }
    }
}
