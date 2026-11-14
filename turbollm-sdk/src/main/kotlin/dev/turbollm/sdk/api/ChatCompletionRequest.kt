package dev.turbollm.sdk.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An OpenAI chat completion request. The refused fields (`tools` through `top_logprobs`) are captured only so
 * the request can be refused by name; they are never acted on.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @Serializable(with = StopSequencesSerializer::class) val stop: List<String>? = null,
    val seed: Long? = null,
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: JsonObject? = null,
    @SerialName("thinking_budget_tokens") val thinkingBudgetTokens: Int? = null,
    val tools: JsonElement? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val functions: JsonElement? = null,
    @SerialName("response_format") val responseFormat: JsonElement? = null,
    val n: JsonElement? = null,
    val logprobs: JsonElement? = null,
    @SerialName("top_logprobs") val topLogprobs: JsonElement? = null,
)
