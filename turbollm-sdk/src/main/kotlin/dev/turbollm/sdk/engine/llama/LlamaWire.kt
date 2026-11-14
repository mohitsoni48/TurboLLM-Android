package dev.turbollm.sdk.engine.llama

import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.internal.sdkJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What `LlamaNative.prepare` takes: the chat, the thinking switch and the JSON-encoded template kwargs. */
@Serializable
internal data class PromptRequest(
    val messages: List<ChatMessage>,
    @SerialName("enable_thinking") val enableThinking: Boolean,
    @SerialName("chat_template_kwargs") val templateKwargs: Map<String, String>,
) {
    companion object {
        fun encode(messages: List<ChatMessage>, params: GenerationParams): String =
            sdkJson.encodeToString(serializer(), PromptRequest(messages, params.enableThinking, params.templateKwargs))
    }
}

/** `LlamaNative.modelInfo`; a trained context of 0 means the model does not say. */
@Serializable
internal data class NativeModelInfo(
    @SerialName("n_ctx") val contextSize: Int,
    @SerialName("n_ctx_train") val trainedContext: Int,
) {
    val knownTrainedContext: Int? get() = trainedContext.takeIf { it > 0 }

    companion object {
        fun from(json: String): NativeModelInfo = sdkJson.decodeFromString(serializer(), json)
    }
}

/** `LlamaNative.parseDelta`: what the reply added since the previous parse. */
@Serializable
internal data class ReplyDelta(val content: String = "", val reasoning: String = "")

/** `LlamaNative.stats` for the current reply. */
@Serializable
internal data class ReplyStats(
    @SerialName("prompt_n") val promptTokens: Int,
    @SerialName("predicted_n") val completionTokens: Int,
    @SerialName("prompt_ms") val promptMs: Double,
    @SerialName("predicted_ms") val genMs: Double,
    @SerialName("context_full") val contextFull: Boolean,
)
