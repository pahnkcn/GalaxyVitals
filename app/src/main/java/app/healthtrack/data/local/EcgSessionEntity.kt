package app.healthtrack.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.healthtrack.domain.AnalysisStatus
import app.healthtrack.domain.EcgSession
import app.healthtrack.domain.EcgSource
import app.healthtrack.domain.Wrist

@Entity(tableName = "ecg_sessions")
data class EcgSessionEntity(
    @PrimaryKey val sessionId: String,
    val filePath: String,
    val tsStartMs: Long,
    val srHz: Int,
    val nSamples: Int,
    val durationSec: Double,
    val hrMedian: Double?,
    val hrMin: Int?,
    val hrMax: Int?,
    val hrCoveragePct: Double,
    val usablePct: Double,
    val wrist: String,
    val signFactor: Int,
    val polarityNormalized: Boolean,
    val unit: String,
    val watchInfo: String,
    val source: String,
    val createdAtMs: Long,
    val analysisStatus: String = AnalysisStatus.NONE.name,
    val naoLabel: String? = null,
    val naoConfidence: Float? = null,
    val findings: String = "",
    val analysisNote: String = "",
) {
    fun toDomain(): EcgSession = EcgSession(
        sessionId = sessionId,
        filePath = filePath,
        tsStartMs = tsStartMs,
        srHz = srHz,
        nSamples = nSamples,
        durationSec = durationSec,
        hrMedian = hrMedian,
        hrMin = hrMin,
        hrMax = hrMax,
        hrCoveragePct = hrCoveragePct,
        usablePct = usablePct,
        wrist = runCatching { Wrist.valueOf(wrist) }.getOrDefault(Wrist.UNKNOWN),
        signFactor = signFactor,
        polarityNormalized = polarityNormalized,
        unit = unit,
        watchInfo = watchInfo,
        source = runCatching { EcgSource.valueOf(source) }.getOrDefault(EcgSource.IMPORT),
        createdAtMs = createdAtMs,
        analysisStatus = runCatching { AnalysisStatus.valueOf(analysisStatus) }
            .getOrDefault(AnalysisStatus.NONE),
        naoLabel = naoLabel,
        naoConfidence = naoConfidence,
        findings = findings,
        analysisNote = analysisNote,
    )

    companion object {
        fun from(parsed: app.healthtrack.data.protocol.ParsedEcgFile, filePath: String, source: EcgSource, now: Long): EcgSessionEntity {
            return EcgSessionEntity(
                sessionId = parsed.sessionId,
                filePath = filePath,
                tsStartMs = parsed.tsStartMs,
                srHz = parsed.srHz,
                nSamples = parsed.samples.size,
                durationSec = parsed.durationSec,
                hrMedian = parsed.hrMedian,
                hrMin = parsed.hrMin,
                hrMax = parsed.hrMax,
                hrCoveragePct = parsed.hrCoveragePct,
                usablePct = parsed.usablePct,
                wrist = parsed.wrist.name,
                signFactor = parsed.signFactor,
                polarityNormalized = parsed.polarityNormalized,
                unit = parsed.unit,
                watchInfo = parsed.watchInfo,
                source = source.name,
                createdAtMs = now,
                analysisStatus = AnalysisStatus.PENDING.name,
            )
        }
    }

    fun withAnalysis(
        status: AnalysisStatus,
        naoLabel: String?,
        naoConfidence: Float?,
        findings: String,
        note: String,
    ): EcgSessionEntity = copy(
        analysisStatus = status.name,
        naoLabel = naoLabel,
        naoConfidence = naoConfidence,
        findings = findings,
        analysisNote = note,
    )
}
