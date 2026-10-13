package dev.modelfit

import dev.modelfit.ConfidenceLevel
import dev.modelfit.Profile
import dev.modelfit.core.ScoreCandidate
import dev.modelfit.core.ScoreContext
import dev.modelfit.core.Scorer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorerTest {

    private val validCandidate = ScoreCandidate(
        modelId = "llama-3.2-1b-instruct",
        quant = "Q4_K_M",
        source = "measured",
        confidence = ConfidenceLevel.MEASURED_EXACT,
        decodeTps = 11.34,
        prefillTps = 25.81,
        ttftMs = 6600.0,
        peakRamMb = 848.0,
        qualityScore = 0.58,
        downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF",
        sizeMb = 770.0,
        sha256 = "6f85a640"
    )

    private val defaultContext = ScoreContext(
        profile = Profile.Balanced,
        totalRamMb = 7435, // 8GB phone
        availRamMb = 3840
    )

    @Test
    fun `test candidate passes balanced profile`() {
        val result = Scorer.scoreAndFilterCandidate(validCandidate, defaultContext)
        assertTrue("Should pass all hard filters", result.passed)
        assertTrue("Score should be between 0 and 1", result.score in 0.1..1.0)
        assertTrue("Reasons should not be empty", result.reasons.isNotEmpty())
    }

    @Test
    fun `test hard filter rejects model exceeding RAM budget`() {
        val oversizedCandidate = validCandidate.copy(peakRamMb = 5000.0)
        val result = Scorer.scoreAndFilterCandidate(oversizedCandidate, defaultContext)
        assertFalse("Oversized model should fail", result.passed)
        assertTrue(
            "Reject reason should cite RAM",
            result.rejectReason?.contains("Exceeds RAM budget") == true
        )
    }

    @Test
    fun `test hard filter rejects model below minimum decode speed`() {
        // Balanced profile requires min 5 tok/s
        val slowCandidate = validCandidate.copy(decodeTps = 3.2)
        val result = Scorer.scoreAndFilterCandidate(slowCandidate, defaultContext)
        assertFalse("Slow model should fail", result.passed)
        assertTrue(
            "Reject reason should cite decode speed",
            result.rejectReason?.contains("Decode speed too slow") == true
        )
    }

    @Test
    fun `test hard filter rejects model exceeding max SLA latency`() {
        // Balanced profile requires reply in <= 30s
        // If decode is 4.0 and prefill is very slow, latency exceeds 30s
        val highLatencyCandidate = validCandidate.copy(
            decodeTps = 5.1, // passes min decode speed
            prefillTps = 2.0 // TTFT = 170 / 2 = 85s -> exceeds 30s max
        )
        val result = Scorer.scoreAndFilterCandidate(highLatencyCandidate, defaultContext)
        assertFalse("High latency candidate should fail", result.passed)
        assertTrue(
            "Reject reason should cite latency",
            result.rejectReason?.contains("limit") == true
        )
    }
}
