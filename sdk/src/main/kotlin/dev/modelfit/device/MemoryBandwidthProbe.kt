package dev.modelfit.device

import java.nio.ByteBuffer

/**
 * Lightweight, non-intrusive memory bus bandwidth probe.
 *
 * Measures usable DRAM memory read bandwidth in GB/s without blocking the UI.
 * Typically finishes in 10 to 30 milliseconds.
 */
object MemoryBandwidthProbe {

    /**
     * Measures DRAM memory throughput by streaming a contiguous memory buffer.
     *
     * @param bufferMb Buffer size in megabytes (default: 16 MB).
     * @param iterations Number of measurement passes to filter scheduling jitter (default: 3).
     * @return Effective memory bandwidth in Gigabytes per second (GB/s).
     */
    fun measureBandwidthGbps(bufferMb: Int = 16, iterations: Int = 3): Double {
        return try {
            val sizeBytes = bufferMb * 1024 * 1024
            val buffer = ByteBuffer.allocateDirect(sizeBytes)

            // Warm up pages / touch virtual memory
            for (i in 0 until sizeBytes step 4096) {
                buffer.put(i, 1)
            }

            var minDurationNs = Long.MAX_VALUE
            val chunk = ByteArray(64 * 1024)

            repeat(iterations) {
                buffer.position(0)
                val start = System.nanoTime()
                while (buffer.remaining() >= chunk.size) {
                    buffer.get(chunk)
                }
                val duration = System.nanoTime() - start
                if (duration < minDurationNs) {
                    minDurationNs = duration
                }
            }

            val seconds = minDurationNs / 1_000_000_000.0
            val gigabytes = sizeBytes / (1000.0 * 1000.0 * 1000.0)
            if (seconds > 0) gigabytes / seconds else 0.0
        } catch (_: Throwable) {
            0.0
        }
    }
}
