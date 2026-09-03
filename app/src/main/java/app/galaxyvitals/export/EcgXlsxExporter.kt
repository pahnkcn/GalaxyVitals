package app.galaxyvitals.export

import app.galaxyvitals.data.protocol.xlsx.XlsxWriter
import app.galaxyvitals.domain.EcgSampleFlags
import java.io.OutputStream

/**
 * Writes a recording as a three-sheet workbook: what it reports, every sample
 * behind it, and the interval series the rate and HRV numbers came from.
 *
 * Row labels follow the app's language, but sheet names and column order do not:
 * a tab name is what someone's formula or import script points at, so
 * `Summary` / `Samples` / `Beats` stay fixed in every locale.
 */
object EcgXlsxExporter {

    const val SHEET_SUMMARY = "Summary"
    const val SHEET_SAMPLES = "Samples"
    const val SHEET_BEATS = "Beats"

    fun write(out: OutputStream, report: EcgReportModel, text: EcgReportText) {
        XlsxWriter.write(out) {
            sheet(SHEET_SUMMARY) { summary(report, text) }
            sheet(SHEET_SAMPLES) { samples(report, text) }
            sheet(SHEET_BEATS) { beats(report, text) }
        }
    }

    private fun XlsxWriter.Sheet.summary(report: EcgReportModel, text: EcgReportText) {
        val header = report.header
        headerRow(text.section(ReportSection.REPORT_TITLE))
        row { }

        headerRow(text.section(ReportSection.RECORDING))
        pair(text.field(ReportField.RECORDED_AT), text.timestamp(header.tsStartMs))
        value(text.field(ReportField.DURATION), header.durationSec, decimals = 1, unit = "s")
        value(
            label = text.field(ReportField.SAMPLE_RATE),
            value = header.effectiveSrHz,
            decimals = 2,
            unit = if (header.srMeasured) "Hz" else "Hz (${header.nominalSrHz} declared)",
        )
        value(text.field(ReportField.SAMPLE_COUNT), header.sampleCount.toDouble(), 0, "")
        pair(text.field(ReportField.LEAD), LEAD_LABEL)
        pair(text.field(ReportField.WRIST), text.wrist(header))
        pair(text.field(ReportField.DEVICE), header.watchInfo)
        pair(text.field(ReportField.CAPTURE_SOURCE), text.captureSource(header))
        pair(text.field(ReportField.FILTER), text.filter(header))
        pair(text.field(ReportField.SCALE), text.scale(header))
        pair(text.field(ReportField.SESSION_ID), header.sessionId)
        pair(text.field(ReportField.CHECKSUM), header.payloadSha256)
        pair(text.field(ReportField.ANALYSIS_BUNDLE), header.analysisBundleId)
        pair(text.field(ReportField.APP_VERSION), header.appVersion)
        row { }

        headerRow(text.section(ReportSection.RHYTHM))
        pair(text.verdictTitle(report.verdict), text.verdictBody(report.verdict))
        report.verdict.modelScore?.let {
            value(text.field(ReportField.MODEL_SCORE), it * 100.0, 0, "%")
        }
        row { }

        headerRow(
            text.section(ReportSection.MEASUREMENTS),
            text.field(ReportField.VALUE),
            text.field(ReportField.SPREAD),
            text.field(ReportField.UNIT),
        )
        report.measurements.forEach { measurement ->
            row {
                text(text.measurement(measurement.key))
                if (measurement.isAvailable) {
                    number(measurement.value, measurement.decimals)
                    number(measurement.spread, measurement.decimals)
                    text(text.unit(measurement.key))
                } else {
                    text(text.availability(measurement.availability))
                }
            }
        }
        row { }

        headerRow(text.section(ReportSection.SIGNAL_QUALITY))
        pair(text.field(ReportField.QUALITY_STATUS), text.qualityStatus(report.quality))
        value(text.field(ReportField.CLEAN_COVERAGE), report.quality.cleanCoveragePct, 0, "%")
        report.quality.mainsHz?.let { hz ->
            // Frequency and how much of it was removed belong on one row: the
            // suppression figure means nothing without the frequency it applies to.
            row {
                text(text.field(ReportField.MAINS))
                number(hz, decimals = 1)
                number(report.quality.mainsSuppressionDb, decimals = 1)
                text("Hz / dB")
            }
        }
        value(
            text.field(ReportField.BASELINE_EXCURSION),
            report.quality.baselineExcursionMv,
            2,
            "mV",
        )
        if (report.quality.noisyRangesMs.isNotEmpty()) {
            pair(
                text.field(ReportField.NOISY_SPANS),
                report.quality.noisyRangesMs.joinToString(", ") { ReportFormat.spanSeconds(it) + " s" },
            )
        }
        report.quality.flags.forEach { flag ->
            row {
                skip()
                text(text.qualityFlag(flag))
            }
        }

        if (report.header.note.isNotEmpty()) {
            row { }
            headerRow(text.section(ReportSection.NOTE))
            pair(text.field(ReportField.NOTE_TEXT), report.header.note)
        }

        row { }
        textRow(text.disclaimer())
    }

    private fun XlsxWriter.Sheet.samples(report: EcgReportModel, text: EcgReportText) {
        headerRow(
            text.field(ReportField.TIME_MS),
            text.field(ReportField.SENSOR_REL_MS),
            text.field(ReportField.RAW_MV),
            text.field(ReportField.FILTERED_MV),
            text.field(ReportField.SAMPLE_FLAGS),
        )
        val srHz = report.header.effectiveSrHz.takeIf { it > 0.0 }
            ?: report.header.nominalSrHz.toDouble()
        val filtered = report.displaySamples
        report.rawSamples.forEachIndexed { index, sample ->
            row {
                // Time from the sample grid, not from the captured timestamp:
                // ECG_ON_DEMAND batches its timestamps, so many samples share one.
                number(sample.sampleIndex * 1_000.0 / srHz, decimals = 3)
                integer(sample.relMs)
                number(sample.valueMv.toDouble(), decimals = 6)
                number(filtered.getOrNull(index)?.valueMv?.toDouble(), decimals = 6)
                text(flagNames(sample.flags))
            }
        }
    }

    private fun XlsxWriter.Sheet.beats(report: EcgReportModel, text: EcgReportText) {
        headerRow(
            text.field(ReportField.R_PEAK_MS),
            text.field(ReportField.RR_ALL_MS),
            text.field(ReportField.RR_NN_MS),
        )
        // Three series of different lengths, not three attributes of one beat:
        // the NN series is what survived artifact filtering, so it cannot be
        // lined up row-for-row against the peaks it was derived from.
        val beats = report.beats
        val rows = maxOf(beats.rPeaksMs.size, beats.rrAllMs.size, beats.rrNnMs.size)
        repeat(rows) { index ->
            row {
                number(beats.rPeaksMs.getOrNull(index), decimals = 1)
                number(beats.rrAllMs.getOrNull(index), decimals = 1)
                number(beats.rrNnMs.getOrNull(index), decimals = 1)
            }
        }
    }

    private fun XlsxWriter.Sheet.pair(label: String, value: String?) {
        row {
            text(label)
            text(value)
        }
    }

    private fun XlsxWriter.Sheet.value(label: String, value: Double?, decimals: Int, unit: String) {
        row {
            text(label)
            number(value, decimals)
            skip()
            text(unit)
        }
    }

    internal fun flagNames(flags: Int): String {
        if (flags == 0) return ""
        val names = ArrayList<String>(4)
        if (flags and EcgSampleFlags.SEQUENCE_GAP != 0) names += "SEQUENCE_GAP"
        if (flags and EcgSampleFlags.CONTACT_LOSS != 0) names += "CONTACT_LOSS"
        if (flags and EcgSampleFlags.CLIPPED != 0) names += "CLIPPED"
        if (flags and EcgSampleFlags.TIMESTAMP_GAP != 0) names += "TIMESTAMP_GAP"
        if (flags and EcgSampleFlags.NONFINITE != 0) names += "NONFINITE"
        return names.joinToString("|")
    }

    /** Wrist ECG is a Lead-I equivalent; it is one lead, and it is not a 12-lead. */
    private const val LEAD_LABEL = "I"
}
