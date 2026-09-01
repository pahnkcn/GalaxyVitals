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
    /**
     * Absolute physiological guard only - roughly 30-200 bpm. Narrower than
     * this is the adaptive check's job: a fixed window cannot tell a missed beat
     * from bradycardia, and a 333 ms floor sits exactly on 180 bpm.
     */
    val minRrMs: Double,
    val maxRrMs: Double,
    /** Accept band around the running median RR, as a fraction of that median. */
    val rrPlausibleLow: Double,
    val rrPlausibleHigh: Double,
    /** Half-width of the "this RR is really two beats" / "really half a beat" bands. */
    val rrMultipleTolerance: Double,
    /** How many recent accepted RRs the running median is taken over. */
    val rrMedianWindow: Int,
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
    val minEnvelopeSnr: Double,
    val snrBypassBsqi: Double,
    val minPeakToMedian: Double,
    val dualPolarity: Boolean,
) {
    companion object {
        const val VERSION = 4
        const val PROVENANCE =
            "physionet-dev-split-v1; thr=0.375; refractory=300ms; secondary-twave; " +
                "minSignalNoise=3.0; snrBypassBsqi=0.95; minPeakToMedian=0.20; " +
                "no-tile; polarity-bsqi-dual; conditioned-detector-input; " +
                "half-window-delay; abs-refine=50ms; match=50ms; subsample-rr; " +
                "adaptive-rr-plausibility=0.6-1.6x; rr-guard=300-2000ms; freeze=2026-09-01-v4"

        val DEFAULT = EcgBeatDetectorConfig(
            version = VERSION,
            provenance = PROVENANCE,
            matchToleranceMs = 50,
            refineRadiusMs = 50,
            minRrMs = 300.0,
            maxRrMs = 2_000.0,
            rrPlausibleLow = 0.6,
            rrPlausibleHigh = 1.6,
            rrMultipleTolerance = 0.25,
            rrMedianWindow = 8,
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
            minEnvelopeSnr = 3.0,
            snrBypassBsqi = 0.95,
            minPeakToMedian = 0.20,
            dualPolarity = true,
        )
    }
}
