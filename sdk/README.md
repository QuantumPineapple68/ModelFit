# ModelFit Client SDK (`modelfit-sdk`)

A lightweight, zero-overhead Android library that delivers instant, zero-crash Large Language Model (LLM) recommendations tailored to a device's physical hardware constraints and memory bus bandwidth.

## Features

- **Zero-Crash Memory Safety**: Calculates real physical memory headroom to prevent Android kernel Low Memory Killer (LMK) `SIGKILL` terminations.
- **First-Principles Physics**: Uses empirical DRAM memory bandwidth ($B$) and scheduling latency ($a$) laws ($1/\text{decode} = a + \text{size}/B$) to accurately predict model token generation speed (~83% accuracy for untested models).
- **4-Stage Fallback Hierarchy**:
  1. Live Serverless Edge API (`POST /v1/recommend`)
  2. Local Persistent Cache (7-day TTL)
  3. Local Offline Physics Snapshot (`snapshot.json` bundled in assets)
  4. Hardcoded Verified Safe Baseline Model
- **Privacy First**: Captures zero Personally Identifiable Information (PII) — no user accounts, IP addresses, GAID, or device serial numbers.
- **Zero Runtime Bloat**: ~93 KB AAR with zero heavy third-party dependencies.

---

## Quickstart

### 1. Initialize ModelFit

```kotlin
import dev.modelfit.ModelFit
import dev.modelfit.ModelFitConfig

val modelFit = ModelFit.create(
    context = applicationContext,
    config = ModelFitConfig(
        baseUrl = "https://benchdb.benchdb.workers.dev", // Default edge backend
        apiKey = null,                                   // Optional API key
        cacheTtlDays = 7,                                // Cache TTL
        timeoutMs = 5000                                 // 5-second network timeout
    )
)
```

### 2. Request a Recommendation

```kotlin
import dev.modelfit.Profile
import dev.modelfit.Task
import dev.modelfit.RecommendationSource

// Asynchronously query the best model for this phone
val recommendation = modelFit.recommend(
    profile = Profile.Balanced,  // Balanced | Speed | Quality | LowMemory | Custom
    task = Task.Chat,            // Chat | Summarization | Code | Instruction
    topN = 3,
    includeEstimated = true
)

println("Recommendation source: ${recommendation.source}") // NETWORK, CACHE, SNAPSHOT, FALLBACK
println("Hardware scope: ${recommendation.scope.match} (${recommendation.scope.scopeKey})")

val bestModel = recommendation.items.firstOrNull()
if (bestModel != null) {
    println("Model ID: ${bestModel.modelId}")
    println("Quantization: ${bestModel.quant}")
    println("Expected Decode Speed: ${bestModel.expected.decodeTps} tokens/s")
    println("Expected RAM Usage: ${bestModel.expected.peakRamMb} MB")
    println("Download URL: ${bestModel.download.url}")
    println("SHA-256 Hash: ${bestModel.download.sha256}")
    println("Decision Reasons: ${bestModel.reasons.joinToString("; ")}")
}
```

### 3. Custom Developer Profiles

Developers can configure custom multi-objective weights and latency thresholds:

```kotlin
val customProfile = Profile.Custom(
    weights = Weights(speed = 0.5f, quality = 0.3f, memory = 0.2f),
    minDecodeTps = 6f,          // Reject any model below 6 tok/s
    maxTs = 25f,                // Reject any model taking > 25s for reply
    ramBudgetFraction = 0.45f   // Reserve at most 45% of total system RAM
)

val recommendation = modelFit.recommend(profile = customProfile)
```

---

## Offline Behavior

If the user has airplane mode enabled or is disconnected from the network, `recommend()`:
1. Immediately checks if an unexpired cached recommendation exists for this device.
2. If none exists, evaluates the local bundled `snapshot.json` physics calibrations directly on-device using `Scorer` and `Estimator`.
3. If no snapshot is bundled, returns the verified lightweight default `qwen2.5-0.5b-instruct` model.

**`recommend()` is guaranteed to never throw an exception or crash your app due to network failures.**
