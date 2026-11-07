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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only holder of the live [Engine] (one model in M1). Load and unload are serialised; a load that finds
 * another load or unload running is refused with [TurboLLMException.LoadInProgress]. Unloading cancels any
 * in-flight generation, then waits for the [GenerationGate] before the engine unloads. Every load first awaits
 * [awaitPrevious] (a closed core's unload still running), so only one model is ever resident.
 */
internal class EngineHost(
    private val catalog: EngineCatalog,
    private val gate: GenerationGate,
    private val sentinel: LoadSentinel,
    private val awaitPrevious: suspend () -> Unit = {},
) {
    private val lifecycle = Mutex()
    private val publishedState = MutableStateFlow<EngineState>(EngineState.Idle)
    private val generations: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var loaded: LoadedEngine? = null

    val state: StateFlow<EngineState> = publishedState.asStateFlow()

    suspend fun load(target: LoadTarget): LoadedModel {
        claimLoad(target)
        return loadClaimed(target)
    }

    /**
     * Claims the load before returning, so a concurrent load is refused at once and [state] already reads
     * Loading, then runs it in [scope]. The outcome is published in [state] and never thrown into [scope].
     * It starts even in a cancelled [scope], so the claim it holds is always released.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun loadInBackground(target: LoadTarget, scope: CoroutineScope): Job {
        claimLoad(target)
        return scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                loadClaimed(target)
            } catch (_: TurboLLMException) {
                // Already published as Failed; a background load has no caller left to throw to.
            }
        }
    }

    suspend fun unload() = lifecycle.withLock {
        unloadCurrent()
        publishedState.value = EngineState.Idle
    }

    /** Re-reads the gate into a Ready state, so its `busy` and `queued` stay live while the model serves. */
    fun showOccupancy() = publishedState.update { state ->
        if (state is EngineState.Ready) state.copy(busy = gate.busy, queued = gate.queued) else state
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

    private fun claimLoad(target: LoadTarget) {
        if (!lifecycle.tryLock()) throw TurboLLMException.LoadInProgress()
        publishedState.value = EngineState.Loading(target.ref)
    }

    /**
     * Runs a load whose lifecycle [claimLoad] holds, and releases it however the load ends. Any failure but a
     * cancellation or a [VirtualMachineError] is published and thrown as a [TurboLLMException], so a background
     * load never takes the process down.
     */
    private suspend fun loadClaimed(target: LoadTarget): LoadedModel {
        try {
            awaitPrevious()
            unloadCurrent()
            val model = loadEngine(target).model
            publishedState.value = EngineState.Ready(model, busy = gate.busy, queued = gate.queued)
            return model
        } catch (failure: TurboLLMException) {
            throw failed(target, failure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw failed(target, loadFailureOf(failure))
        } catch (failure: LinkageError) {
            throw failed(target, loadFailureOf(failure))
        } finally {
            if (publishedState.value is EngineState.Loading) publishedState.value = EngineState.Idle
            lifecycle.unlock()
        }
    }

    private fun failed(target: LoadTarget, failure: TurboLLMException): TurboLLMException {
        publishedState.value = EngineState.Failed(target.ref, failure)
        return failure
    }

    private fun loadFailureOf(cause: Throwable) =
        TurboLLMException.ModelLoadFailed(cause.message ?: cause.javaClass.simpleName, cause)

    private suspend fun loadEngine(target: LoadTarget): LoadedEngine {
        val engine = catalog.resolve(target.format, requested = null).factory.create()
        return loadUnderSentinel(engine, target).also { loaded = it }
    }

    private suspend fun loadUnderSentinel(engine: Engine, target: LoadTarget): LoadedEngine {
        val marked = sentinel.write(target.spec.path)
        var succeeded = false
        try {
            val info = engine.load(target.spec)
            succeeded = true
            return LoadedEngine(engine, target.loadedModel(engine.kind, info))
        } finally {
            if (marked) sentinel.clear()
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
