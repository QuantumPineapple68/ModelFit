package dev.modelfit.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Snapshot of physical hardware capabilities inspected at runtime.
 */
data class DeviceInfo(
    val soc: String,
    val socNormalized: String,
    val manufacturer: String,
    val model: String,
    val totalRamMb: Int,
    val availRamMb: Int,
    val cpuCores: Int,
    val apiLevel: Int,
    val abi: String
)

/**
 * Lightweight hardware probe that inspects device memory, chipset, and CPU topology.
 */
object DeviceProbe {
    /**
     * Inspects current device hardware and live memory availability.
     */
    fun probe(context: Context): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)

        val totalRamMb = (mi.totalMem / (1024 * 1024)).toInt()
        val availRamMb = (mi.availMem / (1024 * 1024)).toInt()

        val rawSoc = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Build.SOC_MODEL.isNullOrBlank() -> Build.SOC_MODEL
            !Build.HARDWARE.isNullOrBlank() && Build.HARDWARE != "unknown" -> Build.HARDWARE
            else -> Build.BOARD
        }

        val socNormalized = SocNormalizer.normalizeSoc(rawSoc)
        val cores = Runtime.getRuntime().availableProcessors()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        return DeviceInfo(
            soc = rawSoc,
            socNormalized = socNormalized,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            totalRamMb = totalRamMb,
            availRamMb = availRamMb,
            cpuCores = cores,
            apiLevel = Build.VERSION.SDK_INT,
            abi = abi
        )
    }
}
