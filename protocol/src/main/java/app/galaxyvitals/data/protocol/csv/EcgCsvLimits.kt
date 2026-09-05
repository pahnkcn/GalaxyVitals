package app.galaxyvitals.data.protocol.csv

/**
 * Bounds shared by the line reader and the metadata reader.
 *
 * An ECG file arrives from a watch or from a user-chosen document, so every
 * length the parser walks has to be capped before it is walked. These two are
 * the ones both readers need: the metadata object is itself one line.
 */

/** Longest single CSV line, metadata blob included. */
internal const val MAX_LINE_CHARS = 16_384

/** Nesting bound for the metadata object, so a hostile file cannot recurse. */
internal const val MAX_JSON_DEPTH = 32
