package app.galaxyvitals.export

import app.galaxyvitals.data.protocol.EcgBandwidth
import app.galaxyvitals.data.protocol.EcgBeatAnalyzer
import app.galaxyvitals.data.protocol.EcgBeatResult
import app.galaxyvitals.data.protocol.EcgBpmStatus
import app.galaxyvitals.data.protocol.EcgDisplayProcessor
import app.galaxyvitals.data.protocol.EcgFounderPreprocess
import app.galaxyvitals.data.protocol.EcgHrvAnalyzer
import app.galaxyvitals.data.protocol.EcgHrvResult
import app.galaxyvitals.data.protocol.EcgHrvStatus
import app.galaxyvitals.data.protocol.EcgSignalMetrics
import app.galaxyvitals.data.protocol.NaoLabel
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.data.protocol.SignalQualityReport
import app.galaxyvitals.data.protocol.StripSpec
import app.galaxyvitals.analysis.EcgAnalysisBundle
import app.galaxyvitals.domain.AnalysisStatus
import app.galaxyvitals.domain.EcgSession
import kotlin.math.sqrt

/**
 * Turns one stored recording into everything the screen and the exports report.
 *
 * Pure Kotlin on purpose: every number a clinician might act on is decided here
 * and can be pinned by an off-device test.
 */
object EcgReportBuilder {

    /** Numbers are reported at diagnostic bandwidth; a 40 Hz cutoff costs 15-20% of R-wave amplitude. */
    val REPORT_BANDWIDTH = EcgBandwidth.DIAGNOSTIC

    fun build(
        parsed: ParsedEcgFile,
        session: EcgSession?,
        appVersion: String,
        bandwidth: EcgBandwidth = REPORT_BANDWIDTH,
        spec: StripSpec = StripSpec(),
        note: String = "",
    ): EcgReportModel {
        val display = EcgDisplayProcessor.process(
            samples = parsed.samples,
            srHz = parsed.srHz,
            signFactor = parsed.signFactor,
            polarityNormalized = parsed.polarityNormalized,
            bandwidth = bandwidth,
        )
        val prepared = EcgFounderPreprocess.prepare(parsed)
        val quality = prepared.quality
        val beat = EcgBeatAnalyzer.analyze(parsed, prepared)
        val hrv = EcgHrvAnalyzer.analyze(beat)

        return EcgReportModel(
            header = header(parsed, session, display.metrics, bandwidth, spec, appVersion, note),
            verdict = verdict(session, beat, hrv),
            measurements = measurements(parsed, session, beat, hrv),
            quality = quality(quality, display.metrics, parsed),
            beats = beats(beat),
            rawSamples = parsed.samples,
            displaySamples = display.samples,
        )
    }

    private fun header(
        parsed: ParsedEcgFile,
        session: EcgSession?,
        metrics: EcgSignalMetrics,
        bandwidth: EcgBandwidth,
        spec: StripSpec,
        appVersion: String,
        note: String,
    ) = ReportHeader(
        sessionId = parsed.sessionId,
        tsStartMs = parsed.tsStartMs,
        durationSec = parsed.durationSec,
        sampleCount = parsed.samples.size,
        nominalSrHz = parsed.srHz,
        effectiveSrHz = metrics.srHz,
        srMeasured = metrics.srMeasured,
        wrist = parsed.wrist,
        captureSource = parsed.captureSource.name,
        watchInfo = parsed.watchInfo,
        unitLabel = parsed.unit,
        bandwidth = bandwidth,
        speedMmPerSec = spec.speedMmPerSec,
        gainMmPerMv = spec.gainMmPerMv,
        payloadSha256 = session?.payloadSha256,
        analysisBundleId = session?.analysisBundleId,
        appVersion = appVersion,
        note = note.trim(),
    )

    private fun verdict(
        session: EcgSession?,
        beat: EcgBeatResult,
        hrv: EcgHrvResult,
    ) = ReportVerdict(
        analysisStatus = session?.analysisStatus ?: AnalysisStatus.NONE,
        naoLabel = session?.naoLabel?.let { runCatching { NaoLabel.valueOf(it) }.getOrNull() },
        modelScore = session?.naoConfidence?.takeIf { it.isFinite() },
        rateStatus = beat.status.toRateStatus(),
        hrvStatus = hrv.status.toHrvStatus(),
        staleBundle = session?.analysisBundleId != null &&
            session.analysisBundleId != EcgAnalysisBundle.CURRENT_COMPATIBILITY_ID,
    )

    private fun measurements(
        parsed: ParsedEcgFile,
        session: EcgSession?,
        beat: EcgBeatResult,
        hrv: EcgHrvResult,
    ): List<Measurement> {
        val rateAvailability = beat.status.toAvailability()
        val hrvAvailability = hrv.status.toAvailability()
        val rrMean = beat.rr.nnMs.takeIf { it.isNotEmpty() }?.average()
        val rrSd = standardDeviation(beat.rr.nnMs)

        return listOf(
            Measurement(
                key = MeasurementKey.HEART_RATE,
                value = beat.bpmMedian?.takeIf { it.isFinite() },
                decimals = 0,
                availability = rateAvailability,
            ),
            // The slowest beat is the longest interval, so min and max swap here.
            Measurement(
                key = MeasurementKey.HEART_RATE_RANGE,
                value = beat.rr.nnMs.maxOrNull()?.let(::bpmFromRr),
                spread = beat.rr.nnMs.minOrNull()?.let(::bpmFromRr),
                decimals = 0,
                availability = rateAvailability,
            ),
            Measurement(
                key = MeasurementKey.RR_MEAN,
                value = rrMean,
                spread = rrSd,
                decimals = 0,
                availability = rateAvailability,
            ),
            Measurement(
                key = MeasurementKey.SDNN,
                value = hrv.sdnnMs,
                decimals = 0,
                availability = hrvAvailability,
            ),
            Measurement(
                key = MeasurementKey.RMSSD,
                value = hrv.rmssdMs,
                decimals = 0,
                availability = hrvAvailability,
            ),
            Measurement(
                key = MeasurementKey.PNN50,
                value = hrv.pnn50Pct,
                decimals = 1,
                availability = hrvAvailability,
            ),
            Measurement(
                key = MeasurementKey.BEAT_COUNT,
                value = beat.matchedPeaks.size.toDouble(),
                decimals = 0,
                availability = rateAvailability,
            ),
            Measurement(
                key = MeasurementKey.CORRECTED_INTERVALS,
                value = beat.rr.correctedCount.toDouble(),
                spread = beat.rr.candidateCount.toDouble(),
                decimals = 0,
                availability = rateAvailability,
            ),
            Measurement(
                key = MeasurementKey.DETECTOR_AGREEMENT,
                value = (beat.bSqi * 100.0).takeIf { it.isFinite() },
                decimals = 0,
                availability = MetricAvailability.AVAILABLE,
            ),
            Measurement(
                key = MeasurementKey.ANALYSED_DURATION,
                value = beat.cleanDurationMs / 1000.0,
                spread = session?.durationSec ?: parsed.durationSec,
                decimals = 1,
                availability = MetricAvailability.AVAILABLE,
            ),
        )
    }

    private fun quality(
        report: SignalQualityReport,
        metrics: EcgSignalMetrics,
        parsed: ParsedEcgFile,
    ): ReportQuality {
        val totalMs = (parsed.durationSec * 1000.0).toLong().coerceAtLeast(0L)
        return ReportQuality(
            status = report.status,
            flags = report.flags.sortedBy { it.name },
            cleanCoveragePct = report.cleanCoveragePct,
            cleanUnionMs = report.cleanUnionMs,
            noisyRangesMs = complementOf(report.cleanRanges, totalMs),
            mainsHz = metrics.line?.frequencyHz,
            mainsSuppressionDb = metrics.line?.let { metrics.lineSuppressionDb },
            baselineExcursionMv = metrics.baselineExcursionMv,
        )
    }

    private fun beats(beat: EcgBeatResult): ReportBeats {
        // Peak indices sit on the analysis grid, which is the corrected rate, not
        // the declared one. A Galaxy Watch runs ~501.7 Hz against a declared 500.
        val srHz = beat.analysisSrHz.takeIf { it > 0.0 } ?: 1.0
        return ReportBeats(
            rPeaksMs = beat.matchedPeaks.map { it * 1000.0 / srHz },
            rrAllMs = beat.rr.allMs,
            rrNnMs = beat.rr.nnMs,
            missedBeatCount = beat.rr.missedBeatCount,
            extraDetectionCount = beat.rr.extraDetectionCount,
            implausibleCount = beat.rr.implausibleCount,
        )
    }

    /** The stretches the analyser did not accept: what is left once clean ranges are removed. */
    internal fun complementOf(clean: List<LongRange>, totalMs: Long): List<ClosedRange<Long>> {
        if (totalMs <= 0L) return emptyList()
        if (clean.isEmpty()) return listOf(0L..totalMs)
        val merged = ArrayList<LongRange>()
        clean.sortedBy { it.first }.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
        val gaps = ArrayList<ClosedRange<Long>>()
        var cursor = 0L
        merged.forEach { range ->
            if (range.first > cursor) gaps.add(cursor..range.first)
            cursor = maxOf(cursor, range.last)
        }
        if (cursor < totalMs) gaps.add(cursor..totalMs)
        return gaps
    }

    private fun bpmFromRr(rrMs: Double): Double? =
        if (rrMs > 0.0) 60_000.0 / rrMs else null

    private fun standardDeviation(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
        return sqrt(variance)
    }

    private fun EcgBpmStatus.toRateStatus(): RateStatus = when (this) {
        EcgBpmStatus.RELIABLE -> RateStatus.RELIABLE
        EcgBpmStatus.INSUFFICIENT_DATA -> RateStatus.INSUFFICIENT_DATA
        EcgBpmStatus.LOW_QUALITY -> RateStatus.LOW_QUALITY
        EcgBpmStatus.DETECTOR_DISAGREEMENT -> RateStatus.DETECTOR_DISAGREEMENT
    }

    private fun EcgHrvStatus.toHrvStatus(): HrvStatus = when (this) {
        EcgHrvStatus.RELIABLE -> HrvStatus.RELIABLE
        EcgHrvStatus.INSUFFICIENT_DATA -> HrvStatus.INSUFFICIENT_DATA
        EcgHrvStatus.TOO_MANY_CORRECTIONS -> HrvStatus.TOO_MANY_CORRECTIONS
        EcgHrvStatus.LOW_QUALITY -> HrvStatus.LOW_QUALITY
    }

    private fun EcgBpmStatus.toAvailability(): MetricAvailability = when (this) {
        EcgBpmStatus.RELIABLE -> MetricAvailability.AVAILABLE
        EcgBpmStatus.INSUFFICIENT_DATA -> MetricAvailability.INSUFFICIENT_DATA
        EcgBpmStatus.LOW_QUALITY -> MetricAvailability.LOW_QUALITY
        EcgBpmStatus.DETECTOR_DISAGREEMENT -> MetricAvailability.DETECTOR_DISAGREEMENT
    }

    private fun EcgHrvStatus.toAvailability(): MetricAvailability = when (this) {
        EcgHrvStatus.RELIABLE -> MetricAvailability.AVAILABLE
        EcgHrvStatus.INSUFFICIENT_DATA -> MetricAvailability.INSUFFICIENT_DATA
        EcgHrvStatus.TOO_MANY_CORRECTIONS -> MetricAvailability.TOO_MANY_CORRECTIONS
        EcgHrvStatus.LOW_QUALITY -> MetricAvailability.LOW_QUALITY
    }
}
