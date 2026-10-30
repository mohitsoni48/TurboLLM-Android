package dev.turbollm.sdk.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletion(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage,
    val timings: Timings? = null,
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatCompletionMessage,
    @SerialName("finish_reason") val finishReason: String,
)

@Serializable
data class ChatCompletionMessage(
    val content: String,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val role: ChatRole = ChatRole.ASSISTANT,
)
