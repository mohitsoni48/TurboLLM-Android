package dev.turbollm.sdk.engine.litert

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.api.TurboLLMException
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.google.ai.edge.litertlm.Backend as AarBackend
import com.google.ai.edge.litertlm.Engine as AarEngine

/**
 * [LiteRtRuntime] over `com.google.ai.edge:litertlm-android` 0.19.0. Every call below was checked against that
 * AAR's `classes.jar` with `javap` (N-5). Nothing here loads native code until [open] runs.
 */
internal object AarLiteRtRuntime : LiteRtRuntime {

    override fun open(config: LiteRtModelConfig): LiteRtModel = unavailableUnlessLinkable {
        File(config.cacheDir).mkdirs()
        val engine = AarEngine(
            EngineConfig(
                modelPath = config.path,
                backend = config.backend.toAar(),
                maxNumTokens = config.maxTokens,
                cacheDir = config.cacheDir,
            ),
        )
        initialised(engine)
        AarModel(engine)
    }

    private fun initialised(engine: AarEngine) {
        try {
            engine.initialize()
        } catch (failure: Exception) {
            engine.close()
            throw failure
        }
    }

    /** A JNI library that cannot load on this ABI is an engine this device does not have (ADR-239). */
    private inline fun <T> unavailableUnlessLinkable(open: () -> T): T =
        try {
            open()
        } catch (missing: LinkageError) {
            throw TurboLLMException.EngineUnavailable(
                EngineKind.LITERT_LM,
                "LiteRT-LM cannot run on this device: ${missing.message}",
            )
        }
}

private fun LiteRtBackend.toAar(): AarBackend = when (this) {
    LiteRtBackend.Gpu -> AarBackend.GPU()
    is LiteRtBackend.Cpu -> AarBackend.CPU(threadCount = threads)
}

private class AarModel(private val engine: AarEngine) : LiteRtModel {
    override fun newConversation(prompt: LiteRtPrompt): LiteRtConversation =
        AarConversation(engine.createConversation(conversationConfigOf(prompt)))

    override fun close() = engine.close()
}

/**
 * Thinking off is LiteRT-LM's own [ThinkingConfig] (QA B-38). Thinking on passes none, so the model keeps the
 * default it had before the SDK could turn thinking off.
 */
internal fun conversationConfigOf(prompt: LiteRtPrompt): ConversationConfig {
    val sampler = prompt.sampler
    return ConversationConfig(
        systemInstruction = prompt.systemInstruction?.let { Contents.of(it) },
        initialMessages = prompt.history.map(::messageOf),
        samplerConfig = SamplerConfig(sampler.topK, sampler.topP, sampler.temperature, sampler.seed),
        maxOutputToken = prompt.maxOutputTokens,
        thinkingConfig = if (prompt.enableThinking) null else ThinkingConfig(enableThinking = false),
    )
}

private fun messageOf(turn: LiteRtTurn): Message = when (turn.role) {
    LiteRtRole.USER -> Message.user(turn.text)
    LiteRtRole.MODEL -> Message.model(turn.text)
}

private class AarConversation(private val conversation: Conversation) : LiteRtConversation {
    override fun reply(userTurn: String): Flow<LiteRtChunk> =
        conversation.sendMessageAsync(userTurn).map { chunk -> chunk.toChunk() }

    override fun tokenCount(): Int = conversation.getTokenCount()

    override fun cancel() = conversation.cancelProcess()

    override fun close() = conversation.close()
}

/** LiteRT-LM puts a thinking model's thinking in this channel of each streamed message, not in its contents. */
private const val THOUGHT_CHANNEL = "thought"

/** The message's contents are answer text; its `thought` channel is thinking. Other channels are not used. */
internal fun Message.toChunk(): LiteRtChunk =
    LiteRtChunk(text = contents.toString(), thought = channels[THOUGHT_CHANNEL].orEmpty())
