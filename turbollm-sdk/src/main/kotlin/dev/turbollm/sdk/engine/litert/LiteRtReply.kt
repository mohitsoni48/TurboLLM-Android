package dev.turbollm.sdk.engine.litert

import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.internal.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * One reply in its own conversation, on the engine thread (architecture §2.7). A chunk's thinking becomes
 * [EngineEvent.Reasoning] and its answer text [EngineEvent.Text]. Usage is the conversation's token-count delta
 * (thinking included), timings are wall-clock, and the conversation is always closed: after a cancel, first
 * stopping the process.
 */
internal class LiteRtReply(private val model: LiteRtModel, private val clock: Clock) {

    fun events(prompt: LiteRtPrompt): Flow<EngineEvent> = flow {
        val conversation = model.newConversation(prompt)
        var answered = false
        try {
            emit(stream(conversation, prompt))
            answered = true
        } catch (failure: Exception) {
            throw asReplyFailure(failure)
        } finally {
            if (answered) conversation.close() else abandon(conversation)
        }
    }

    private suspend fun FlowCollector<EngineEvent>.stream(
        conversation: LiteRtConversation,
        prompt: LiteRtPrompt,
    ): EngineEvent.Done {
        val promptTokens = conversation.tokenCount()
        val sent = clock.nowMillis()
        var firstChunkAt: Long? = null
        conversation.reply(prompt.userTurn).collect { chunk ->
            if (firstChunkAt == null) firstChunkAt = reportPrefill(promptTokens, sent)
            emitParts(chunk)
        }
        val ended = clock.nowMillis()
        val completionTokens = conversation.tokenCount() - promptTokens
        val prefilled = firstChunkAt ?: ended
        return EngineEvent.Done(
            reason = finishReason(completionTokens, prompt.maxOutputTokens),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            promptMs = (prefilled - sent).toDouble(),
            genMs = (ended - prefilled).toDouble(),
        )
    }

    /** LiteRT does not say when its prefill ends; the first chunk arriving is the earliest sign of it. */
    private suspend fun FlowCollector<EngineEvent>.reportPrefill(promptTokens: Int, sent: Long): Long {
        val firstChunkAt = clock.nowMillis()
        emit(EngineEvent.Prefilled(promptTokens, (firstChunkAt - sent).toDouble()))
        return firstChunkAt
    }

    /** Thinking first, as the model produced it before the answer; empty parts are not emitted. */
    private suspend fun FlowCollector<EngineEvent>.emitParts(chunk: LiteRtChunk) {
        if (chunk.thought.isNotEmpty()) emit(EngineEvent.Reasoning(chunk.thought))
        if (chunk.text.isNotEmpty()) emit(EngineEvent.Text(chunk.text))
    }

    /** LiteRT says nothing about why a reply ended; one that used its whole budget was cut short. */
    private fun finishReason(completionTokens: Int, maxOutputTokens: Int?): FinishReason =
        if (maxOutputTokens != null && completionTokens >= maxOutputTokens) FinishReason.LENGTH else FinishReason.STOP

    /** Once the collector has cancelled, whatever LiteRT throws is that cancel showing up, not a failure. */
    private suspend fun asReplyFailure(failure: Exception): Exception = when {
        !currentCoroutineContext().isActive -> CancellationException("The reply was cancelled.").apply {
            initCause(failure)
        }
        failure is TurboLLMException || failure is CancellationException -> failure
        else -> TurboLLMException.EngineFailure("LiteRT-LM failed while generating: ${failure.message}", failure)
    }

    /**
     * Stops and closes a conversation whose reply did not finish. A failure here would only hide the reason
     * the reply ended (a cancel, or the error already being thrown), so it is deliberately not reported.
     */
    private fun abandon(conversation: LiteRtConversation) {
        runCatching { conversation.cancel() }
        runCatching { conversation.close() }
    }
}
