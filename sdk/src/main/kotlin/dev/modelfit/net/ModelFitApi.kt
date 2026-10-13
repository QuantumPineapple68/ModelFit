package dev.modelfit.net

import dev.modelfit.ModelFitConfig
import dev.modelfit.Profile
import dev.modelfit.Recommendation
import dev.modelfit.RecommendationSource
import dev.modelfit.device.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ModelFitApi(private val config: ModelFitConfig) {

    suspend fun recommend(
        device: DeviceInfo,
        profile: Profile,
        promptTokens: Int,
        genTokens: Int,
        topN: Int,
        includeEstimated: Boolean
    ): Recommendation? = withContext(Dispatchers.IO) {
        val payload = Dtos.buildRecommendRequestBody(
            device = device,
            profile = profile,
            promptTokens = promptTokens,
            genTokens = genTokens,
            topN = topN,
            includeEstimated = includeEstimated
        )

        val fullUrl = "${config.baseUrl.trimEnd('/')}/v1/recommend"
        var attempts = 0
        val maxAttempts = 1 + config.retries

        while (attempts < maxAttempts) {
            attempts++
            var conn: HttpURLConnection? = null
            try {
                val url = URL(fullUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = config.timeoutMs
                    readTimeout = config.timeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    if (!config.apiKey.isNullOrBlank()) {
                        setRequestProperty("X-Api-Key", config.apiKey)
                    }
                }

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    return@withContext Dtos.parseRecommendationResponse(body, RecommendationSource.NETWORK)
                } else if (responseCode in 400..499) {
                    // Non-retryable client error
                    return@withContext null
                }
            } catch (e: Exception) {
                if (attempts >= maxAttempts) {
                    return@withContext null
                }
            } finally {
                conn?.disconnect()
            }
        }

        return@withContext null
    }

    suspend fun fetchSnapshot(): String? = withContext(Dispatchers.IO) {
        val fullUrl = "${config.baseUrl.trimEnd('/')}/v1/snapshot"
        var conn: HttpURLConnection? = null
        try {
            val url = URL(fullUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = config.timeoutMs
                readTimeout = config.timeoutMs
                setRequestProperty("Accept", "application/json")
                if (!config.apiKey.isNullOrBlank()) {
                    setRequestProperty("X-Api-Key", config.apiKey)
                }
            }

            if (conn.responseCode in 200..299) {
                return@withContext conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
        return@withContext null
    }

    suspend fun reportResults(
        installId: String,
        device: DeviceInfo,
        metrics: List<dev.modelfit.RunMetrics>,
        source: String = "sdk"
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = Dtos.buildIngestRequestBody(installId, device, metrics, source)
        val fullUrl = "${config.baseUrl.trimEnd('/')}/v1/results"
        var conn: HttpURLConnection? = null
        try {
            val url = URL(fullUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = config.timeoutMs
                readTimeout = config.timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (!config.apiKey.isNullOrBlank()) {
                    setRequestProperty("X-Api-Key", config.apiKey)
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            return@withContext (conn.responseCode in 200..299)
        } catch (_: Exception) {
            return@withContext false
        } finally {
            conn?.disconnect()
        }
    }
}
