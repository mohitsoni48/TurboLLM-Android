package dev.turbollm.sdk.engine

import dev.turbollm.sdk.ModelFormat

/** Creates a fresh engine; throws `TurboLLMException.EngineUnavailable` when it cannot run on this device. */
internal fun interface EngineFactory {
    fun create(): Engine
}

/** Maps a model format to the engine that runs it. Factories are injected so tests can pass fakes. */
internal class EngineRegistry(
    private val llamaCpp: EngineFactory,
    private val liteRtLm: EngineFactory,
) {
    fun engineFor(format: ModelFormat): Engine = when (format) {
        ModelFormat.GGUF -> llamaCpp.create()
        ModelFormat.LITERTLM -> liteRtLm.create()
    }
}
