package dev.modelfit.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mathematical calibration parameters extracted for a specific SoC or RAM tier class.
 *
 * @property decodeAS Fixed per-token CPU scheduling and thread synchronization latency (seconds).
 * @property decodeBGbps Usable DRAM memory bandwidth of the SoC (Gigabytes per second).
 * @property prefillK Compute throughput constant for batch prompt GEMM evaluation.
 * @property ramOverheadMb Static runtime KV-cache and framework memory overhead (megabytes).
 */
data class CalibrationParameters(
    val decodeAS: Double,
    val decodeBGbps: Double,
    val prefillK: Double,
    val ramOverheadMb: Double
)

/**
 * Inferred performance metrics calculated for an unmeasured candidate model.
 */
data class EstimatedMetrics(
    val decodeTps: Double,
    val prefillTps: Double,
    val ttftMs: Double,
    val tSeconds: Double,
    val peakRamMb: Double,
    val qualityScore: Double
)

/**
 * First-principles physics estimator for GGUF autoregressive language models.
 * Exact 1:1 Kotlin port of lib/estimate.ts.
 */
object Estimator {
    val DEFAULT_QUANT_FACTORS = mapOf(
        "Q8_0" to 1.00,
        "Q6_K" to 0.99,
        "Q5_K_M" to 0.98,
        "Q4_K_M" to 0.96,
        "Q4_0" to 0.93,
        "Q3_K_M" to 0.88,
        "Q2_K" to 0.70
    )

    /**
     * Estimates decode speed, prefill speed, end-to-end latency, and memory footprint
     * using the chip's fitted bandwidth equation: 1/decode = a + size_GB / B.
     */
    fun estimateModelMetrics(
        sizeMb: Double,
        paramsB: Double,
        quant: String,
        qualityScore: Double?,
        calibration: CalibrationParameters,
        promptTokens: Int = 170,
        genTokens: Int = 100
    ): EstimatedMetrics {
        val sizeGb = sizeMb / 1000.0
        val a = calibration.decodeAS
        val b = calibration.decodeBGbps
        val k = calibration.prefillK
        val overhead = calibration.ramOverheadMb

        val decodeTps = 1.0 / (a + sizeGb / b)
        val prefillTps = k / max(0.1, paramsB)

        val ttftS = promptTokens.toDouble() / prefillTps
        val tSeconds = ttftS + genTokens.toDouble() / decodeTps
        val ttftMs = (ttftS * 1000.0).roundToInt().toDouble()
        val peakRamMb = (sizeMb + overhead).roundToInt().toDouble()

        val resolvedQuality: Double = if (qualityScore != null && qualityScore > 0.0) {
            qualityScore
        } else {
            val rawPrior = 0.40 + 0.33 * log10(max(0.1, paramsB))
            val clampedPrior = min(0.90, max(0.10, rawPrior))
            val factor = DEFAULT_QUANT_FACTORS[quant] ?: 0.96
            clampedPrior * factor
        }

        return EstimatedMetrics(
            decodeTps = (decodeTps * 100.0).roundToInt() / 100.0,
            prefillTps = (prefillTps * 100.0).roundToInt() / 100.0,
            ttftMs = ttftMs,
            tSeconds = (tSeconds * 100.0).roundToInt() / 100.0,
            peakRamMb = peakRamMb,
            qualityScore = (resolvedQuality * 1000.0).roundToInt() / 1000.0
        )
    }
}
