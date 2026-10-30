package dev.turbollm.sdk.engine

import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.EngineState
import dev.turbollm.sdk.LoadedModel
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.chat.GenerationGate
import dev.turbollm.sdk.internal.LoadSentinel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only holder of the live [Engine] (one model in M1). Load and unload are serialised; a load that finds
 * another load or unload running is refused with [TurboLLMException.LoadInProgress]. Unloading cancels any
 * in-flight generation, then waits for the [GenerationGate] before the engine unloads.
 */
internal class EngineHost(
    private val registry: EngineRegistry,
    private val gate: GenerationGate,
    private val sentinel: LoadSentinel,
) {
    private val lifecycle = Mutex()
    private val publishedState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val generations: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var loaded: LoadedEngine? = null

    val state: StateFlow<EngineState> = publishedState.asStateFlow()

    suspend fun load(target: LoadTarget): LoadedModel {
        if (!lifecycle.tryLock()) throw TurboLLMException.LoadInProgress()
        try {
            unloadCurrent()
            publishedState.value = EngineState.Loading(target.ref)
            val model = loadEngine(target).model
            publishedState.value = EngineState.Ready(model, busy = gate.busy, queued = gate.queued)
            return model
        } catch (failure: TurboLLMException) {
            publishedState.value = EngineState.Failed(target.ref, failure)
            throw failure
        } finally {
            if (publishedState.value is EngineState.Loading) publishedState.value = EngineState.Idle
            lifecycle.unlock()
        }
    }

    suspend fun unload() = lifecycle.withLock {
        unloadCurrent()
        publishedState.value = EngineState.Idle
    }

    /** Runs [generation] against the loaded engine; an unload cancels it. Fails fast when nothing is loaded. */
    suspend fun <T> withLoaded(generation: suspend (LoadedEngine) -> T): T = coroutineScope {
        val generationJob = coroutineContext.job
        generations += generationJob
        try {
            generation(loaded ?: throw TurboLLMException.ModelNotLoaded())
        } finally {
            generations -= generationJob
        }
    }

    private suspend fun loadEngine(target: LoadTarget): LoadedEngine {
        val engine = registry.engineFor(target.format)
        return loadUnderSentinel(engine, target).also { loaded = it }
    }

    private suspend fun loadUnderSentinel(engine: Engine, target: LoadTarget): LoadedEngine {
        sentinel.write(target.spec.path)
        var succeeded = false
        try {
            val info = engine.load(target.spec)
            succeeded = true
            return LoadedEngine(engine, target.loadedModel(engine.kind, info))
        } finally {
            sentinel.clear()
            if (!succeeded) engine.close()
        }
    }

    private suspend fun unloadCurrent() {
        val current = loaded ?: return
        loaded = null
        generations.forEach { it.cancel(CancellationException("The model was unloaded.")) }
        gate.awaitIdle()
        current.engine.unload()
        current.engine.close()
    }
}

internal data class LoadTarget(val ref: ModelRef, val id: String, val format: ModelFormat, val spec: LoadSpec)

internal class LoadedEngine(val engine: Engine, val model: LoadedModel)

private fun LoadTarget.loadedModel(kind: EngineKind, info: EngineModelInfo) = LoadedModel(
    id = id,
    path = spec.path.absolutePath,
    format = format,
    engine = kind,
    contextSize = info.contextSize,
    backend = info.backend,
    notes = info.notes,
)
