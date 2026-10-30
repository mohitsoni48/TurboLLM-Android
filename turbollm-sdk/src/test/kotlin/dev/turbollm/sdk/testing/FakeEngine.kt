package dev.turbollm.sdk.testing

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.engine.Engine
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineModelInfo
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.engine.LoadSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * A scripted [Engine]: plays [script] on every generation and records what was asked of it. [history] lists
 * `load`, `cancelled`, `unload` and `close` in the order they happened.
 */
internal class FakeEngine(
    private val script: List<Step> = emptyList(),
    private val modelInfo: EngineModelInfo = DEFAULT_MODEL_INFO,
    private val loadFailure: Throwable? = null,
    override val kind: EngineKind = EngineKind.LLAMA_CPP,
    private val loadPauseMillis: Long = 0,
) : Engine {

    sealed interface Step {
        data class Emit(val event: EngineEvent) : Step
        data class Pause(val millis: Long) : Step
    }

    private val recordedLoads = mutableListOf<LoadSpec>()
    val loads: List<LoadSpec> get() = recordedLoads.toList()

    private val recordedHistory = mutableListOf<String>()
    val history: List<String> get() = recordedHistory.toList()

    var unloads = 0
        private set

    var cancelled = false
        private set

    var closed = false
        private set

    override suspend fun load(spec: LoadSpec): EngineModelInfo {
        recordedLoads += spec
        recordedHistory += "load"
        delay(loadPauseMillis)
        loadFailure?.let { throw it }
        return modelInfo
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<EngineEvent> =
        flow { script.forEach { play(it) } }
            .onCompletion { cause -> if (cause != null) markCancelled() }

    override suspend fun unload() {
        unloads++
        recordedHistory += "unload"
    }

    override fun close() {
        closed = true
        recordedHistory += "close"
    }

    private fun markCancelled() {
        cancelled = true
        recordedHistory += "cancelled"
    }

    private suspend fun FlowCollector<EngineEvent>.play(step: Step) = when (step) {
        is Step.Emit -> emit(step.event)
        is Step.Pause -> delay(step.millis)
    }

    private companion object {
        val DEFAULT_MODEL_INFO = EngineModelInfo(
            contextSize = 4096,
            trainedContext = 4096,
            backend = Backend.CPU,
            notes = emptyList(),
        )
    }
}
