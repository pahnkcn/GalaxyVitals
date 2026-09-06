package app.galaxyvitals.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.galaxyvitals.data.protocol.ParsedEcgFile
import app.galaxyvitals.domain.Wrist
import app.galaxyvitals.wear.R
import app.galaxyvitals.wear.ui.theme.listSideMargin
import app.galaxyvitals.wear.ui.theme.screenWidthFraction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recordings held on the watch.
 *
 * Each row is two runs, not four: the rate and the time. Naming the app on
 * every row inside the app's own history was noise, and it made the card tall
 * enough that the bezel hard-clipped it before the list's own scaling could
 * shrink it.
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
        ScreenScaffold(scrollInfoProvider = null) { contentPadding ->
            Box(
                Modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.wear_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = screenWidthFraction(0.66f)),
                )
            }
        }
        return
    }

    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val time = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }

    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().padding(horizontal = listSideMargin()),
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(
                            ListHeaderDefaults.minimumTopListContentPadding,
                        ),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(R.string.wear_history))
                }
            }
            items(sessions, key = { it.sessionId }) { session ->
                TitleCard(
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .minimumVerticalContentPadding(
                            CardDefaults.minimumVerticalListContentPadding,
                        ),
                    transformation = SurfaceTransformation(transformationSpec),
                    title = {
                        Text(
                            stringResource(
                                R.string.wear_bpm_value,
                                WatchSessionBpm.displayBpmText(session),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    time = { Text(time.format(Date(session.tsStartMs)), maxLines = 1) },
                ) {
                    Text(
                        stringResource(
                            R.string.wear_history_row,
                            session.durationSec.toInt(),
                            stringResource(wristRes(session.wrist)),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun wristRes(wrist: Wrist): Int = when (wrist) {
    Wrist.RIGHT -> R.string.wear_wrist_right
    else -> R.string.wear_wrist_left
}
