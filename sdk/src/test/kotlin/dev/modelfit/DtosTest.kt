package dev.modelfit

import dev.modelfit.device.DeviceInfo
import dev.modelfit.net.Dtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtosTest {

    private val sampleJson = """
        {
          "formula_version": 1,
          "scope": {
            "match": "exact_low_n",
            "scope_key": "sm7435",
            "n_installs": 1
          },
          "recommendations": [
            {
              "rank": 1,
              "model_id": "llama-3.2-1b-instruct",
              "quant": "Q4_K_M",
              "source": "measured",
              "confidence": "measured_low_n",
              "score": 0.654,
              "expected": {
                "decode_tps": 11.34,
                "prefill_tps": 25.81,
                "ttft_ms": 6600,
                "t_s": 15.42,
                "peak_ram_mb": 848
              },
              "download": {
                "url": "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF",
                "size_mb": 770,
                "sha256": "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"
              },
              "reasons": [
                "fits in RAM (848 MB of 3072 MB budget)",
                "15.4 s for a 100-token reply"
              ]
            }
          ],
          "rejected": [
            {
              "model_id": "gemma-2-2b-it",
              "reason": "100-token reply takes 32.9 s (> 30 s limit)"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `test parse recommendation response json`() {
        val rec = Dtos.parseRecommendationResponse(sampleJson)
        assertEquals(1, rec.formulaVersion)
        assertEquals("exact_low_n", rec.scope.match)
        assertEquals("sm7435", rec.scope.scopeKey)
        assertEquals(1, rec.scope.nInstalls)

        assertEquals(1, rec.items.size)
        val item = rec.items.first()
        assertEquals(1, item.rank)
        assertEquals("llama-3.2-1b-instruct", item.modelId)
        assertEquals("Q4_K_M", item.quant)
        assertEquals(11.34, item.expected.decodeTps, 0.01)
        assertEquals(848.0, item.expected.peakRamMb, 0.01)
        assertEquals("6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", item.download.sha256)
        assertEquals(2, item.reasons.size)

        assertEquals(1, rec.rejected.size)
        assertEquals("gemma-2-2b-it", rec.rejected.first().modelId)
    }

    @Test
    fun `test build recommend request body`() {
        val device = DeviceInfo(
            soc = "SM7435",
            socNormalized = "sm7435",
            manufacturer = "realme",
            model = "RMX3987",
            totalRamMb = 7435,
            availRamMb = 3840,
            cpuCores = 8,
            apiLevel = 34,
            abi = "arm64-v8a"
        )

        val json = Dtos.buildRecommendRequestBody(
            device = device,
            profile = Profile.Balanced,
            promptTokens = 170,
            genTokens = 100,
            topN = 3,
            includeEstimated = true
        )

        assertTrue("JSON should contain soc", json.contains("\"soc\":\"sm7435\""))
        assertTrue("JSON should contain balanced", json.contains("\"profile\":\"balanced\""))
        assertTrue("JSON should contain total_ram_mb", json.contains("\"total_ram_mb\":7435"))
    }

    @Test
    fun `test serialization roundtrip`() {
        val rec = Dtos.parseRecommendationResponse(sampleJson)
        val serialized = Dtos.recommendationToJson(rec)
        val deserialized = Dtos.parseRecommendationResponse(serialized)

        assertEquals(rec.formulaVersion, deserialized.formulaVersion)
        assertEquals(rec.scope.match, deserialized.scope.match)
        assertEquals(rec.items.first().modelId, deserialized.items.first().modelId)
        assertEquals(rec.items.first().expected.decodeTps, deserialized.items.first().expected.decodeTps, 0.01)
    }

    @Test
    fun `test build ingest request body for crowdsourced telemetry`() {
        val device = DeviceInfo(
            soc = "SM7435",
            socNormalized = "sm7435",
            manufacturer = "realme",
            model = "RMX3987",
            totalRamMb = 7435,
            availRamMb = 3840,
            cpuCores = 8,
            apiLevel = 34,
            abi = "arm64-v8a"
        )

        val metrics = listOf(
            dev.modelfit.RunMetrics(
                modelId = "llama-3.2-1b-instruct",
                quant = "Q4_K_M",
                decodeTps = 11.45,
                prefillTps = 25.10,
                peakRamMb = 852.0
            )
        )

        val json = Dtos.buildIngestRequestBody("test-install-uuid", device, metrics, source = "sdk")
        assertTrue("JSON should contain install_id", json.contains("\"install_id\":\"test-install-uuid\""))
        assertTrue("JSON should contain source sdk", json.contains("\"source\":\"sdk\""))
        assertTrue("JSON should contain model_id", json.contains("\"model_id\":\"llama-3.2-1b-instruct\""))
        assertTrue("JSON should contain decode_tps", json.contains("\"decode_tps\":11.45"))
    }
}
