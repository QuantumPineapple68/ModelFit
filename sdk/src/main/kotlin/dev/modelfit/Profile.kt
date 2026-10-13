package dev.modelfit

/**
 * Weights assigned to latency, model intelligence, and RAM footprint.
 */
data class Weights(
    val speed: Float,
    val quality: Float,
    val memory: Float,
    val battery: Float = 0f
)

/**
 * Scoring profiles defining user experience priorities and SLA constraints.
 */
sealed class Profile(
    val name: String,
    val weights: Weights,
    val tRefS: Float,
    val minDecodeTps: Float,
    val maxTs: Float,
    val ramBudgetFraction: Float
) {
    /**
     * Balanced profile: 40% speed, 40% quality, 20% memory. Max 30s latency, min 5 tok/s.
     */
    data object Balanced : Profile(
        name = "balanced",
        weights = Weights(speed = 0.40f, quality = 0.40f, memory = 0.20f),
        tRefS = 10f,
        minDecodeTps = 5f,
        maxTs = 30f,
        ramBudgetFraction = 0.50f
    )

    /**
     * Speed-first profile: 60% speed, 25% quality, 15% memory. Max 20s latency, min 8 tok/s.
     */
    data object Speed : Profile(
        name = "speed",
        weights = Weights(speed = 0.60f, quality = 0.25f, memory = 0.15f),
        tRefS = 6f,
        minDecodeTps = 8f,
        maxTs = 20f,
        ramBudgetFraction = 0.50f
    )

    /**
     * Quality-first profile: 20% speed, 65% quality, 15% memory. Max 60s latency, min 3 tok/s.
     */
    data object Quality : Profile(
        name = "quality",
        weights = Weights(speed = 0.20f, quality = 0.65f, memory = 0.15f),
        tRefS = 20f,
        minDecodeTps = 3f,
        maxTs = 60f,
        ramBudgetFraction = 0.50f
    )

    /**
     * Low memory profile: 25% speed, 25% quality, 50% memory. Constrained to 35% RAM budget.
     */
    data object LowMemory : Profile(
        name = "lowmem",
        weights = Weights(speed = 0.25f, quality = 0.25f, memory = 0.50f),
        tRefS = 10f,
        minDecodeTps = 5f,
        maxTs = 30f,
        ramBudgetFraction = 0.35f
    )

    /**
     * Custom developer-specified profile with user-defined weights and thresholds.
     */
    class Custom(
        weights: Weights,
        tRefS: Float = 10f,
        minDecodeTps: Float = 5f,
        maxTs: Float = 30f,
        ramBudgetFraction: Float = 0.50f
    ) : Profile(
        name = "custom",
        weights = weights,
        tRefS = tRefS,
        minDecodeTps = minDecodeTps,
        maxTs = maxTs,
        ramBudgetFraction = ramBudgetFraction
    )
}
