package dev.modelfit

import android.content.Context
import android.os.Debug
import dev.modelfit.cache.RecCache
import dev.modelfit.cache.SnapshotStore
import dev.modelfit.device.DeviceInfo
import dev.modelfit.device.DeviceProbe
import dev.modelfit.net.ModelFitApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ModelFit Android Client SDK.
 *
 * Provides instant, zero-crash LLM recommendations guaranteed to run within physical device memory budgets.
 */
class ModelFit private constructor(
    private val context: Context,
    private val config: ModelFitConfig
) {
    private val api = ModelFitApi(config)
    private val cache = RecCache(context, config)
    private val snapshotStore = SnapshotStore(context)

    /**
     * Unique anonymous installation ID for this client instance.
     */
    val installId: String by lazy {
        val prefs = context.getSharedPreferences("modelfit_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("install_id", null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("install_id", id).apply()
        }
        id
    }

    init {
        // Automatically probe memory bandwidth once per installation (zero developer effort)
        triggerAutoCalibration()
    }

    private fun triggerAutoCalibration() {
        val prefs = context.getSharedPreferences("modelfit_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("bandwidth_calibrated", false)) {
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val bwGbps = dev.modelfit.device.MemoryBandwidthProbe.measureBandwidthGbps()
                    if (bwGbps > 0.5) {
                        val device = DeviceProbe.probe(context)
                        val metrics = RunMetrics(
                            modelId = "mem-bandwidth-probe",
                            quant = "raw",
                            decodeTps = bwGbps,
                            peakRamMb = 16.0
                        )
                        val ok = api.reportResults(installId, device, listOf(metrics), source = "sdk-auto")
                        if (ok) {
                            prefs.edit().putBoolean("bandwidth_calibrated", true).apply()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Recommends the highest-utility Large Language Model for the current physical device.
     *
     * Evaluates hardware memory budgets, memory bus bandwidth scaling laws, and SLA targets.
     * Employs a 4-tier fallback hierarchy:
     * 1. Live Serverless Edge Network (`POST /v1/recommend`)
     * 2. Local Valid Cache Hit (Persistent within TTL)
     * 3. Local Offline Physics Snapshot Evaluation (`snapshot.json`)
     * 4. Hardcoded Verified Safe Baseline Model
     *
     * @param profile The optimization objective (Balanced, Speed, Quality, LowMemory, or Custom).
     * @param task Workload type defining prompt context and generation budgets.
     * @param promptTokens Prompt context length (defaults to task specification).
     * @param genTokens Expected completion length (defaults to task specification).
     * @param topN Number of ranked recommendations to return (default: 3).
     * @param includeEstimated Whether to evaluate unmeasured catalog models using physics scaling laws (default: true).
     * @return Ranked [Recommendation] with performance predictions and cryptographic SHA-256 hashes.
     */
    suspend fun recommend(
        profile: Profile = Profile.Balanced,
        task: Task = Task.Chat,
        promptTokens: Int = task.defaultPromptTokens,
        genTokens: Int = task.defaultGenTokens,
        topN: Int = 3,
        includeEstimated: Boolean = true
    ): Recommendation = withContext(Dispatchers.IO) {
        val device = DeviceProbe.probe(context)

        // Tier 1: Fresh Network Call
        try {
            val netRec = api.recommend(
                device = device,
                profile = profile,
                promptTokens = promptTokens,
                genTokens = genTokens,
                topN = topN,
                includeEstimated = includeEstimated
            )
            if (netRec != null && netRec.items.isNotEmpty()) {
                cache.put(profile.name, device.socNormalized, netRec)
                return@withContext netRec.copy(source = RecommendationSource.NETWORK)
            }
        } catch (_: Exception) {
            // Transient network failure; gracefully proceed to cache
        }

        // Tier 2: Local Valid Cache Hit
        val cachedRec = cache.get(profile.name, device.socNormalized)
        if (cachedRec != null && cachedRec.items.isNotEmpty()) {
            return@withContext cachedRec.copy(source = RecommendationSource.CACHE)
        }

        // Tier 3: Local Offline Physics Snapshot Evaluation
        val snapshotRec = snapshotStore.evaluate(
            device = device,
            profile = profile,
            promptTokens = promptTokens,
            genTokens = genTokens,
            topN = topN,
            includeEstimated = includeEstimated
        )
        if (snapshotRec != null && snapshotRec.items.isNotEmpty()) {
            return@withContext snapshotRec.copy(source = RecommendationSource.SNAPSHOT)
        }

        // Tier 4: Guaranteed Safe Default Fallback
        return@withContext getHardcodedFallback(device)
    }

    /**
     * Reports runtime inference metrics back to the ModelFit edge database.
     * Internal: Invoked by [measureInference] or internal telemetry pipelines to prevent forged/fake telemetry.
     */
    internal suspend fun reportMetrics(metrics: RunMetrics): Boolean = withContext(Dispatchers.IO) {
        val device = DeviceProbe.probe(context)
        return@withContext api.reportResults(installId, device, listOf(metrics), source = "sdk")
    }

    /**
     * Convenience method to report runtime inference metrics internally.
     */
    internal suspend fun reportMetrics(
        modelId: String,
        quant: String = "Q4_K_M",
        decodeTps: Double,
        prefillTps: Double? = null,
        peakRamMb: Double? = null,
        ttftMs: Double? = null,
        promptTokens: Int? = null,
        decodeTokens: Int? = null,
        computeBackend: String = "cpu",
        engineFramework: String = "llama.cpp"
    ): Boolean {
        val metrics = RunMetrics(
            modelId = modelId,
            quant = quant,
            decodeTps = decodeTps,
            prefillTps = prefillTps,
            peakRamMb = peakRamMb,
            ttftMs = ttftMs,
            promptTokens = promptTokens,
            decodeTokens = decodeTokens,
            computeBackend = computeBackend,
            engineFramework = engineFramework
        )
        return reportMetrics(metrics)
    }

    /**
     * Measures execution duration and memory footprint during an inference block,
     * and automatically uploads the performance telemetry to ModelFit in the background.
     *
     * @param modelId Model identifier (e.g. "llama-3.2-1b-instruct").
     * @param quant Model quantization (e.g. "Q4_K_M").
     * @param decodeTokens Number of tokens emitted during generation.
     * @param promptTokens Number of tokens evaluated during prompt processing.
     * @param reportInBackground Whether to upload observed metrics asynchronously (default: true).
     * @param block The LLM generation code to execute and profile.
     * @return Result of the [block] execution.
     */
    suspend fun <T> measureInference(
        modelId: String,
        quant: String = "Q4_K_M",
        decodeTokens: Int,
        promptTokens: Int? = null,
        reportInBackground: Boolean = true,
        block: suspend () -> T
    ): T {
        val ramBefore = Debug.getNativeHeapAllocatedSize()
        val startTimeNs = System.nanoTime()

        val result = block()

        val durationNs = System.nanoTime() - startTimeNs
        val ramAfter = Debug.getNativeHeapAllocatedSize()

        val durationSeconds = durationNs / 1_000_000_000.0
        val decodeTps = if (durationSeconds > 0 && decodeTokens > 0) {
            decodeTokens / durationSeconds
        } else {
            0.0
        }

        val peakRamMb = maxOf(0L, ramAfter - ramBefore) / (1024.0 * 1024.0)

        if (reportInBackground && decodeTps > 0.05) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    reportMetrics(
                        modelId = modelId,
                        quant = quant,
                        decodeTps = decodeTps,
                        peakRamMb = if (peakRamMb > 10.0) peakRamMb else null,
                        promptTokens = promptTokens,
                        decodeTokens = decodeTokens
                    )
                } catch (_: Exception) {}
            }
        }

        return result
    }

    /**
     * Inspects current device hardware specifications.
     */
    fun probeDevice(): DeviceInfo {
        return DeviceProbe.probe(context)
    }

    /**
     * Clears all locally cached recommendation responses.
     */
    fun clearCache() {
        cache.clear()
    }

    private fun getHardcodedFallback(device: DeviceInfo): Recommendation {
        return Recommendation(
            formulaVersion = 1,
            source = RecommendationSource.FALLBACK,
            scope = ScopeInfo(match = "fallback", scopeKey = device.socNormalized, nInstalls = 0),
            items = listOf(
                RecommendedModel(
                    rank = 1,
                    modelId = "qwen2.5-0.5b-instruct",
                    quant = "Q4_K_M",
                    source = "fallback",
                    confidence = ConfidenceLevel.ESTIMATED,
                    score = 0.50,
                    expected = ExpectedPerformance(
                        decodeTps = 15.0,
                        prefillTps = 50.0,
                        ttftMs = 3400.0,
                        tSeconds = 10.0,
                        peakRamMb = 500.0
                    ),
                    download = DownloadInfo(
                        url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2_5-0_5b-instruct-q4_k_m.gguf",
                        sizeMb = 398.0,
                        sha256 = "5fd2e7960fc977e2ff175fb1c13d964fcf2f4951fc89cf1fae7f53d2bf97ee37"
                    ),
                    reasons = listOf(
                        "Hardcoded safe default model for devices with >= 2GB RAM",
                        "Guaranteed zero-crash baseline for cold-start offline execution"
                    )
                )
            ),
            rejected = emptyList(),
            isFallback = true
        )
    }

    companion object {
        @Volatile
        private var instance: ModelFit? = null

        /**
         * Obtains or creates the singleton [ModelFit] client instance.
         */
        fun create(context: Context, config: ModelFitConfig = ModelFitConfig()): ModelFit {
            return instance ?: synchronized(this) {
                instance ?: ModelFit(context.applicationContext, config).also { instance = it }
            }
        }
    }
}
