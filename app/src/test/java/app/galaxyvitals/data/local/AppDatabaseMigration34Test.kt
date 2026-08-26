package app.galaxyvitals.data.local

import app.galaxyvitals.data.protocol.EcgCsvWriter
import app.galaxyvitals.data.protocol.EcgWearContract
import app.galaxyvitals.data.protocol.LiveBpmSummarizer
import app.galaxyvitals.domain.CaptureSource
import app.galaxyvitals.domain.Wrist
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager

class AppDatabaseMigration34Test {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roomVersionFourRegistersMigrationThreeToFour() {
        assertThat(AppDatabase.MIGRATION_3_4.startVersion).isEqualTo(3)
        assertThat(AppDatabase.MIGRATION_3_4.endVersion).isEqualTo(4)
    }

    @Test
    fun migrationKeepsFilesAndResultsAndRewritesV2SensorTrust() {
        val v2Gzip = EcgCsvWriter.gzipBytes(
            EcgCsvWriter.encodeCaptureV2(
                wallStartMs = 1_700_000_000_000L,
                sensorStartMs = 10_000L,
                valuesMv = floatArrayOf(0.1f, 0.2f),
                relMs = longArrayOf(0L, 2L),
                sampleFlags = intArrayOf(0, 0),
                wrist = Wrist.LEFT,
                signFactor = 1,
                watchInfo = "watch",
                captureSource = CaptureSource.HARDWARE,
            ),
        )
        val v3Gzip = EcgCsvWriter.gzipBytes(
            EcgCsvWriter.encodeCaptureV3(
                wallStartMs = 1_700_000_000_100L,
                sensorStartMs = 20_000L,
                valuesMv = floatArrayOf(-0.12f, -0.11f),
                sampleFlags = intArrayOf(0, 0),
                sensorTimestampsMsRaw = longArrayOf(20_000L, 20_000L),
                batchSequence = intArrayOf(0, 0),
                batchSampleOffset = intArrayOf(0, 1),
                batchSize = intArrayOf(2, 2),
                wrist = Wrist.RIGHT,
                signFactor = -1,
                watchInfo = "watch",
                captureSource = CaptureSource.HARDWARE,
            ),
        )
        val v2File = tmp.newFile("ecg_v2.csv.gz").apply { writeBytes(v2Gzip) }
        val v3File = tmp.newFile("ecg_v3.csv.gz").apply { writeBytes(v3Gzip) }
        val v2Before = v2File.readBytes()
        val v3Before = v3File.readBytes()

        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(V3_CREATE_TABLE)
                statement.executeUpdate(
                    insertV3Row(
                        sessionId = "v2-sensor",
                        filePath = v2File.absolutePath,
                        schemaVersion = 2,
                        timingTrust = "SENSOR",
                        analysisStatus = "OK",
                        naoLabel = "N",
                        findings = "kept",
                        analysisNote = "prior",
                        ecgHrMedian = 72.0,
                        analysisBundleId = "legacy-bundle",
                        payloadSha256 = EcgWearContract.sha256(v2Gzip),
                    ),
                )
                statement.executeUpdate(
                    insertV3Row(
                        sessionId = "v3-seq",
                        filePath = v3File.absolutePath,
                        schemaVersion = 3,
                        timingTrust = "SEQUENCE_RECONSTRUCTED",
                        analysisStatus = "OK",
                        naoLabel = "A",
                        findings = "v3-kept",
                        analysisNote = "v3-prior",
                        ecgHrMedian = 68.0,
                        analysisBundleId = "current-bundle",
                        payloadSha256 = EcgWearContract.sha256(v3Gzip),
                    ),
                )

                EcgSessionMigration3To4.migrate(statement::execute)

                val columns = linkedSetOf<String>()
                statement.executeQuery("PRAGMA table_info(ecg_sessions)").use { rows ->
                    while (rows.next()) columns += rows.getString("name")
                }
                assertThat(columns).containsAtLeast(
                    "rawTimingTrust",
                    "liveBpmMedian",
                    "liveBpmMin",
                    "liveBpmMax",
                    "liveBpmReliableCoveragePct",
                    "liveBpmAlgorithmId",
                    "liveBpmObservationCount",
                )

                statement.executeQuery("SELECT * FROM ecg_sessions WHERE sessionId = 'v2-sensor'")
                    .use { row ->
                        assertThat(row.next()).isTrue()
                        assertThat(row.getString("timingTrust")).isEqualTo("UNVERIFIED")
                        assertThat(row.getString("analysisBundleId")).isNull()
                        assertThat(row.getString("filePath")).isEqualTo(v2File.absolutePath)
                        assertThat(row.getString("analysisStatus")).isEqualTo("OK")
                        assertThat(row.getString("naoLabel")).isEqualTo("N")
                        assertThat(row.getString("findings")).isEqualTo("kept")
                        assertThat(row.getString("analysisNote")).isEqualTo("prior")
                        assertThat(row.getDouble("ecgHrMedian")).isEqualTo(72.0)
                        assertThat(row.getString("payloadSha256"))
                            .isEqualTo(EcgWearContract.sha256(v2Gzip))
                        assertThat(row.getObject("liveBpmMedian")).isNull()
                        assertThat(row.getDouble("liveBpmReliableCoveragePct")).isEqualTo(0.0)
                        assertThat(row.getInt("liveBpmObservationCount")).isEqualTo(0)
                    }

                statement.executeQuery("SELECT * FROM ecg_sessions WHERE sessionId = 'v3-seq'")
                    .use { row ->
                        assertThat(row.next()).isTrue()
                        assertThat(row.getString("timingTrust"))
                            .isEqualTo("SEQUENCE_RECONSTRUCTED")
                        assertThat(row.getString("analysisBundleId")).isEqualTo("current-bundle")
                        assertThat(row.getString("naoLabel")).isEqualTo("A")
                        assertThat(row.getString("findings")).isEqualTo("v3-kept")
                        assertThat(row.getDouble("ecgHrMedian")).isEqualTo(68.0)
                    }
            }
        }

        assertThat(v2File.readBytes().asList()).isEqualTo(v2Before.asList())
        assertThat(v3File.readBytes().asList()).isEqualTo(v3Before.asList())
        assertThat(String(v2Before, Charsets.ISO_8859_1)).doesNotContain(LiveBpmSummarizer.ALGORITHM_ID)
    }

    private fun insertV3Row(
        sessionId: String,
        filePath: String,
        schemaVersion: Int,
        timingTrust: String,
        analysisStatus: String,
        naoLabel: String,
        findings: String,
        analysisNote: String,
        ecgHrMedian: Double,
        analysisBundleId: String,
        payloadSha256: String,
    ): String = """
        INSERT INTO ecg_sessions (
            sessionId, filePath, tsStartMs, srHz, nSamples, durationSec,
            hrMedian, hrMin, hrMax, hrCoveragePct, usablePct, wrist, signFactor,
            polarityNormalized, unit, watchInfo, source, createdAtMs, analysisStatus,
            naoLabel, naoConfidence, findings, analysisNote, inputSchemaVersion,
            timingTrust, qualityStatus, cleanCoveragePct, qualityFlagsJson,
            ecgHrMedian, analysisBundleId, payloadSha256, captureSource
        ) VALUES (
            '$sessionId', '${filePath.replace("'", "''")}', 1700000000000, 500, 2, 0.002,
            70.0, 68, 72, 0.0, 100.0, 'LEFT', 1,
            0, 'mV', 'watch', 'WEAR', 1, '$analysisStatus',
            '$naoLabel', 0.9, '$findings', '$analysisNote', $schemaVersion,
            '$timingTrust', 'GOOD', 90.0, '[]',
            $ecgHrMedian, '$analysisBundleId', '$payloadSha256', 'HARDWARE'
        )
    """.trimIndent()

    private companion object {
        const val V3_CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS `ecg_sessions` (
                `sessionId` TEXT NOT NULL,
                `filePath` TEXT NOT NULL,
                `tsStartMs` INTEGER NOT NULL,
                `srHz` INTEGER NOT NULL,
                `nSamples` INTEGER NOT NULL,
                `durationSec` REAL NOT NULL,
                `hrMedian` REAL,
                `hrMin` INTEGER,
                `hrMax` INTEGER,
                `hrCoveragePct` REAL NOT NULL,
                `usablePct` REAL NOT NULL,
                `wrist` TEXT NOT NULL,
                `signFactor` INTEGER NOT NULL,
                `polarityNormalized` INTEGER NOT NULL,
                `unit` TEXT NOT NULL,
                `watchInfo` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `analysisStatus` TEXT NOT NULL,
                `naoLabel` TEXT,
                `naoConfidence` REAL,
                `findings` TEXT NOT NULL,
                `analysisNote` TEXT NOT NULL,
                `inputSchemaVersion` INTEGER NOT NULL,
                `timingTrust` TEXT NOT NULL,
                `qualityStatus` TEXT NOT NULL,
                `cleanCoveragePct` REAL NOT NULL,
                `qualityFlagsJson` TEXT NOT NULL,
                `ecgHrMedian` REAL,
                `analysisBundleId` TEXT,
                `payloadSha256` TEXT,
                `captureSource` TEXT NOT NULL,
                PRIMARY KEY(`sessionId`)
            )
        """
    }
}
