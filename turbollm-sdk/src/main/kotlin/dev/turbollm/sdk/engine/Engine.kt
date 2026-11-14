package dev.turbollm.sdk.engine

import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.api.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * One inference engine holding at most one model. Stop sequences are not an engine concern: the chat layer
 * applies them to the text stream and cancels [generate]'s flow when one matches.
 */
internal interface Engine : AutoCloseable {
    val kind: EngineKind

    /** Runs on the engine's own thread; throws `TurboLLMException.ModelLoadFailed`. */
    suspend fun load(spec: LoadSpec): EngineModelInfo

    /** Cold: nothing runs until collected, and cancelling the collector aborts the generation. */
    fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<EngineEvent>

    suspend fun unload()
}
