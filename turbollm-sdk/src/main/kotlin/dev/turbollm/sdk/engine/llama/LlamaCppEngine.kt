package dev.turbollm.sdk.engine.llama

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.Engine
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineModelInfo
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.engine.LoadSpec
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val GPU_UNAVAILABLE_NOTE = "GPU is not available for llama.cpp in M1; ran on CPU"

private const val ENGINE_THREAD_NAME = "turbollm-llama"
private const val DEFAULT_CONTEXT_SIZE = 4096
private const val NO_SESSION = 0L

/**
 * The llama.cpp [Engine] over the native session (architecture §2.4, §2.5). Each load gets its own engine
 * thread, stopped by [unload]; every native call except [LlamaBinding.requestAbort] runs on it. A cancel is
 * cooperative between tokens and preemptive during a prefill. llama.cpp runs on the CPU only in M1.
 */
internal class LlamaCppEngine(
    private val llama: LlamaBinding,
    private val defaultThreads: () -> Int,
) : Engine {

    override val kind = EngineKind.LLAMA_CPP

    @Volatile
    private var engineThread: ExecutorCoroutineDispatcher? = null

    @Volatile
    private var session = NO_SESSION

    override suspend fun load(spec: LoadSpec): EngineModelInfo {
        check(engineThread == null) { "This engine already holds a model." }
        val thread = newEngineThread().also { engineThread = it }
        val opened = withContext(thread) { reportingFailureAsModelLoad { open(spec) } }
        return EngineModelInfo(opened.contextSize, opened.knownTrainedContext, Backend.CPU, notesFor(spec.backend))
    }

    override fun generate(messages: List<ChatMessage>, params: GenerationParams): Flow<EngineEvent> = flow {
        val thread = engineThread ?: throw TurboLLMException.ModelNotLoaded()
        val reply = LlamaReply(llama, session)
        abortingWhenCancelled(reply) { emitAll(reply.events(messages, params).flowOn(thread)) }
    }

    /** Returns only once the session is freed, so no native call of this engine is still running. */
    override suspend fun unload() {
        val thread = engineThread ?: return
        abortTheRunningCall()
        withContext(thread) { freeSession() }
        stop(thread)
    }

    /** Frees the session on the engine thread after anything already queued there, then stops the thread. */
    override fun close() {
        val thread = engineThread ?: return
        abortTheRunningCall()
        thread.executor.execute(::freeSession)
        stop(thread)
    }

    private fun open(spec: LoadSpec): NativeModelInfo {
        llama.loadLibrary()
        val threads = spec.threads ?: defaultThreads()
        val requested = spec.contextSize ?: DEFAULT_CONTEXT_SIZE
        val opened = openSession(spec.path, requested, threads)
        if (spec.contextSize != null || opened.trainedContext !in 1 until requested) return opened
        freeSession()
        return openSession(spec.path, opened.trainedContext, threads)
    }

    private fun openSession(model: File, contextSize: Int, threads: Int): NativeModelInfo {
        val handle = llama.loadModel(model.absolutePath, contextSize, threads)
        if (handle == NO_SESSION) {
            throw TurboLLMException.ModelLoadFailed("llama.cpp could not load the model: ${llama.lastError()}")
        }
        session = handle
        return NativeModelInfo.from(llama.modelInfo(handle))
    }

    /** A prefill or decode in flight on the engine thread would otherwise hold the free back until it ends. */
    private fun abortTheRunningCall() {
        val running = session
        if (running != NO_SESSION) llama.requestAbort(running)
    }

    private fun freeSession() {
        if (session == NO_SESSION) return
        llama.freeSession(session)
        session = NO_SESSION
    }

    private fun stop(thread: ExecutorCoroutineDispatcher) {
        engineThread = null
        thread.close()
    }

    /**
     * A prefill is one long native call that cannot see a coroutine cancel, so the native abort flag is set
     * from the cancelling thread itself: the unconfined watcher resumes there as soon as this scope is
     * cancelled, without waiting for the engine thread. A reply that ends normally never sets the flag, and
     * neither does one that failed on its own: its native call has already returned, and an unload may have
     * freed the session by the time the failure unwinds here.
     */
    private suspend fun abortingWhenCancelled(reply: LlamaReply, streaming: suspend () -> Unit) = coroutineScope {
        val replyEnded = AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!replyEnded.get()) reply.abort()
            }
        }
        try {
            streaming()
        } catch (failure: Exception) {
            if (failure !is CancellationException) replyEnded.set(true)
            throw failure
        }
        replyEnded.set(true)
        watcher.cancel()
    }
}

private fun newEngineThread(): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { work -> Thread(work, ENGINE_THREAD_NAME) }.asCoroutineDispatcher()

private fun notesFor(backend: Backend): List<String> =
    if (backend == Backend.GPU) listOf(GPU_UNAVAILABLE_NOTE) else emptyList()

/** The engine port promises [TurboLLMException.ModelLoadFailed] for a load that fails for any other reason. */
private inline fun <T> reportingFailureAsModelLoad(load: () -> T): T =
    try {
        load()
    } catch (failure: TurboLLMException) {
        throw failure
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        throw TurboLLMException.ModelLoadFailed("llama.cpp could not load the model: ${failure.message}", failure)
    }
