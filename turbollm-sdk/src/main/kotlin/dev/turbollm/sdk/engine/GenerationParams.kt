package dev.turbollm.sdk.engine

/**
 * [maxTokens] is passed down so the engine stops exactly: llama counts sampled tokens, LiteRT-LM its output.
 * [enableThinking] and [templateKwargs] feed llama.cpp's chat template; [templateKwargs] values are JSON-encoded,
 * the form `common_chat_templates_inputs.chat_template_kwargs` takes. LiteRT-LM ignores both in M1.
 */
internal data class GenerationParams(
    val maxTokens: Int?,
    val temperature: Float?,
    val topP: Float?,
    val topK: Int?,
    val seed: Long?,
    val enableThinking: Boolean = true,
    val templateKwargs: Map<String, String> = emptyMap(),
)
