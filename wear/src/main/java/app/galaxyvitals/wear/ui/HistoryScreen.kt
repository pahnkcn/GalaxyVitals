package app.galaxyvitals.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.R
import app.galaxyvitals.wear.ui.components.PlateCaption
import app.galaxyvitals.wear.ui.components.StatusBand
import app.galaxyvitals.wear.ui.components.plate
import app.galaxyvitals.wear.ui.theme.Plate
import app.galaxyvitals.wear.ui.theme.listSideMargin
import app.galaxyvitals.wear.ui.theme.plateTaper
import app.galaxyvitals.wear.ui.theme.safeVerticalInset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recordings held on the watch.
 *
 * Ruled rows rather than cards. A card here spends a fill, a corner and a
 * shadow saying "separate object" about rows that are already obviously
 * separate; one dp of outline says it for nothing and leaves the rate as the
 * only thing with any weight on the row.
 *
 * Each row is two runs: the rate against its time, then the length and the
 * wrist underneath. Naming the app on every row inside the app's own history
 * was noise.
 *
 * Rows narrow as they approach the top and bottom of the glass — see
 * [plateTaper]. That is the same rule the static plate tiers come from, applied
 * to something that moves, and it is why the list ends in a taper rather than a
 * clipped corner.
 */
@Composable
fun HistoryScreen(
    sessions: List<ParsedEcgFile>,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }

    // An empty list is not a list. Centring the message keeps it in the widest
    // band instead of leaving it stranded under a header.
    if (sessions.isEmpty()) {
        ScreenScaffold(scrollInfoProvider = null) {
            Box(
                Modifier.fillMaxSize().padding(vertical = safeVerticalInset()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.wear_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.plate(Plate.Core),
                )
            }
        }
        return
    }

    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val time = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    val inset = safeVerticalInset()

    ScreenScaffold(scrollState = columnState) {
        TransformingLazyColumn(
            state = columnState,
            contentPadding = PaddingValues(vertical = inset),
            modifier = Modifier.fillMaxSize().padding(horizontal = listSideMargin()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .plateTaper(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StatusBand(
                        stringResource(R.string.wear_history_count, sessions.size),
                    )
                    RowRule(Modifier.padding(top = HEADER_GAP))
                }
            }
            itemsIndexed(sessions, key = { _, session -> session.sessionId }) { index, session ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .plateTaper(),
                ) {
                    SessionRow(session, time)
                    if (index != sessions.lastIndex) RowRule()
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: ParsedEcgFile, time: SimpleDateFormat) {
    Column(Modifier.fillMaxWidth().padding(vertical = ROW_PADDING)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = WatchSessionBpm.displayBpmText(session),
                    style = MaterialTheme.typography.numeralSmall,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.wear_unit_bpm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                )
            }
            Text(
                text = time.format(Date(session.tsStartMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PlateCaption(
            text = stringResource(
                R.string.wear_history_row,
                session.durationSec.toInt(),
                stringResource(wristRes(session.wrist)),
            ),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun RowRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

private fun wristRes(wrist: Wrist): Int = when (wrist) {
    Wrist.RIGHT -> R.string.wear_wrist_right
    else -> R.string.wear_wrist_left
}

private val ROW_PADDING = 8.dp
private val HEADER_GAP = 8.dp
