package dev.modelfit

/**
 * Standard on-device LLM workloads with canonical token budgets.
 *
 * @property defaultPromptTokens Canonical prompt context size.
 * @property defaultGenTokens Canonical completion length.
 */
enum class Task(val defaultPromptTokens: Int, val defaultGenTokens: Int) {
    /**
     * Interactive chat / assistant dialogue (170 prompt tokens, 100 generated tokens).
     */
    Chat(defaultPromptTokens = 170, defaultGenTokens = 100),

    /**
     * Document summarization (1024 prompt tokens, 256 generated tokens).
     */
    Summarization(defaultPromptTokens = 1024, defaultGenTokens = 256),

    /**
     * Code completion / generation (512 prompt tokens, 256 generated tokens).
     */
    Code(defaultPromptTokens = 512, defaultGenTokens = 256),

    /**
     * Single-turn instruction following (256 prompt tokens, 128 generated tokens).
     */
    Instruction(defaultPromptTokens = 256, defaultGenTokens = 128)
}
