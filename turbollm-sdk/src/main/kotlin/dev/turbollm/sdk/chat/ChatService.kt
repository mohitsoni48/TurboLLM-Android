package dev.turbollm.sdk.chat

import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.ChatChoice
import dev.turbollm.sdk.api.ChatCompletion
import dev.turbollm.sdk.api.ChatCompletionChunk
import dev.turbollm.sdk.api.ChatCompletionMessage
import dev.turbollm.sdk.api.ChatCompletionRequest
import dev.turbollm.sdk.api.Timings
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.api.Usage
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineHost
import dev.turbollm.sdk.internal.chatCompletionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

private const val MILLIS_PER_SECOND = 1_000.0

/**
 * Runs OpenAI chat requests on the loaded engine (architecture §1.3 flow A): validate, resolve the model, wait
 * at the [GenerationGate], generate, cut the content at the first stop sequence, and report usage. Cancelling
 * the collector cancels the engine's generation and releases the gate.
 */
internal class ChatService(
    private val resolver: ModelResolver,
    private val gate: GenerationGate,
    private val host: EngineHost,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    fun stream(request: ChatCompletionRequest): Flow<ChatCompletionChunk> = flow {
        val includeUsage = request.streamOptions?.includeUsage == true
        val assembler = ChunkAssembler(chatCompletionId(), epochSeconds(), request.model, includeUsage)
        reply(request).collect { part -> assembler.chunksFor(part).forEach { emit(it) } }
    }

    suspend fun complete(request: ChatCompletionRequest): ChatCompletion {
        val collected = CollectedReply()
        reply(request).collect(collected::add)
        return collected.toCompletion(chatCompletionId(), epochSeconds(), request.model)
    }

    private fun reply(request: ChatCompletionRequest): Flow<ReplyPart> = flow {
        val valid = request.validated()
        resolver.resolve(request.model)
        gate.withPermit {
            host.withLoaded { loaded ->
                emit(ReplyPart.Started)
                emitAll(loaded.engine.generate(valid.messages, valid.params).toReplyParts(valid.stops))
            }
        }
    }
}

/** One reply as the chat layer sees it: content already cut at a stop, and one [End] with its usage. */
internal sealed interface ReplyPart {
    data object Started : ReplyPart
    data class Content(val text: String) : ReplyPart
    data class Reasoning(val text: String) : ReplyPart
    data class End(val reason: FinishReason, val usage: Usage, val timings: Timings?) : ReplyPart
}

/**
 * Stops apply to content only, never to reasoning (llama-server behaviour). A matched stop ends the flow early,
 * which cancels the engine's generation; after `Done` the engine is left to finish on its own, so a completed
 * generation is never aborted. Engine errors other than the SDK's own and cancellation become
 * [TurboLLMException.EngineFailure].
 */
private fun Flow<EngineEvent>.toReplyParts(stops: List<String>): Flow<ReplyPart> {
    val reply = StoppableReply(StopMatcher(stops))
    return transformWhile { event -> reply.continueAfter(event, this) }
        .catch { failure -> throw failure.asEngineFailure() }
}

private class StoppableReply(private val matcher: StopMatcher) {
    private var generatedPieces = 0

    suspend fun continueAfter(event: EngineEvent, out: FlowCollector<ReplyPart>): Boolean = when (event) {
        is EngineEvent.Reasoning -> {
            generatedPieces++
            out.emit(ReplyPart.Reasoning(event.text))
            true
        }
        is EngineEvent.Text -> {
            generatedPieces++
            continueAfterContent(event.text, out)
        }
        is EngineEvent.Done -> {
            out.emitContent(matcher.flush())
            out.emit(event.toEnd())
            true
        }
    }

    private suspend fun continueAfterContent(text: String, out: FlowCollector<ReplyPart>): Boolean {
        val outcome = matcher.feed(text)
        out.emitContent(outcome.text)
        if (outcome.matched) out.emit(endAtStop())
        return !outcome.matched
    }

    /** The engine is cancelled before it reports its counts; the pieces seen so far stand in for them. */
    private fun endAtStop() = ReplyPart.End(
        reason = FinishReason.STOP,
        usage = Usage(promptTokens = 0, completionTokens = generatedPieces, totalTokens = generatedPieces),
        timings = null,
    )

    private suspend fun FlowCollector<ReplyPart>.emitContent(text: String) {
        if (text.isNotEmpty()) emit(ReplyPart.Content(text))
    }
}

private fun EngineEvent.Done.toEnd() = ReplyPart.End(
    reason = reason,
    usage = Usage(promptTokens, completionTokens, promptTokens + completionTokens),
    timings = Timings(promptTokens, promptMs, completionTokens, genMs, tokensPerSecond()),
)

private fun EngineEvent.Done.tokensPerSecond(): Double =
    if (genMs > 0) completionTokens * MILLIS_PER_SECOND / genMs else 0.0

private fun Throwable.asEngineFailure(): Throwable = when (this) {
    is CancellationException, is TurboLLMException -> this
    else -> TurboLLMException.EngineFailure(message ?: "The engine failed while generating.", this)
}

private class CollectedReply {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private var end: ReplyPart.End? = null

    fun add(part: ReplyPart) {
        when (part) {
            ReplyPart.Started -> Unit
            is ReplyPart.Content -> content.append(part.text)
            is ReplyPart.Reasoning -> reasoning.append(part.text)
            is ReplyPart.End -> end = part
        }
    }

    fun toCompletion(id: String, created: Long, model: String): ChatCompletion {
        val finished = end ?: throw TurboLLMException.EngineFailure("The engine ended the reply without finishing it.")
        val message = ChatCompletionMessage(content.toString(), reasoning.toString().ifEmpty { null })
        return ChatCompletion(
            id = id,
            created = created,
            model = model,
            choices = listOf(ChatChoice(index = 0, message = message, finishReason = finished.reason)),
            usage = finished.usage,
            timings = finished.timings,
        )
    }
}
