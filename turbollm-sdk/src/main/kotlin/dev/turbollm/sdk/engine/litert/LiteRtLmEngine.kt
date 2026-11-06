package dev.turbollm.sdk.engine.litert

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.Engine
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineModelInfo
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.internal.Clock
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private const val ENGINE_THREAD_NAME = "turbollm-litert"
private const val DEFAULT_CONTEXT_SIZE = 4096

/**
 * The LiteRT-LM [Engine] over Google's AAR (architecture §2.4, §2.7). Each load gets its own engine thread,
 * stopped by [unload], and every LiteRT call runs on it. `AUTO` tries the GPU, then the CPU with a note.
 * Each request runs in a fresh conversation, with no prefix reuse in M1. A thinking model's thinking (LiteRT-LM's
 * `thought` channel) streams as [EngineEvent.Reasoning] and its answer as [EngineEvent.Text], as on the llama.cpp
 * path, so a client gets it as `reasoning_content` and stop sequences apply to the answer only. A request that
 * turns thinking off gets LiteRT-LM's own thinking switch; otherwise the model keeps its default.
 */
internal class LiteRtLmEngine(
    private val liteRt: LiteRtRuntime,
    private val defaultThreads: () -> Int,
    private val clock: Clock,
) : Engine {

    override val kind = EngineKind.LITERT_LM

    @Volatile
    private var engineThread: ExecutorCoroutineDispatcher? = null

    @Volatile
    private var model: LiteRtModel? = null

    override suspend fun load(spec: LoadSpec): EngineModelInfo {
        check(engineThread == null) { "This engine already holds a model." }
        val thread = newEngineThread().also { engineThread = it }
        val opened = withContext(thread) { reportingFailureAsModelLoad { openAndHold(spec) } }
        return EngineModelInfo(opened.config.maxTokens, null, opened.backend, opened.notes)
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<EngineEvent> = flow {
        val thread = engineThread ?: throw TurboLLMException.ModelNotLoaded()
        val loaded = model ?: throw TurboLLMException.ModelNotLoaded()
        val prompt = LiteRtPrompt.of(messages, params)
        emitAll(LiteRtReply(loaded, clock).events(prompt).flowOn(thread))
    }

    override suspend fun unload() {
        val thread = engineThread ?: return
        withContext(thread) { closeModel() }
        stop(thread)
    }

    /** Closes the model on the engine thread after anything already queued there, then stops the thread. */
    override fun close() {
        val thread = engineThread ?: return
        thread.executor.execute(::closeModel)
        stop(thread)
    }

    /**
     * Holds the model on the engine thread, not on the caller's: a load cancelled while LiteRT initialises
     * discards [withContext]'s result, and the model must still be there for [close] to free.
     */
    private fun openAndHold(spec: LoadSpec): OpenedModel = open(spec).also { model = it.model }

    private fun open(spec: LoadSpec): OpenedModel {
        val threads = spec.threads ?: defaultThreads()
        val cpu = LiteRtBackend.Cpu(threads)
        return when (spec.backend) {
            Backend.CPU -> openOn(spec, cpu)
            Backend.GPU -> openOn(spec, LiteRtBackend.Gpu)
            Backend.AUTO -> openOnGpuElse(spec, cpu)
        }
    }

    private fun openOnGpuElse(spec: LoadSpec, cpu: LiteRtBackend.Cpu): OpenedModel =
        try {
            openOn(spec, LiteRtBackend.Gpu)
        } catch (unavailable: TurboLLMException.EngineUnavailable) {
            throw unavailable
        } catch (gpuFailure: Exception) {
            openOn(spec, cpu).copy(notes = listOf("GPU unavailable: ${gpuFailure.message}"))
        }

    private fun openOn(spec: LoadSpec, backend: LiteRtBackend): OpenedModel {
        val config = LiteRtModelConfig(
            path = spec.path.absolutePath,
            backend = backend,
            maxTokens = spec.contextSize ?: DEFAULT_CONTEXT_SIZE,
            cacheDir = spec.cacheDir.absolutePath,
        )
        return OpenedModel(liteRt.open(config), config)
    }

    private fun closeModel() {
        model?.close()
        model = null
    }

    private fun stop(thread: ExecutorCoroutineDispatcher) {
        engineThread = null
        thread.close()
    }
}

private data class OpenedModel(
    val model: LiteRtModel,
    val config: LiteRtModelConfig,
    val notes: List<String> = emptyList(),
) {
    val backend: Backend
        get() = when (config.backend) {
            LiteRtBackend.Gpu -> Backend.GPU
            is LiteRtBackend.Cpu -> Backend.CPU
        }
}

private fun newEngineThread(): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { work -> Thread(work, ENGINE_THREAD_NAME) }.asCoroutineDispatcher()

/** The engine port promises [TurboLLMException.ModelLoadFailed] for a load that fails for any other reason. */
private inline fun <T> reportingFailureAsModelLoad(load: () -> T): T =
    try {
        load()
    } catch (failure: TurboLLMException) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw TurboLLMException.ModelLoadFailed("LiteRT-LM could not load the model: ${failure.message}", failure)
    }
