package dev.modelfit

import dev.modelfit.device.MemoryBandwidthProbe
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBandwidthProbeTest {

    @Test
    fun `test memory bandwidth probe executes quickly and produces positive bandwidth`() {
        val start = System.currentTimeMillis()
        val bwGbps = MemoryBandwidthProbe.measureBandwidthGbps(bufferMb = 8, iterations = 2)
        val durationMs = System.currentTimeMillis() - start

        println("Memory bandwidth measured: $bwGbps GB/s in $durationMs ms")
        assertTrue("Probe should complete in under 200ms", durationMs < 200)
        assertTrue("Bandwidth should be positive", bwGbps > 0.5)
    }
}
