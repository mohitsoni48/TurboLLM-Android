package dev.turbollm.sdk.api

import dev.turbollm.sdk.FinishReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null,
    val timings: Timings? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    @SerialName("finish_reason") val finishReason: FinishReason? = null,
)

@Serializable
data class ChunkDelta(
    val role: ChatRole? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)
