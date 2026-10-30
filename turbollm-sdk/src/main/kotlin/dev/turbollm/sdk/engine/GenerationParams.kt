package dev.turbollm.sdk.engine

/** [maxTokens] is passed down so the engine stops exactly: llama counts sampled tokens, LiteRT-LM its output. */
internal data class GenerationParams(
    val maxTokens: Int?,
    val temperature: Float?,
    val topP: Float?,
    val topK: Int?,
    val seed: Long?,
)
