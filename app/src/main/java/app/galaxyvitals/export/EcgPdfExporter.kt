package app.galaxyvitals.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.galaxyvitals.data.protocol.EcgStripLayout
import app.galaxyvitals.data.protocol.StripRow
import app.galaxyvitals.data.protocol.StripSpec
import app.galaxyvitals.domain.EcgSampleFlags
import java.io.OutputStream

/**
 * Renders a recording as a printable ECG report.
 *
 * The strip is drawn at exactly 25 mm/s and 10 mm/mV on A4 landscape, so a
 * clinician who prints this at 100% can lay a ruler on it and measure. That is
 * the whole point of the document, and it is why the page is sized in
 * millimetres and only converted to PDF points at the last step.
 *
 * One page, because that is what a clinician is handed: the header, the strip,
 * and under it the measurements, the signal quality, and the provenance of the
 * file that produced them.
 */
object EcgPdfExporter {

    /** A4 landscape in PDF points, which are 1/72 inch. */
    const val PAGE_WIDTH_PT = 842
    const val PAGE_HEIGHT_PT = 595

    private const val MARGIN_MM = 12.0
    private const val PAGE_WIDTH_MM = 297.0
    private const val PAGE_HEIGHT_MM = 210.0

    private const val TITLE_MM = 4.6
    private const val BODY_MM = 2.8
    private const val SMALL_MM = 2.3

    private val MODEL_PATTERN = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"")
    private const val LINE_MM = 4.0

    fun write(out: OutputStream, report: EcgReportModel, text: EcgReportText) {
        val document = PdfDocument()
        try {
            renderPage(document, 1) { canvas -> drawReportPage(canvas, report, text) }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    private fun renderPage(document: PdfDocument, number: Int, body: (Canvas) -> Unit) {
        val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, number).create()
        val page = document.startPage(info)
        page.canvas.drawColor(Color.WHITE)
        body(page.canvas)
        document.finishPage(page)
    }

    // ---- page 1 -----------------------------------------------------------

    private fun drawReportPage(canvas: Canvas, report: EcgReportModel, text: EcgReportText) {
        val header = report.header
        var y = MARGIN_MM + TITLE_MM

        canvas.text(text.section(ReportSection.REPORT_TITLE), MARGIN_MM, y, TITLE_MM, bold = true)
        canvas.text(
            text.timestamp(header.tsStartMs),
            PAGE_WIDTH_MM - MARGIN_MM,
            y,
            TITLE_MM,
            align = Paint.Align.RIGHT,
            mono = true,
        )
        y += LINE_MM * 1.4

        // Identity line: what was recorded, by what, for how long.
        val identity = listOfNotNull(
            "${text.field(ReportField.LEAD)} I",
            "${ReportFormat.number(header.durationSec, 0)} s",
            "${ReportFormat.number(header.effectiveSrHz, 1)} Hz",
            text.wrist(header),
            deviceName(header.watchInfo),
        ).joinToString("   ·   ")
        canvas.text(identity, MARGIN_MM, y, BODY_MM)
        y += LINE_MM

        if (header.note.isNotEmpty()) {
            canvas.text(
                "${text.field(ReportField.NOTE_TEXT)}: ${header.note}",
                MARGIN_MM,
                y,
                BODY_MM,
            )
            y += LINE_MM
        }

        canvas.text(text.verdictTitle(report.verdict), MARGIN_MM, y + 1.0, TITLE_MM, bold = true)
        val summary = keyMeasurements(report, text)
        canvas.text(
            summary,
            PAGE_WIDTH_MM - MARGIN_MM,
            y + 1.0,
            BODY_MM,
            align = Paint.Align.RIGHT,
            mono = true,
        )
        y += LINE_MM * 1.8

        drawStrip(canvas, report, text, topMm = y)
        val spec = specOf(report)
        val rows = EcgStripLayout.rows(report.header.durationSec, spec)
        y += EcgStripLayout.heightMm(rows.size, spec) + LINE_MM

        canvas.text(
            "${text.scale(header)}   ·   ${text.filter(header)}",
            MARGIN_MM,
            y,
            SMALL_MM,
            mono = true,
        )
        y += LINE_MM * 1.2

        drawColumns(canvas, report, text, topMm = y)

        canvas.text(text.disclaimer(), MARGIN_MM, PAGE_HEIGHT_MM - MARGIN_MM, SMALL_MM)
    }

    /** Everything the strip does not show, in three columns under it. */
    private fun drawColumns(
        canvas: Canvas,
        report: EcgReportModel,
        text: EcgReportText,
        topMm: Double,
    ) {
        // Ten measurement rows plus a heading have to clear the footer
        // disclaimer, which is pinned to the bottom margin.
        val step = LINE_MM * 0.75
        val columnWidth = (PAGE_WIDTH_MM - 2 * MARGIN_MM) / 3.0

        var y = topMm
        canvas.text(text.section(ReportSection.MEASUREMENTS), MARGIN_MM, y, BODY_MM, bold = true)
        y += step * 1.4
        report.measurements.forEach { measurement ->
            canvas.text(text.measurement(measurement.key), MARGIN_MM, y, SMALL_MM)
            val value = ReportFormat.value(measurement)
            if (value != null) {
                canvas.text(
                    value,
                    MARGIN_MM + columnWidth - 12.0,
                    y,
                    SMALL_MM,
                    align = Paint.Align.RIGHT,
                    mono = true,
                )
                canvas.text(text.unit(measurement.key), MARGIN_MM + columnWidth - 10.0, y, SMALL_MM)
            } else {
                canvas.text(
                    text.availability(measurement.availability),
                    MARGIN_MM + columnWidth - 10.0,
                    y,
                    SMALL_MM,
                    align = Paint.Align.RIGHT,
                )
            }
            y += step
        }

        val middleX = MARGIN_MM + columnWidth
        var middle = topMm
        canvas.text(text.section(ReportSection.SIGNAL_QUALITY), middleX, middle, BODY_MM, bold = true)
        middle += step * 1.4
        qualityLines(report, text).forEach { line ->
            canvas.text(line, middleX, middle, SMALL_MM)
            middle += step
        }

        val rightX = MARGIN_MM + columnWidth * 2
        var right = topMm
        canvas.text(text.section(ReportSection.RECORDING), rightX, right, BODY_MM, bold = true)
        right += step * 1.4
        provenanceLines(report, text).forEach { (label, value) ->
            canvas.text(label, rightX, right, SMALL_MM)
            canvas.text(
                value,
                PAGE_WIDTH_MM - MARGIN_MM,
                right,
                SMALL_MM,
                align = Paint.Align.RIGHT,
                mono = true,
            )
            right += step
        }
    }

    /**
     * `watchInfo` is the device's own JSON blob. A report needs the model, not
     * the blob, and printing the blob overruns the page.
     */
    internal fun deviceName(watchInfo: String): String? {
        if (watchInfo.isBlank()) return null
        val model = MODEL_PATTERN.find(watchInfo)?.groupValues?.getOrNull(1)
        return model?.takeIf { it.isNotBlank() } ?: watchInfo.take(24).takeIf { !it.startsWith("{") }
    }

    private fun keyMeasurements(report: EcgReportModel, text: EcgReportText): String =
        listOf(MeasurementKey.HEART_RATE, MeasurementKey.HEART_RATE_RANGE)
            .mapNotNull { key -> report.measurement(key) }
            .joinToString("   ") { measurement ->
                val value = ReportFormat.value(measurement)
                    ?: text.availability(measurement.availability)
                "${text.measurement(measurement.key)} $value ${text.unit(measurement.key)}".trim()
            }

    // ---- the strip --------------------------------------------------------

    private fun specOf(report: EcgReportModel) = StripSpec(
        speedMmPerSec = report.header.speedMmPerSec,
        gainMmPerMv = report.header.gainMmPerMv,
    )

    private fun drawStrip(
        canvas: Canvas,
        report: EcgReportModel,
        text: EcgReportText,
        topMm: Double,
    ) {
        val spec = specOf(report)
        val rows = EcgStripLayout.rows(report.header.durationSec, spec)
        val grid = EcgStripLayout.grid(rows.size, spec)
        val left = MARGIN_MM

        val minor = gridPaint(0x22C86A5E.toInt(), 0.15)
        val major = gridPaint(0x55B4483A.toInt(), 0.3)
        val height = EcgStripLayout.heightMm(rows.size, spec)
        val width = EcgStripLayout.widthMm(spec)

        grid.minorXMm.forEach { x -> canvas.line(left + x, topMm, left + x, topMm + height, minor) }
        grid.minorYMm.forEach { y -> canvas.line(left, topMm + y, left + width, topMm + y, minor) }
        grid.majorXMm.forEach { x -> canvas.line(left + x, topMm, left + x, topMm + height, major) }
        grid.majorYMm.forEach { y -> canvas.line(left, topMm + y, left + width, topMm + y, major) }

        val trace = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = (0.28 * EcgStripLayout.PDF_POINTS_PER_MM).toFloat()
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val srHz = report.header.effectiveSrHz.takeIf { it > 0.0 }
            ?: report.header.nominalSrHz.toDouble()

        rows.forEach { row ->
            canvas.drawPath(calibrationPath(row, spec, left, topMm), trace)
            canvas.drawPath(tracePath(report, row, spec, srHz, left, topMm), trace)
            canvas.text(
                "${ReportFormat.number(row.startSec, 0)}–${ReportFormat.number(row.endSec, 0)} s",
                left + width - 1.0,
                topMm + row.topMm + 3.0,
                SMALL_MM,
                align = Paint.Align.RIGHT,
                mono = true,
            )
        }
        canvas.text(
            text.field(ReportField.LEAD) + " I",
            left + 0.5,
            topMm + 3.0,
            SMALL_MM,
            bold = true,
        )
    }

    private fun calibrationPath(
        row: StripRow,
        spec: StripSpec,
        leftMm: Double,
        topMm: Double,
    ): Path {
        val path = Path()
        EcgStripLayout.calibrationPulseMm(row, spec).forEachIndexed { index, point ->
            val x = ((leftMm + point.xMm) * EcgStripLayout.PDF_POINTS_PER_MM).toFloat()
            val y = ((topMm + point.yMm) * EcgStripLayout.PDF_POINTS_PER_MM).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    private fun tracePath(
        report: EcgReportModel,
        row: StripRow,
        spec: StripSpec,
        srHz: Double,
        leftMm: Double,
        topMm: Double,
    ): Path {
        val path = Path()
        val samples = report.displaySamples
        val range = EcgStripLayout.rowSampleRange(row, srHz, samples.size)
        if (range.isEmpty()) return path
        val gapFlags = EcgSampleFlags.TIMESTAMP_GAP or EcgSampleFlags.SEQUENCE_GAP
        var pendingMove = true
        for (index in range) {
            val sample = samples[index]
            val tSec = EcgStripLayout.sampleTimeSec(sample.sampleIndex.toLong(), srHz)
            if (tSec < row.startSec || tSec > row.endSec) continue
            val x = ((leftMm + EcgStripLayout.xMm(tSec, row, spec)) * EcgStripLayout.PDF_POINTS_PER_MM).toFloat()
            val y = ((topMm + EcgStripLayout.yMm(sample.valueMv.toDouble(), row, spec)) * EcgStripLayout.PDF_POINTS_PER_MM).toFloat()
            if (pendingMove || sample.flags and gapFlags != 0) {
                path.moveTo(x, y)
                pendingMove = false
            } else {
                path.lineTo(x, y)
            }
        }
        return path
    }

    private fun qualityLines(report: EcgReportModel, text: EcgReportText): List<String> {
        val quality = report.quality
        val lines = ArrayList<String>()
        lines += "${text.field(ReportField.QUALITY_STATUS)}: ${text.qualityStatus(quality)}"
        lines += "${text.field(ReportField.CLEAN_COVERAGE)}: ${ReportFormat.number(quality.cleanCoveragePct, 0)} %"
        quality.mainsHz?.let { hz ->
            val suppression = ReportFormat.number(quality.mainsSuppressionDb, 1)
            lines += "${text.field(ReportField.MAINS)}: ${ReportFormat.number(hz, 1)} Hz (${suppression} dB)"
        }
        lines += "${text.field(ReportField.BASELINE_EXCURSION)}: ${ReportFormat.number(quality.baselineExcursionMv, 2)} mV"
        if (quality.noisyRangesMs.isNotEmpty()) {
            lines += "${text.field(ReportField.NOISY_SPANS)}: " +
                quality.noisyRangesMs.joinToString(", ") { ReportFormat.spanSeconds(it) } + " s"
        }
        quality.flags.forEach { lines += "· ${text.qualityFlag(it)}" }
        return lines
    }

    private fun provenanceLines(
        report: EcgReportModel,
        text: EcgReportText,
    ): List<Pair<String, String>> {
        val header = report.header
        return listOfNotNull(
            text.field(ReportField.SESSION_ID) to header.sessionId,
            text.field(ReportField.CAPTURE_SOURCE) to text.captureSource(header),
            text.field(ReportField.SAMPLE_COUNT) to header.sampleCount.toString(),
            text.field(ReportField.SAMPLE_RATE) to
                "${ReportFormat.number(header.effectiveSrHz, 2)} Hz",
            text.field(ReportField.FILTER) to text.filter(header),
            text.field(ReportField.SCALE) to text.scale(header),
            header.analysisBundleId?.let { text.field(ReportField.ANALYSIS_BUNDLE) to it },
            header.payloadSha256?.let { text.field(ReportField.CHECKSUM) to it.take(16) },
            text.field(ReportField.APP_VERSION) to header.appVersion,
        )
    }

    // ---- millimetre drawing helpers ---------------------------------------

    private fun gridPaint(color: Int, widthMm: Double) = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = (widthMm * EcgStripLayout.PDF_POINTS_PER_MM).toFloat()
    }

    private fun Canvas.line(x0: Double, y0: Double, x1: Double, y1: Double, paint: Paint) {
        val f = EcgStripLayout.PDF_POINTS_PER_MM
        drawLine((x0 * f).toFloat(), (y0 * f).toFloat(), (x1 * f).toFloat(), (y1 * f).toFloat(), paint)
    }

    private fun Canvas.text(
        value: String,
        xMm: Double,
        yMm: Double,
        sizeMm: Double,
        bold: Boolean = false,
        mono: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
    ) {
        val f = EcgStripLayout.PDF_POINTS_PER_MM
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = (sizeMm * f).toFloat()
            textAlign = align
            typeface = Typeface.create(
                if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL,
            )
        }
        drawText(value, (xMm * f).toFloat(), (yMm * f).toFloat(), paint)
    }
}
