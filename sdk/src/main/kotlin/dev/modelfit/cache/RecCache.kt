package dev.modelfit.cache

import android.content.Context
import android.content.SharedPreferences
import dev.modelfit.ModelFitConfig
import dev.modelfit.Recommendation
import dev.modelfit.RecommendationSource
import dev.modelfit.net.Dtos
import java.util.concurrent.TimeUnit

class RecCache(context: Context, private val config: ModelFitConfig) {
    private val prefs: SharedPreferences = context.getSharedPreferences("modelfit_rec_cache", Context.MODE_PRIVATE)

    fun get(profileName: String, socKey: String): Recommendation? {
        val cacheKey = "rec_${profileName}_$socKey"
        val timeKey = "time_${profileName}_$socKey"

        val json = prefs.getString(cacheKey, null) ?: return null
        val timestamp = prefs.getLong(timeKey, 0L)
        val maxAgeMs = TimeUnit.DAYS.toMillis(config.cacheTtlDays.toLong())

        if (System.currentTimeMillis() - timestamp > maxAgeMs) {
            // Expired
            return null
        }

        return try {
            Dtos.parseRecommendationResponse(json, RecommendationSource.CACHE)
        } catch (_: Exception) {
            null
        }
    }

    fun put(profileName: String, socKey: String, rec: Recommendation) {
        val cacheKey = "rec_${profileName}_$socKey"
        val timeKey = "time_${profileName}_$socKey"

        try {
            val json = Dtos.recommendationToJson(rec)
            prefs.edit()
                .putString(cacheKey, json)
                .putLong(timeKey, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
