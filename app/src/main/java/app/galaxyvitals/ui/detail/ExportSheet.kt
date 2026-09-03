package app.galaxyvitals.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.galaxyvitals.R
import app.galaxyvitals.export.ExportFormat
import app.galaxyvitals.ui.theme.Spacing

/**
 * Pick a format, add a note if it helps the doctor, share.
 *
 * The note is a text field and nothing more: it is written into the exported
 * file and then forgotten. Storing a name or a symptom would turn a local
 * recording into a health record, which this app deliberately is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    running: Boolean,
    onDismiss: () -> Unit,
    onExport: (ExportFormat, String) -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(
                start = Spacing.page,
                end = Spacing.page,
                bottom = Spacing.section,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            Text(
                text = stringResource(R.string.export_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.export_note_label)) },
                placeholder = { Text(stringResource(R.string.export_note_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.export_note_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (running) {
                Row(
                    Modifier.padding(vertical = Spacing.item),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.export_preparing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                FormatRow(
                    title = R.string.export_pdf,
                    body = R.string.export_pdf_body,
                ) { onExport(ExportFormat.PDF, note) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                FormatRow(
                    title = R.string.export_xlsx,
                    body = R.string.export_xlsx_body,
                ) { onExport(ExportFormat.XLSX, note) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                FormatRow(
                    title = R.string.export_csv,
                    body = R.string.export_csv_body,
                ) { onExport(ExportFormat.CSV_GZ, note) }
            }
        }
    }
}

@Composable
private fun FormatRow(title: Int, body: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.item),
        verticalArrangement = Arrangement.spacedBy(Spacing.hair),
    ) {
        Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
