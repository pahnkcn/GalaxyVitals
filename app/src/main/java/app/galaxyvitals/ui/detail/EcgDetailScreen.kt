package app.galaxyvitals.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.galaxyvitals.R
import app.galaxyvitals.data.protocol.EcgBandwidth
import app.galaxyvitals.data.protocol.StripSpec
import app.galaxyvitals.export.AndroidReportText
import app.galaxyvitals.export.EcgReportModel
import app.galaxyvitals.export.ExportFormat
import app.galaxyvitals.ui.EcgScaleCalibration
import app.galaxyvitals.ui.components.ScreenTopBar
import app.galaxyvitals.ui.pxPerMm
import app.galaxyvitals.ui.theme.Amber
import app.galaxyvitals.ui.theme.EcgType
import app.galaxyvitals.ui.theme.Spacing

/** Sheet is the whole recording at a glance; true scale is the one you measure. */
enum class StripMode { SHEET, TRUE_SCALE }

@Composable
fun EcgDetailScreen(
    report: EcgReportModel?,
    loading: Boolean,
    sessionId: String?,
    bandwidth: EcgBandwidth,
    calibration: EcgScaleCalibration,
    exporting: Boolean,
    onLoad: (String) -> Unit,
    onBandwidth: (EcgBandwidth) -> Unit,
    onExport: (ExportFormat, String, AndroidReportText, String) -> Unit,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(StripMode.SHEET) }
    var spec by remember { mutableStateOf(StripSpec()) }

    val context = LocalContext.current
    val text = remember(context) { AndroidReportText(context) }
    val chooserTitle = stringResource(R.string.export_chooser)

    LaunchedEffect(sessionId) {
        sessionId?.let(onLoad)
    }

    if (confirmDelete && sessionId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ecg_delete_title)) },
            text = { Text(stringResource(R.string.ecg_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(sessionId)
                    confirmDelete = false
                    onBack()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showExport && sessionId != null) {
        ExportSheet(
            running = exporting,
            onDismiss = { showExport = false },
            onExport = { format, note ->
                onExport(format, note, text, chooserTitle)
                showExport = false
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        ScreenTopBar(
            title = stringResource(R.string.ecg_title),
            onBack = onBack,
            actions = {
                if (report != null) {
                    TextButton(onClick = { showExport = true }) {
                        Text(stringResource(R.string.action_export))
                    }
                }
                if (sessionId != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            },
        )

        when {
            report != null -> DetailBody(
                report = report,
                text = text,
                bandwidth = bandwidth,
                mode = mode,
                spec = spec,
                calibration = calibration,
                onMode = { mode = it },
                onSpec = { spec = it },
                onBandwidth = onBandwidth,
            )

            loading -> Text(
                text = stringResource(R.string.ecg_loading),
                modifier = Modifier.padding(Spacing.page),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Text(
                text = stringResource(R.string.ecg_missing),
                modifier = Modifier.padding(Spacing.page),
            )
        }
    }
}

@Composable
private fun DetailBody(
    report: EcgReportModel,
    text: AndroidReportText,
    bandwidth: EcgBandwidth,
    mode: StripMode,
    spec: StripSpec,
    calibration: EcgScaleCalibration,
    onMode: (StripMode) -> Unit,
    onSpec: (StripSpec) -> Unit,
    onBandwidth: (EcgBandwidth) -> Unit,
) {
    // The measured rate, not the declared one: a Galaxy Watch says 500 Hz and
    // runs at about 501.7, which is a third of a millimetre over 30 seconds.
    val srHz = report.header.effectiveSrHz.takeIf { it > 0.0 }
        ?: report.header.nominalSrHz.toDouble()
    val physicalPxPerMm = pxPerMm(calibration)

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        VerdictBand(
            report = report,
            recordedAt = text.timestamp(report.header.tsStartMs),
        )

        if (report.verdict.staleBundle) {
            Text(
                text = stringResource(R.string.ecg_stale_analysis),
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }

        StripControls(
            mode = mode,
            bandwidth = bandwidth,
            spec = spec,
            onMode = onMode,
            onSpec = onSpec,
            onBandwidth = onBandwidth,
        )

        if (report.displaySamples.size < 2) {
            Text(
                text = stringResource(R.string.strip_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (mode == StripMode.SHEET) {
            EcgSheetStrip(
                samples = report.displaySamples,
                durationSec = report.header.durationSec,
                srHz = srHz,
                spec = spec,
                rPeaksMs = report.beats.rPeaksMs,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            EcgTrueScaleStrip(
                samples = report.displaySamples,
                durationSec = report.header.durationSec,
                srHz = srHz,
                spec = spec,
                rPeaksMs = report.beats.rPeaksMs,
                pxPerMm = physicalPxPerMm,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            text = stringResource(
                if (mode == StripMode.SHEET) {
                    R.string.strip_caption_sheet
                } else {
                    R.string.strip_caption_true_scale
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${text.scale(report.header)} · ${text.filter(report.header)}",
            style = EcgType.dataSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.tight))
        Text(
            text = stringResource(R.string.section_measurements),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MeasurementTable(report)

        SignalQualityCard(report)

        Text(
            text = stringResource(R.string.ecg_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.section))
    }
}

@Composable
private fun StripControls(
    mode: StripMode,
    bandwidth: EcgBandwidth,
    spec: StripSpec,
    onMode: (StripMode) -> Unit,
    onSpec: (StripSpec) -> Unit,
    onBandwidth: (EcgBandwidth) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            StripMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { onMode(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, StripMode.entries.size),
                ) {
                    Text(
                        stringResource(
                            if (entry == StripMode.SHEET) {
                                R.string.strip_mode_sheet
                            } else {
                                R.string.strip_mode_true_scale
                            },
                        ),
                    )
                }
            }
        }

        // Speed and gain only mean something once the drawing is to scale; on the
        // fitted sheet they would change the shape without changing the size.
        if (mode == StripMode.TRUE_SCALE) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                StripSpec.SPEED_OPTIONS.forEach { speed ->
                    FilterChip(
                        selected = spec.speedMmPerSec == speed,
                        onClick = { onSpec(spec.copy(speedMmPerSec = speed)) },
                        label = {
                            Text(
                                stringResource(R.string.strip_speed_chip, trim(speed)),
                                style = EcgType.dataSmall,
                            )
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
                StripSpec.GAIN_OPTIONS.forEach { gain ->
                    FilterChip(
                        selected = spec.gainMmPerMv == gain,
                        onClick = { onSpec(spec.copy(gainMmPerMv = gain)) },
                        label = {
                            Text(
                                stringResource(R.string.strip_gain_chip, trim(gain)),
                                style = EcgType.dataSmall,
                            )
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.tight)) {
            FilterChip(
                selected = bandwidth == EcgBandwidth.DIAGNOSTIC,
                onClick = { onBandwidth(EcgBandwidth.DIAGNOSTIC) },
                label = { Text(stringResource(R.string.strip_bandwidth_diagnostic)) },
            )
            FilterChip(
                selected = bandwidth == EcgBandwidth.MONITOR,
                onClick = { onBandwidth(EcgBandwidth.MONITOR) },
                label = { Text(stringResource(R.string.strip_bandwidth_monitor)) },
            )
        }
        Text(
            text = stringResource(
                if (bandwidth == EcgBandwidth.DIAGNOSTIC) {
                    R.string.strip_bandwidth_hint_diagnostic
                } else {
                    R.string.strip_bandwidth_hint_monitor
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun trim(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
