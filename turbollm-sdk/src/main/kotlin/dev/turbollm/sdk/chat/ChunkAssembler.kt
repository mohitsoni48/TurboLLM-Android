package dev.turbollm.sdk.chat

import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.ChatCompletionChunk
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.ChunkChoice
import dev.turbollm.sdk.api.ChunkDelta

/**
 * Turns one reply into OpenAI stream chunks, in order: the role, the content and reasoning deltas, a final chunk
 * with `finish_reason` and timings, then (when the client asked for usage) a chunk with no choices and the usage.
 */
internal class ChunkAssembler(
    private val id: String,
    private val created: Long,
    private val model: String,
    private val includeUsage: Boolean,
) {
    fun chunksFor(part: ReplyPart): List<ChatCompletionChunk> = when (part) {
        ReplyPart.Started -> listOf(choiceChunk(ChunkDelta(role = ChatRole.ASSISTANT)))
        is ReplyPart.Content -> listOf(choiceChunk(ChunkDelta(content = part.text)))
        is ReplyPart.Reasoning -> listOf(choiceChunk(ChunkDelta(reasoningContent = part.text)))
        is ReplyPart.End -> closingChunks(part)
    }

    private fun closingChunks(end: ReplyPart.End): List<ChatCompletionChunk> {
        val finish = choiceChunk(ChunkDelta(), end.reason).copy(timings = end.timings)
        return if (includeUsage) listOf(finish, usageChunk(end)) else listOf(finish)
    }

    private fun choiceChunk(delta: ChunkDelta, finishReason: FinishReason? = null) =
        chunk(choices = listOf(ChunkChoice(index = 0, delta = delta, finishReason = finishReason)))

    private fun usageChunk(end: ReplyPart.End) = chunk(choices = emptyList()).copy(usage = end.usage)

    private fun chunk(choices: List<ChunkChoice>) =
        ChatCompletionChunk(id = id, created = created, model = model, choices = choices)
}
