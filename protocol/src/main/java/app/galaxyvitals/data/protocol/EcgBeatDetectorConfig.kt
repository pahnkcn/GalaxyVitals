package app.galaxyvitals.data.protocol

/**
 * Versioned beat-detector thresholds. Values were selected on the PhysioNet
 * **dev** split only (`tools/ecg_benchmark/physionet_split.csv`) and frozen
 * before locked-set evaluation.
 */
data class EcgBeatDetectorConfig(
    val version: Int,
    val provenance: String,
    val matchToleranceMs: Int,
    val refineRadiusMs: Int,
    val minRrMs: Double,
    val maxRrMs: Double,
    val minRrCount: Int,
    val minBsqi: Double,
    val primaryIntegrationMs: Int,
    val secondaryIntegrationMs: Int,
    val primaryRefractoryMs: Int,
    val secondaryRefractoryMs: Int,
    val twaveMs: Int,
    val twaveAmpRatio: Double,
    val secondaryTwave: Boolean,
    val searchbackRr: Double,
    val searchbackScale: Double,
    val searchback: Boolean,
    val ewma: Double,
    val thresholdNoiseWeight: Double,
    val learnSeconds: Int,
    val maxRrCv: Double,
    val dualPolarity: Boolean,
) {
    companion object {
        const val VERSION = 1
        const val PROVENANCE =
            "physionet-dev-split-v1; thr=0.375; refractory=300ms; secondary-twave; maxRrCv=0.22; fallback-dual-polarity; freeze=2026-08-26"

        val DEFAULT = EcgBeatDetectorConfig(
            version = VERSION,
            provenance = PROVENANCE,
            matchToleranceMs = 150,
            refineRadiusMs = 100,
            minRrMs = 333.0,
            maxRrMs = 1_500.0,
            minRrCount = 4,
            minBsqi = 0.80,
            primaryIntegrationMs = 150,
            secondaryIntegrationMs = 80,
            primaryRefractoryMs = 300,
            secondaryRefractoryMs = 300,
            twaveMs = 360,
            twaveAmpRatio = 0.5,
            secondaryTwave = true,
            searchbackRr = 1.66,
            searchbackScale = 0.5,
            searchback = true,
            ewma = 0.125,
            thresholdNoiseWeight = 0.375,
            learnSeconds = 2,
            maxRrCv = 0.22,
            dualPolarity = true,
        )
    }
}
