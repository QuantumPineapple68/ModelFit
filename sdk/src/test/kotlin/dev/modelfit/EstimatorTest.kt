package dev.modelfit

import dev.modelfit.core.CalibrationParameters
import dev.modelfit.core.Estimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatorTest {

    private val sm7435Calib = CalibrationParameters(
        decodeAS = 0.0181,
        decodeBGbps = 9.44,
        prefillK = 29.24,
        ramOverheadMb = 160.0
    )

    @Test
    fun `test estimator math matches physics formula`() {
        // 1B model: size ~ 770 MB, params ~ 1.23 B
        val metrics = Estimator.estimateModelMetrics(
            sizeMb = 770.0,
            paramsB = 1.23,
            quant = "Q4_K_M",
            qualityScore = 0.58,
            calibration = sm7435Calib,
            promptTokens = 170,
            genTokens = 100
        )

        // 1/decode = 0.0181 + 0.770 / 9.44 = 0.0181 + 0.081568 = 0.099668 s/token
        // decode_tps = 1 / 0.099668 ~= 10.03 tok/s
        assertTrue("Decode TPS should be around 10 tok/s", metrics.decodeTps in 9.5..10.5)

        // prefill_tps = 29.24 / 1.23 ~= 23.77 tok/s
        assertTrue("Prefill TPS should be around 23.8 tok/s", metrics.prefillTps in 23.0..25.0)

        // Peak RAM = 770 + 160 = 930 MB
        assertEquals(930.0, metrics.peakRamMb, 1.0)

        // Quality score matches supplied score
        assertEquals(0.58, metrics.qualityScore, 0.001)
    }

    @Test
    fun `test prior quality score calculation when none supplied`() {
        val metrics = Estimator.estimateModelMetrics(
            sizeMb = 398.0,
            paramsB = 0.5,
            quant = "Q4_K_M",
            qualityScore = null,
            calibration = sm7435Calib
        )

        // Prior quality for 0.5B should be positive and in reasonable 0.25..0.40 range
        assertTrue("Quality score should be positive", metrics.qualityScore in 0.25..0.45)
    }
}
