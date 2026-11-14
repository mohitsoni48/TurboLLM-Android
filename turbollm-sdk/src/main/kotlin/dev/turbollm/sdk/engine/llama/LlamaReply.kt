package dev.turbollm.sdk.engine.llama

import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.internal.sdkJson
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

private const val DEFAULT_TEMPERATURE = 0.8f
private const val DEFAULT_TOP_P = 0.95f
private const val DEFAULT_TOP_K = 40
private const val RANDOM_SEED = -1L

/**
 * One reply from a loaded session: prefill, sample token by token, then report the native stats.
 * Runs entirely on the engine thread, and is used for a single generation.
 */
internal class LlamaReply(private val llama: LlamaBinding, private val session: Long) {

    private val characters = Utf8StreamDecoder()
    private val abortRequested = AtomicBoolean(false)
    private var sampledTokens = 0

    fun events(messages: List<ChatMessage>, params: GenerationParams): Flow<EngineEvent> = flow {
        prefill(messages, params)
        emit(prefilled())
        startSampling(params)
        val reason = streamTokens(params.maxTokens)
        emitDelta(parse(isPartial = false))
        emit(doneWith(reason))
    }

    /**
     * Stops a running decode; called from the cancelling thread. The engine thread can see the aborted call
     * return before its own coroutine is cancelled, so what fails after this is reported as the cancel it is.
     */
    fun abort() {
        abortRequested.set(true)
        llama.requestAbort(session)
    }

    private suspend fun prefill(messages: List<ChatMessage>, params: GenerationParams) {
        llama.clearAbort(session)
        val outcome = llama.prepare(session, PromptRequest.encode(messages, params))
        if (outcome < 0) throw prefillFailure(outcome)
    }

    private fun prefillFailure(sentinel: Int): Exception = when (sentinel) {
        LlamaNative.CONTEXT_LENGTH_EXCEEDED -> TurboLLMException.ContextLengthExceeded(llama.lastError())
        LlamaNative.TEMPLATE_UNSUPPORTED -> TurboLLMException.TemplateUnsupported()
        else -> unlessAborted("llama.cpp could not process the prompt: ${llama.lastError()}")
    }

    private fun startSampling(params: GenerationParams) {
        val status = llama.startSampling(
            session,
            params.temperature ?: DEFAULT_TEMPERATURE,
            params.topP ?: DEFAULT_TOP_P,
            params.topK ?: DEFAULT_TOP_K,
            params.seed ?: RANDOM_SEED,
        )
        if (status != 0) {
            throw TurboLLMException.EngineFailure("llama.cpp could not start sampling: ${llama.lastError()}")
        }
    }

    private suspend fun FlowCollector<EngineEvent>.streamTokens(maxTokens: Int?): FinishReason {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (maxTokens != null && sampledTokens >= maxTokens) return FinishReason.LENGTH
            val token = llama.nextToken(session) ?: return endOfReply()
            sampledTokens++
            if (completesACharacter(token)) emitDelta(parse(isPartial = true))
        }
    }

    /**
     * The native parser frames UTF-8 itself; the decoder only tells whether this token finished a character,
     * so a token carrying half of one is not worth a parse.
     */
    private fun completesACharacter(token: ByteArray): Boolean = characters.decode(token).isNotEmpty()

    private fun endOfReply(): FinishReason {
        val failure = llama.lastError()
        if (failure.isNotEmpty()) throw unlessAborted("llama.cpp failed while generating: $failure")
        return if (stats().contextFull) FinishReason.LENGTH else FinishReason.STOP
    }

    private fun unlessAborted(failure: String): Exception =
        if (abortRequested.get()) CancellationException("The reply was cancelled.")
        else TurboLLMException.EngineFailure(failure)

    private suspend fun FlowCollector<EngineEvent>.emitDelta(delta: ReplyDelta) {
        if (delta.reasoning.isNotEmpty()) emit(EngineEvent.Reasoning(delta.reasoning))
        if (delta.content.isNotEmpty()) emit(EngineEvent.Text(delta.content))
    }

    private fun parse(isPartial: Boolean): ReplyDelta =
        sdkJson.decodeFromString(nativeJson(llama.parseDelta(session, isPartial), "read the reply"))

    private fun prefilled(): EngineEvent.Prefilled = stats().let { EngineEvent.Prefilled(it.promptTokens, it.promptMs) }

    private fun doneWith(reason: FinishReason): EngineEvent.Done {
        val stats = stats()
        return EngineEvent.Done(reason, stats.promptTokens, stats.completionTokens, stats.promptMs, stats.genMs)
    }

    private fun stats(): ReplyStats = sdkJson.decodeFromString(nativeJson(llama.stats(session), "read its stats"))

    private fun nativeJson(json: String, attempt: String): String {
        if (json.isEmpty()) throw TurboLLMException.EngineFailure("llama.cpp could not $attempt: ${llama.lastError()}")
        return json
    }
}
