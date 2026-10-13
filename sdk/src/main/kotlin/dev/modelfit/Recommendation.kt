package dev.modelfit

/**
 * Origin of the delivered recommendation.
 */
enum class RecommendationSource {
    /** Fresh live response from the Cloudflare Worker API. */
    NETWORK,
    /** Locally cached response matching the device SoC within TTL. */
    CACHE,
    /** Evaluated locally against the bundled or downloaded snapshot.json physics calibration. */
    SNAPSHOT,
    /** Built-in guaranteed safe default model for devices under zero-connectivity cold start. */
    FALLBACK
}

/**
 * Degree of empirical evidence supporting the recommendation.
 */
enum class ConfidenceLevel {
    /** Measured directly on this exact SoC model with >= 3 independent device installs. */
    MEASURED_EXACT,
    /** Measured on this exact SoC model with 1-2 installs. */
    MEASURED_LOW_N,
    /** Inferred from physical measurements on devices within the same RAM tier bracket. */
    MEASURED_NEIGHBOR,
    /** Predicted via mathematical DRAM memory-bandwidth scaling laws (LOO-MAPE ~17%). */
    ESTIMATED;

    companion object {
        fun fromString(str: String?): ConfidenceLevel = when (str?.lowercase()?.trim()) {
            "measured_exact" -> MEASURED_EXACT
            "measured_low_n" -> MEASURED_LOW_N
            "measured_neighbor" -> MEASURED_NEIGHBOR
            else -> ESTIMATED
        }
    }
}

/**
 * Resolution scope used to evaluate device capabilities.
 */
data class ScopeInfo(
    val match: String,
    val scopeKey: String,
    val nInstalls: Int? = null
)

/**
 * Expected runtime performance figures.
 */
data class ExpectedPerformance(
    val decodeTps: Double,
    val prefillTps: Double,
    val ttftMs: Double,
    val tSeconds: Double,
    val peakRamMb: Double
)

/**
 * Download metadata and cryptographic integrity hash.
 */
data class DownloadInfo(
    val url: String,
    val sizeMb: Double,
    val sha256: String? = null
)

/**
 * A recommended model passing all SLA limits and RAM budgets, ranked by utility score.
 */
data class RecommendedModel(
    val rank: Int,
    val modelId: String,
    val quant: String,
    val source: String, // "measured" or "estimated"
    val confidence: ConfidenceLevel,
    val score: Double,
    val expected: ExpectedPerformance,
    val download: DownloadInfo,
    val reasons: List<String>
)

/**
 * A model disqualified during evaluation with the explicit rationale.
 */
data class RejectedModel(
    val modelId: String,
    val quant: String? = null,
    val reason: String
)

/**
 * Complete recommendation response returned to the calling application.
 */
data class Recommendation(
    val formulaVersion: Int = 1,
    val source: RecommendationSource,
    val scope: ScopeInfo,
    val items: List<RecommendedModel>,
    val rejected: List<RejectedModel> = emptyList(),
    val isFallback: Boolean = (source == RecommendationSource.FALLBACK)
)
