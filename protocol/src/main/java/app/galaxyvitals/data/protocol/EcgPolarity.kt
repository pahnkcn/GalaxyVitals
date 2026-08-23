package app.galaxyvitals.data.protocol

/**
 * Returns the only polarity multiplier downstream ECG consumers should apply.
 *
 * Schema-v1 recordings can already contain wrist-normalized samples while still
 * retaining the original wrist sign in metadata. Applying [signFactor] again to
 * those samples would invert right-wrist recordings twice.
 */
fun ParsedEcgFile.effectivePolarity(): Float =
    effectivePolarity(signFactor, polarityNormalized)

internal fun effectivePolarity(signFactor: Int, polarityNormalized: Boolean): Float =
    if (polarityNormalized) 1f else signFactor.toFloat()
