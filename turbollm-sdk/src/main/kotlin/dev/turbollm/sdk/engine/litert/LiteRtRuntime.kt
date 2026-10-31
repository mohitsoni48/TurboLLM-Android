package dev.turbollm.sdk.engine.litert

import kotlinx.coroutines.flow.Flow

/**
 * LiteRT-LM as [LiteRtLmEngine] sees it. [AarLiteRtRuntime] is the production one, over Google's AAR;
 * JVM tests pass a scripted fake. Every call is blocking and is made on the engine's own thread.
 */
internal fun interface LiteRtRuntime {
    /** Initialises the model; throws `TurboLLMException.EngineUnavailable` when LiteRT cannot run here at all. */
    fun open(config: LiteRtModelConfig): LiteRtModel
}

internal data class LiteRtModelConfig(
    val path: String,
    val backend: LiteRtBackend,
    val maxTokens: Int,
    val cacheDir: String,
)

internal sealed interface LiteRtBackend {
    data object Gpu : LiteRtBackend
    data class Cpu(val threads: Int) : LiteRtBackend
}

internal interface LiteRtModel : AutoCloseable {
    fun newConversation(prompt: LiteRtPrompt): LiteRtConversation
}

internal interface LiteRtConversation : AutoCloseable {
    /** The reply to [userTurn], as the text chunks LiteRT streams (each one new text, not the reply so far). */
    fun reply(userTurn: String): Flow<String>

    /** Tokens in the conversation's KV cache, prefill and decode together. */
    fun tokenCount(): Int

    /** Stops an in-flight reply; what the reply's flow does next is undocumented. */
    fun cancel()
}
