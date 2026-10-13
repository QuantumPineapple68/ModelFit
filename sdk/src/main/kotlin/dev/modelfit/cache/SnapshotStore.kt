package dev.modelfit.cache

import android.content.Context
import dev.modelfit.ConfidenceLevel
import dev.modelfit.DownloadInfo
import dev.modelfit.ExpectedPerformance
import dev.modelfit.Profile
import dev.modelfit.Recommendation
import dev.modelfit.RecommendationSource
import dev.modelfit.RecommendedModel
import dev.modelfit.RejectedModel
import dev.modelfit.ScopeInfo
import dev.modelfit.core.CalibrationParameters
import dev.modelfit.core.Estimator
import dev.modelfit.core.ScoreCandidate
import dev.modelfit.core.ScoreContext
import dev.modelfit.core.Scorer
import dev.modelfit.device.DeviceInfo
import dev.modelfit.device.SocNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

class SnapshotStore(private val context: Context) {

    fun evaluate(
        device: DeviceInfo,
        profile: Profile,
        promptTokens: Int,
        genTokens: Int,
        topN: Int,
        includeEstimated: Boolean
    ): Recommendation? {
        val jsonString = loadSnapshotJson() ?: return null
        return try {
            evaluateSnapshot(jsonString, device, profile, promptTokens, genTokens, topN, includeEstimated)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadSnapshotJson(): String? {
        // 1. Check downloaded snapshot update in app storage
        val localFile = File(context.filesDir, "modelfit/snapshot.json")
        if (localFile.exists() && localFile.length() > 0) {
            try {
                return localFile.readText(Charsets.UTF_8)
            } catch (_: Exception) {}
        }

        // 2. Fall back to bundled asset
        return try {
            val assetStream: InputStream = context.assets.open("modelfit/snapshot.json")
            assetStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun evaluateSnapshot(
        jsonString: String,
        device: DeviceInfo,
        profile: Profile,
        promptTokens: Int,
        genTokens: Int,
        topN: Int,
        includeEstimated: Boolean
    ): Recommendation {
        val root = JSONObject(jsonString)
        val formulaVersion = root.optInt("formula_version", 1)

        val calibsArray = root.optJSONArray("calibrations") ?: JSONArray()
        val statsArray = root.optJSONArray("stats") ?: JSONArray()
        val catalogArray = root.optJSONArray("catalog") ?: JSONArray()

        val socKey = device.socNormalized
        val tierKey = SocNormalizer.ramTier(device.totalRamMb).toString()

        // 1. Resolve Calibration Parameters
        var resolvedCalib: CalibrationParameters? = null
        var matchScope = "global"
        var scopeKey = "all"
        var nInstalls: Int? = null

        // Try exact SoC
        for (i in 0 until calibsArray.length()) {
            val c = calibsArray.getJSONObject(i)
            if (c.optString("scope") == "soc" && c.optString("scope_key") == socKey) {
                resolvedCalib = parseCalib(c)
                matchScope = "exact"
                scopeKey = socKey
                break
            }
        }

        // Try RAM tier
        if (resolvedCalib == null) {
            for (i in 0 until calibsArray.length()) {
                val c = calibsArray.getJSONObject(i)
                if (c.optString("scope") == "ram_tier" && c.optString("scope_key") == tierKey) {
                    resolvedCalib = parseCalib(c)
                    matchScope = "ram_tier"
                    scopeKey = tierKey
                    break
                }
            }
        }

        // Try global
        if (resolvedCalib == null) {
            for (i in 0 until calibsArray.length()) {
                val c = calibsArray.getJSONObject(i)
                if (c.optString("scope") == "global") {
                    resolvedCalib = parseCalib(c)
                    matchScope = "global"
                    scopeKey = "all"
                    break
                }
            }
        }

        // Bootstrap constants if empty
        val calibration = resolvedCalib ?: CalibrationParameters(
            decodeAS = 0.022,
            decodeBGbps = 9.9,
            prefillK = 29.0,
            ramOverheadMb = 160.0
        )

        val candidates = mutableListOf<ScoreCandidate>()
        val seenModelIds = mutableSetOf<String>()

        // 2. Gather Measured Models for Scope
        for (i in 0 until statsArray.length()) {
            val s = statsArray.getJSONObject(i)
            val sScope = s.optString("scope")
            val sKey = s.optString("scope_key")

            val matchesScope = (sScope == "soc" && sKey == socKey) ||
                    (matchScope != "exact" && sScope == "ram_tier" && sKey == tierKey) ||
                    (matchScope == "global" && sScope == "global")

            if (matchesScope) {
                val modelId = s.optString("model_id")
                seenModelIds.add(modelId)
                val installs = s.optInt("n_installs", 1)
                nInstalls = maxOf(nInstalls ?: 0, installs)

                val conf = if (sScope == "soc") {
                    if (installs >= 3) ConfidenceLevel.MEASURED_EXACT else ConfidenceLevel.MEASURED_LOW_N
                } else ConfidenceLevel.MEASURED_NEIGHBOR

                candidates.add(
                    ScoreCandidate(
                        modelId = modelId,
                        quant = s.optString("quant", "Q4_K_M"),
                        source = "measured",
                        confidence = conf,
                        decodeTps = s.optDouble("decode_tps_p50", 10.0),
                        prefillTps = s.optDouble("prefill_tps_p50", 30.0),
                        ttftMs = s.optDouble("ttft_ms_p50", 5000.0),
                        sustainedTps = if (s.has("sustained_tps_p50")) s.optDouble("sustained_tps_p50") else null,
                        peakRamMb = s.optDouble("peak_ram_mb_p50", 1000.0),
                        peakRamP90 = if (s.has("peak_ram_mb_p90")) s.optDouble("peak_ram_mb_p90") else null,
                        qualityScore = 0.50, // default prior
                        failRate = if (s.has("fail_rate")) s.optDouble("fail_rate") else 0.0,
                        nInstalls = installs,
                        downloadUrl = "https://huggingface.co",
                        sizeMb = s.optDouble("peak_ram_mb_p50", 1000.0) - calibration.ramOverheadMb
                    )
                )
            }
        }

        // 3. Gather Estimated Catalog Models
        if (includeEstimated) {
            for (i in 0 until catalogArray.length()) {
                val cat = catalogArray.getJSONObject(i)
                val measuredId = cat.optString("measured_model_id")
                if (measuredId.isNotBlank() && seenModelIds.contains(measuredId)) {
                    continue // Measured model takes precedence
                }

                val sizeMb = cat.optDouble("size_mb", 1000.0)
                val paramsB = cat.optDouble("params_b", 1.0)
                val quant = cat.optString("quant", "Q4_K_M")
                val qualityScore = if (cat.has("quality_score") && !cat.isNull("quality_score")) cat.optDouble("quality_score") else null

                val est = Estimator.estimateModelMetrics(
                    sizeMb = sizeMb,
                    paramsB = paramsB,
                    quant = quant,
                    qualityScore = qualityScore,
                    calibration = calibration,
                    promptTokens = promptTokens,
                    genTokens = genTokens
                )

                val sha256Val = if (cat.isNull("sha256")) null else cat.optString("sha256").takeIf { it.isNotBlank() }

                candidates.add(
                    ScoreCandidate(
                        modelId = cat.optString("id"),
                        quant = quant,
                        source = "estimated",
                        confidence = ConfidenceLevel.ESTIMATED,
                        decodeTps = est.decodeTps,
                        prefillTps = est.prefillTps,
                        ttftMs = est.ttftMs,
                        peakRamMb = est.peakRamMb,
                        qualityScore = est.qualityScore,
                        downloadUrl = cat.optString("download_url"),
                        sizeMb = sizeMb,
                        sha256 = sha256Val
                    )
                )
            }
        }

        // 4. Score and Filter
        val scoreCtx = ScoreContext(
            profile = profile,
            totalRamMb = device.totalRamMb,
            availRamMb = device.availRamMb,
            promptTokens = promptTokens,
            genTokens = genTokens
        )

        val passedItems = mutableListOf<ScoredCandidateResult>()
        val rejectedItems = mutableListOf<RejectedModel>()

        for (cand in candidates) {
            val res = Scorer.scoreAndFilterCandidate(cand, scoreCtx)
            if (res.passed) {
                passedItems.add(ScoredCandidateResult(cand, res.score, res.tSeconds, res.reasons))
            } else if (rejectedItems.size < 3) {
                rejectedItems.add(
                    RejectedModel(
                        modelId = cand.modelId,
                        quant = cand.quant,
                        reason = res.rejectReason ?: "Did not meet SLA criteria"
                    )
                )
            }
        }

        // Sort descending by score, tie-break by confidence then lower RAM
        passedItems.sortWith(
            compareByDescending<ScoredCandidateResult> { it.score }
                .thenByDescending { it.candidate.confidence.ordinal }
                .thenBy { it.candidate.peakRamMb }
        )

        val topRecommendations = passedItems.take(topN).mapIndexed { idx, item ->
            RecommendedModel(
                rank = idx + 1,
                modelId = item.candidate.modelId,
                quant = item.candidate.quant,
                source = item.candidate.source,
                confidence = item.candidate.confidence,
                score = item.score,
                expected = ExpectedPerformance(
                    decodeTps = item.candidate.decodeTps,
                    prefillTps = item.candidate.prefillTps,
                    ttftMs = item.candidate.ttftMs,
                    tSeconds = item.tSeconds,
                    peakRamMb = item.candidate.peakRamMb
                ),
                download = DownloadInfo(
                    url = item.candidate.downloadUrl,
                    sizeMb = item.candidate.sizeMb,
                    sha256 = item.candidate.sha256
                ),
                reasons = item.reasons
            )
        }

        return Recommendation(
            formulaVersion = formulaVersion,
            source = RecommendationSource.SNAPSHOT,
            scope = ScopeInfo(match = matchScope, scopeKey = scopeKey, nInstalls = nInstalls),
            items = topRecommendations,
            rejected = rejectedItems
        )
    }

    private fun parseCalib(obj: JSONObject): CalibrationParameters {
        return CalibrationParameters(
            decodeAS = obj.optDouble("decode_a_s", 0.022),
            decodeBGbps = obj.optDouble("decode_b_gbps", 9.9),
            prefillK = obj.optDouble("prefill_k", 29.0),
            ramOverheadMb = obj.optDouble("ram_overhead_mb", 160.0)
        )
    }

    private data class ScoredCandidateResult(
        val candidate: ScoreCandidate,
        val score: Double,
        val tSeconds: Double,
        val reasons: List<String>
    )
}
