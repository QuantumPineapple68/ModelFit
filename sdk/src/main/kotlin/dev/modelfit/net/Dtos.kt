package dev.modelfit.net

import dev.modelfit.ConfidenceLevel
import dev.modelfit.DownloadInfo
import dev.modelfit.ExpectedPerformance
import dev.modelfit.Profile
import dev.modelfit.Recommendation
import dev.modelfit.RecommendationSource
import dev.modelfit.RecommendedModel
import dev.modelfit.RejectedModel
import dev.modelfit.ScopeInfo
import dev.modelfit.device.DeviceInfo
import org.json.JSONArray
import org.json.JSONObject

object Dtos {
    fun buildRecommendRequestBody(
        device: DeviceInfo,
        profile: Profile,
        promptTokens: Int,
        genTokens: Int,
        topN: Int,
        includeEstimated: Boolean
    ): String {
        val root = JSONObject()

        val devObj = JSONObject().apply {
            put("soc", device.socNormalized)
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("total_ram_mb", device.totalRamMb)
            put("avail_ram_mb", device.availRamMb)
            put("cpu_cores", device.cpuCores)
            put("api_level", device.apiLevel)
        }
        root.put("device", devObj)
        root.put("profile", profile.name)

        if (profile is Profile.Custom) {
            val customObj = JSONObject().apply {
                put("w_speed", profile.weights.speed)
                put("w_quality", profile.weights.quality)
                put("w_memory", profile.weights.memory)
                put("min_decode_tps", profile.minDecodeTps)
                put("max_t_s", profile.maxTs)
            }
            root.put("custom", customObj)
        }

        root.put("prompt_tokens", promptTokens)
        root.put("gen_tokens", genTokens)
        root.put("top_n", topN)
        root.put("include_estimated", includeEstimated)

        return root.toString()
    }

    fun parseRecommendationResponse(jsonString: String, source: RecommendationSource = RecommendationSource.NETWORK): Recommendation {
        val root = JSONObject(jsonString)
        val formulaVersion = root.optInt("formula_version", 1)

        val scopeObj = root.optJSONObject("scope")
        val scope = ScopeInfo(
            match = scopeObj?.optString("match") ?: "unknown",
            scopeKey = scopeObj?.optString("scope_key") ?: "unknown",
            nInstalls = if (scopeObj?.has("n_installs") == true) scopeObj.optInt("n_installs") else null
        )

        val items = mutableListOf<RecommendedModel>()
        val itemsArray = root.optJSONArray("recommendations") ?: JSONArray()
        for (i in 0 until itemsArray.length()) {
            val itemObj = itemsArray.getJSONObject(i)
            val expectedObj = itemObj.optJSONObject("expected") ?: JSONObject()
            val downloadObj = itemObj.optJSONObject("download") ?: JSONObject()

            val reasons = mutableListOf<String>()
            val reasonsArray = itemObj.optJSONArray("reasons") ?: JSONArray()
            for (r in 0 until reasonsArray.length()) {
                reasons.add(reasonsArray.getString(r))
            }

            val sha256Val = if (downloadObj.isNull("sha256")) null else downloadObj.optString("sha256").takeIf { it.isNotBlank() }

            items.add(
                RecommendedModel(
                    rank = itemObj.optInt("rank", i + 1),
                    modelId = itemObj.optString("model_id"),
                    quant = itemObj.optString("quant"),
                    source = itemObj.optString("source", "estimated"),
                    confidence = ConfidenceLevel.fromString(itemObj.optString("confidence")),
                    score = itemObj.optDouble("score", 0.0),
                    expected = ExpectedPerformance(
                        decodeTps = expectedObj.optDouble("decode_tps", 0.0),
                        prefillTps = expectedObj.optDouble("prefill_tps", 0.0),
                        ttftMs = expectedObj.optDouble("ttft_ms", 0.0),
                        tSeconds = expectedObj.optDouble("t_s", 0.0),
                        peakRamMb = expectedObj.optDouble("peak_ram_mb", 0.0)
                    ),
                    download = DownloadInfo(
                        url = downloadObj.optString("url"),
                        sizeMb = downloadObj.optDouble("size_mb", 0.0),
                        sha256 = sha256Val
                    ),
                    reasons = reasons
                )
            )
        }

        val rejected = mutableListOf<RejectedModel>()
        val rejectedArray = root.optJSONArray("rejected") ?: JSONArray()
        for (i in 0 until rejectedArray.length()) {
            val rejObj = rejectedArray.getJSONObject(i)
            rejected.add(
                RejectedModel(
                    modelId = rejObj.optString("model_id"),
                    quant = rejObj.optString("quant").takeIf { it.isNotBlank() },
                    reason = rejObj.optString("reason")
                )
            )
        }

        return Recommendation(
            formulaVersion = formulaVersion,
            source = source,
            scope = scope,
            items = items,
            rejected = rejected
        )
    }

    fun recommendationToJson(rec: Recommendation): String {
        val root = JSONObject()
        root.put("formula_version", rec.formulaVersion)

        val scopeObj = JSONObject().apply {
            put("match", rec.scope.match)
            put("scope_key", rec.scope.scopeKey)
            rec.scope.nInstalls?.let { put("n_installs", it) }
        }
        root.put("scope", scopeObj)

        val recsArray = JSONArray()
        for (item in rec.items) {
            val itemObj = JSONObject().apply {
                put("rank", item.rank)
                put("model_id", item.modelId)
                put("quant", item.quant)
                put("source", item.source)
                put("confidence", item.confidence.name.lowercase())
                put("score", item.score)

                val expObj = JSONObject().apply {
                    put("decode_tps", item.expected.decodeTps)
                    put("prefill_tps", item.expected.prefillTps)
                    put("ttft_ms", item.expected.ttftMs)
                    put("t_s", item.expected.tSeconds)
                    put("peak_ram_mb", item.expected.peakRamMb)
                }
                put("expected", expObj)

                val dlObj = JSONObject().apply {
                    put("url", item.download.url)
                    put("size_mb", item.download.sizeMb)
                    item.download.sha256?.let { put("sha256", it) }
                }
                put("download", dlObj)

                val reasonsArr = JSONArray()
                item.reasons.forEach { reasonsArr.put(it) }
                put("reasons", reasonsArr)
            }
            recsArray.put(itemObj)
        }
        root.put("recommendations", recsArray)

        val rejArray = JSONArray()
        for (rej in rec.rejected) {
            val rejObj = JSONObject().apply {
                put("model_id", rej.modelId)
                rej.quant?.let { put("quant", it) }
                put("reason", rej.reason)
            }
            rejArray.put(rejObj)
        }
        root.put("rejected", rejArray)

        return root.toString()
    }
}
