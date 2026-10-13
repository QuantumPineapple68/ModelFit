package dev.modelfit

import java.util.UUID

/**
 * Observed runtime metrics from a real-world inference run on the device.
 *
 * Reported to the ModelFit edge database to maintain real-time freshness and improve future recommendations.
 *
 * @property modelId Identifier of the model (e.g. "llama-3.2-1b-instruct").
 * @property quant Quantization format (e.g. "Q4_K_M").
 * @property decodeTps Tokens generated per second during the decode phase.
 * @property prefillTps Tokens ingested per second during prompt evaluation (optional).
 * @property ttftMs Time To First Token in milliseconds (optional).
 * @property peakRamMb Peak memory consumed by the model in megabytes (optional).
 * @property promptTokens Number of tokens in the input prompt (optional).
 * @property decodeTokens Number of tokens emitted (optional).
 * @property computeBackend Backend used (default: "cpu").
 * @property engineFramework Execution engine (default: "llama.cpp").
 * @property contextLength Active context window (default: 2048).
 * @property id Unique observation ID (defaults to a random UUID).
 * @property createdAt Timestamp of the run in milliseconds.
 */
data class RunMetrics(
    val modelId: String,
    val quant: String = "Q4_K_M",
    val decodeTps: Double,
    val prefillTps: Double? = null,
    val ttftMs: Double? = null,
    val peakRamMb: Double? = null,
    val promptTokens: Int? = null,
    val decodeTokens: Int? = null,
    val computeBackend: String = "cpu",
    val engineFramework: String = "llama.cpp",
    val contextLength: Int? = 2048,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis()
)
