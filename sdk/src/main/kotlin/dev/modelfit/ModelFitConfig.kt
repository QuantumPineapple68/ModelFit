package dev.modelfit

/**
 * Configuration options for the ModelFit recommendation client.
 *
 * @property baseUrl The root URL of the ModelFit backend (default: production Cloudflare Worker).
 * @property apiKey Optional API key sent via `X-Api-Key` header.
 * @property cacheTtlDays Number of days cached recommendations remain valid (default: 7).
 * @property timeoutMs Network request connection and read timeout in milliseconds (default: 5000).
 * @property retries Number of retry attempts on transient network failures (default: 1).
 */
data class ModelFitConfig(
    val baseUrl: String = "https://benchdb.benchdb.workers.dev",
    val apiKey: String? = null,
    val cacheTtlDays: Int = 7,
    val timeoutMs: Int = 5000,
    val retries: Int = 1
)
