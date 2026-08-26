package app.galaxyvitals.data.local

object EcgSessionMigration3To4 {
    val STATEMENTS: List<String> = listOf(
        "ALTER TABLE ecg_sessions ADD COLUMN rawTimingTrust TEXT",
        "ALTER TABLE ecg_sessions ADD COLUMN liveBpmMedian REAL",
        "ALTER TABLE ecg_sessions ADD COLUMN liveBpmMin REAL",
        "ALTER TABLE ecg_sessions ADD COLUMN liveBpmMax REAL",
        "ALTER TABLE ecg_sessions ADD COLUMN liveBpmReliableCoveragePct REAL NOT NULL DEFAULT 0.0",
        "ALTER TABLE ecg_sessions ADD COLUMN liveBpmAlgorithmId TEXT",
        "ALTER TABLE ecg_sessions ADD COLUMN liveBpmObservationCount INTEGER NOT NULL DEFAULT 0",
        "UPDATE ecg_sessions SET timingTrust = 'UNVERIFIED', analysisBundleId = NULL " +
            "WHERE inputSchemaVersion = 2 AND timingTrust = 'SENSOR'",
    )

    fun migrate(execSql: (String) -> Unit) {
        STATEMENTS.forEach(execSql)
    }
}
