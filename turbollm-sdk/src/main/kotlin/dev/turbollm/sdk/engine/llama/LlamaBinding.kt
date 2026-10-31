package dev.turbollm.sdk.engine.llama

import dev.turbollm.sdk.internal.Logger
import java.io.File
import java.io.IOException

/**
 * The native llama session as [LlamaCppEngine] sees it: [LlamaNative]'s calls, plus loading the library.
 * [JniLlamaBinding] is the production one; JVM tests pass a scripted fake.
 */
internal interface LlamaBinding {
    /** Loads the best native build for this CPU and initialises the backend, once per process. */
    fun loadLibrary()

    fun loadModel(path: String, contextSize: Int, threads: Int): Long
    fun modelInfo(session: Long): String
    fun prepare(session: Long, requestJson: String): Int
    fun startSampling(session: Long, temp: Float, topP: Float, topK: Int, seed: Long): Int
    fun nextToken(session: Long): ByteArray?
    fun parseDelta(session: Long, isPartial: Boolean): String
    fun requestAbort(session: Long)
    fun clearAbort(session: Long)
    fun stats(session: Long): String
    fun freeSession(session: Long)
    fun lastError(): String
}

internal class JniLlamaBinding(private val logger: Logger) : LlamaBinding {
    override fun loadLibrary() = NativeLlamaLibrary.ensureLoaded(logger)

    override fun loadModel(path: String, contextSize: Int, threads: Int) =
        LlamaNative.loadModel(path, contextSize, threads)

    override fun modelInfo(session: Long) = LlamaNative.modelInfo(session)
    override fun prepare(session: Long, requestJson: String) = LlamaNative.prepare(session, requestJson)

    override fun startSampling(session: Long, temp: Float, topP: Float, topK: Int, seed: Long) =
        LlamaNative.startSampling(session, temp, topP, topK, seed)

    override fun nextToken(session: Long) = LlamaNative.nextToken(session)
    override fun parseDelta(session: Long, isPartial: Boolean) = LlamaNative.parseDelta(session, isPartial)
    override fun requestAbort(session: Long) = LlamaNative.requestAbort(session)
    override fun clearAbort(session: Long) = LlamaNative.clearAbort(session)
    override fun stats(session: Long) = LlamaNative.stats(session)
    override fun freeSession(session: Long) = LlamaNative.freeSession(session)
    override fun lastError() = LlamaNative.lastError()
}

private object NativeLlamaLibrary {
    private const val CPUINFO = "/proc/cpuinfo"

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(logger: Logger) {
        if (loaded) return
        val variant = CpuVariant.loadBest(cpuinfo())
        LlamaNative.backendInit()
        logger.info("llama.cpp loaded ${variant.libraryName}")
        loaded = true
    }

    /** Without a readable cpuinfo, only the base build is known to run. */
    private fun cpuinfo(): String =
        try {
            File(CPUINFO).readText()
        } catch (_: IOException) {
            ""
        }
}
