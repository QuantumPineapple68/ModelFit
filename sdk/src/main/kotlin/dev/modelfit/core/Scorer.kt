package dev.modelfit.core

import dev.modelfit.ConfidenceLevel
import dev.modelfit.Profile
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An evaluated model candidate ready for qualification and scoring.
 */
data class ScoreCandidate(
    val modelId: String,
    val quant: String,
    val source: String, // "measured" or "estimated"
    val confidence: ConfidenceLevel,
    val decodeTps: Double,
    val decodeTpsP25: Double? = null,
    val prefillTps: Double,
    val ttftMs: Double,
    val sustainedTps: Double? = null,
    val peakRamMb: Double,
    val peakRamP90: Double? = null,
    val qualityScore: Double,
    val failRate: Double? = null,
    val nInstalls: Int? = null,
    val downloadUrl: String,
    val sizeMb: Double,
    val sha256: String? = null
)

/**
 * Result of evaluating a candidate against hard constraints and scoring formulas.
 */
data class ScoredResult(
    val candidate: ScoreCandidate,
    val passed: Boolean,
    val rejectReason: String? = null,
    val score: Double,
    val tSeconds: Double,
    val reasons: List<String>
)

/**
 * Hardware and profile context for scoring evaluation.
 */
data class ScoreContext(
    val profile: Profile,
    val totalRamMb: Int,
    val availRamMb: Int? = null,
    val promptTokens: Int = 170,
    val genTokens: Int = 100,
    val estimatePenalty: Double = 0.05,
    val estimateSafety: Double = 0.75
)

/**
 * Multi-attribute utility scoring engine and SLA constraint evaluator.
 * Exact 1:1 Kotlin port of lib/score.ts.
 */
object Scorer {
    /**
     * Evaluates hard disqualification filters and calculates multi-objective utility score.
     */
    fun scoreAndFilterCandidate(candidate: ScoreCandidate, ctx: ScoreContext): ScoredResult {
        val profile = ctx.profile
        val fractionBudget = profile.ramBudgetFraction * ctx.totalRamMb
        val ramBudgetMb = if (ctx.availRamMb != null && ctx.availRamMb > 0) {
            min(fractionBudget.toDouble(), 0.8 * ctx.availRamMb)
        } else {
            fractionBudget.toDouble()
        }

        val isEstimated = candidate.source == "estimated"
        val filterRam = if (isEstimated) candidate.peakRamMb else (candidate.peakRamP90 ?: candidate.peakRamMb)
        val filterDecode = if (isEstimated) {
            candidate.decodeTps * ctx.estimateSafety
        } else {
            candidate.decodeTpsP25 ?: candidate.decodeTps
        }

        val ttftS = ctx.promptTokens.toDouble() / max(0.1, candidate.prefillTps)
        val tSeconds = ttftS + ctx.genTokens.toDouble() / max(0.1, candidate.decodeTps)
        val roundedTs = (tSeconds * 10.0).roundToInt() / 10.0

        // 1. Hard filters
        if (candidate.failRate != null && candidate.failRate >= 0.5) {
            return ScoredResult(
                candidate = candidate,
                passed = false,
                rejectReason = "High failure rate (${(candidate.failRate * 100).roundToInt()}%) on this device class",
                score = 0.0,
                tSeconds = roundedTs,
                reasons = emptyList()
            )
        }

        if (filterRam > ramBudgetMb) {
            return ScoredResult(
                candidate = candidate,
                passed = false,
                rejectReason = "Exceeds RAM budget: ${filterRam.roundToInt()} MB > ${ramBudgetMb.roundToInt()} MB",
                score = 0.0,
                tSeconds = roundedTs,
                reasons = emptyList()
            )
        }

        if (filterDecode < profile.minDecodeTps) {
            return ScoredResult(
                candidate = candidate,
                passed = false,
                rejectReason = "Decode speed too slow: ${String.format(Locale.US, "%.1f", filterDecode)} tok/s < ${profile.minDecodeTps} tok/s required",
                score = 0.0,
                tSeconds = roundedTs,
                reasons = emptyList()
            )
        }

        if (tSeconds > profile.maxTs) {
            return ScoredResult(
                candidate = candidate,
                passed = false,
                rejectReason = "${ctx.genTokens}-token reply takes ${String.format(Locale.US, "%.1f", tSeconds)} s (> ${profile.maxTs} s limit)",
                score = 0.0,
                tSeconds = roundedTs,
                reasons = emptyList()
            )
        }

        // 2. Scoring terms (each 0..1)
        val uLat = min(1.0, profile.tRefS.toDouble() / max(0.1, tSeconds))
        val uMem = min(1.0, max(0.0, 1.0 - candidate.peakRamMb / ramBudgetMb))
        val q = min(1.0, max(0.0, candidate.qualityScore))

        val totalW = profile.weights.speed + profile.weights.quality + profile.weights.memory
        val wL = profile.weights.speed / totalW
        val wQ = profile.weights.quality / totalW
        val wM = profile.weights.memory / totalW

        var rawScore = wL * uLat + wQ * q + wM * uMem
        if (isEstimated) {
            rawScore = max(0.01, rawScore - ctx.estimatePenalty)
        }
        val score = (rawScore * 1000.0).roundToInt() / 1000.0

        // 3. Human-readable reasons
        val reasons = mutableListOf<String>()
        reasons.add("fits in RAM (${candidate.peakRamMb.roundToInt()} MB of ${ramBudgetMb.roundToInt()} MB budget)")
        reasons.add("${String.format(Locale.US, "%.1f", tSeconds)} s for a ${ctx.genTokens}-token reply")

        if (isEstimated) {
            reasons.add("estimated from calibration on this chip (±25%)")
        } else if (candidate.confidence == ConfidenceLevel.MEASURED_EXACT) {
            val installs = candidate.nInstalls ?: 1
            reasons.add("measured on this SoC ($installs device${if (installs > 1) "s" else ""})")
        } else if (candidate.confidence == ConfidenceLevel.MEASURED_NEIGHBOR) {
            reasons.add("measured on devices in the same RAM tier")
        }

        if (candidate.sustainedTps != null && candidate.decodeTps > 0) {
            val ratio = candidate.sustainedTps / candidate.decodeTps
            if (ratio < 0.70) {
                reasons.add("slows down when hot (sustained ${(ratio * 100).roundToInt()}% of decode)")
            }
        }

        return ScoredResult(
            candidate = candidate,
            passed = true,
            score = score,
            tSeconds = roundedTs,
            reasons = reasons
        )
    }
}
