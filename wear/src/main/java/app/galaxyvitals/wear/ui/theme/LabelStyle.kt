package app.galaxyvitals.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Tracking on the small labels, and why Thai gets none of it.
 *
 * The uppercase mono labels — the status band, the plate captions, the rail's
 * end marks — are tracked open so they read as machine labels rather than as
 * words. Thai has no tracking tradition, and worse, the space between glyphs is
 * what separates a base consonant from the marks stacked above and below it:
 * opening it up pulls a cluster apart. The phone makes the same call in
 * `labelStyle()`; this is the watch's copy of it.
 *
 * Uppercasing is left alone rather than branched, because `uppercase()` on Thai
 * returns the string unchanged.
 */
@Composable
@ReadOnlyComposable
fun isThaiLocale(): Boolean = LocalConfiguration.current.locales[0].language == "th"

/** Tracking for an uppercase machine label. */
@Composable
@ReadOnlyComposable
fun labelTracking(): TextUnit = if (isThaiLocale()) 0.sp else LABEL_TRACKING

/** Tracking for the smallest captions, which need a touch more to stay legible. */
@Composable
@ReadOnlyComposable
fun captionTracking(): TextUnit = if (isThaiLocale()) 0.sp else CAPTION_TRACKING

private val LABEL_TRACKING = 0.14.em
private val CAPTION_TRACKING = 0.18.em
