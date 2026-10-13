package dev.modelfit.device

/**
 * Normalizes device SoC identifiers into canonical database keys and categorizes RAM tiers.
 * Exact 1:1 Kotlin port of the Cloudflare Worker normalizer in lib/soc.ts.
 */
object SocNormalizer {
    private val qcomRegex = Regex("(sm\\d{3,5}|sdm\\d{3,5}|msm\\d{3,5})")
    private val mtkRegex = Regex("(mt\\d{4})")
    private val exynosRegex = Regex("(s5e\\d{4}|exynos\\d{3,4})")
    private val tensorRegex = Regex("(gs\\d{3}|tensor(_g\\d)?)")
    private val sanitizeRegex = Regex("[^a-z0-9_-]")

    /**
     * Normalizes raw SoC strings from Android Build APIs, /proc/cpuinfo, or sysfs into a canonical key.
     */
    fun normalizeSoc(s: String?): String {
        if (s.isNullOrBlank()) return "unknown"

        var raw = s.lowercase().trim()
        if (raw.contains(':')) {
            raw = raw.substringAfterLast(':').trim()
        }

        // Clean out non-alphanumeric except hyphen/underscore
        raw = raw.replace(sanitizeRegex, "")

        qcomRegex.find(raw)?.let { return it.groupValues[1] }
        mtkRegex.find(raw)?.let { return it.groupValues[1] }
        exynosRegex.find(raw)?.let { return it.groupValues[1] }
        tensorRegex.find(raw)?.let { return it.groupValues[1] }

        if (raw.isEmpty()) return "unknown"
        return raw
    }

    /**
     * Maps total physical memory in megabytes to standard RAM tier brackets (4, 6, 8, 12, 16 GB).
     * Accounts for kernel, GPU, and modem hardware reservations.
     */
    fun ramTier(totalRamMb: Int): Int {
        return when {
            totalRamMb < 5000 -> 4
            totalRamMb < 7000 -> 6
            totalRamMb < 10000 -> 8
            totalRamMb < 14000 -> 12
            else -> 16
        }
    }
}
