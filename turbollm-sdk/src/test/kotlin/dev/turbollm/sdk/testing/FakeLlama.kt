package dev.turbollm.sdk.testing

import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.llama.LlamaBinding
import dev.turbollm.sdk.engine.llama.LlamaNative
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A scripted native llama layer. It records every call with the thread that made it, so tests can check
 * the engine's threading, and it can block a prefill until an abort is requested, as the real decode does.
 */
internal class FakeLlama : LlamaBinding {
    data class Call(val name: String, val thread: Thread)
    data class Opened(val path: String, val contextSize: Int, val threads: Int)
    data class Sampling(val temperature: Float, val topP: Float, val topK: Int, val seed: Long)

    var libraryFailure: TurboLLMException? = null
    var openFailure: String? = null
    var trainedContext = 32_768
    var promptTokens = 12
    var prepareSentinel: Int? = null
    var prepareFailure = ""
    var prefillWaitsForAbort = false
    var endlessReply = false
    var contextFull = false
    var tokenFailure = ""

    val calls: MutableList<Call> = CopyOnWriteArrayList()
    val opened: MutableList<Opened> = CopyOnWriteArrayList()
    val freed: MutableList<Long> = CopyOnWriteArrayList()
    val requests: MutableList<String> = CopyOnWriteArrayList()
    val samplings: MutableList<Sampling> = CopyOnWriteArrayList()
    val abortThreads: MutableList<Thread> = CopyOnWriteArrayList()
    val prefillStarted = CountDownLatch(1)

    private val tokens = ArrayDeque<ByteArray>()
    private val deltas = ArrayDeque<String>()
    private var finalDelta = delta()
    private var abortSignal = CountDownLatch(1)
    private var nextHandle = 1L
    private var sampled = 0
    private var error = ""

    @Volatile
    var partialParses = 0
        private set

    /** Each token comes with the parse delta the native parser gives once that token is in. */
    fun reply(vararg tokenDeltas: Pair<String, String>, final: String = delta()) {
        tokenDeltas.forEach { (token, parsed) -> replyBytes(token.toByteArray(), parsed) }
        finalDelta = final
    }

    /** A token, and the delta the parse after it gives when the engine parses there. */
    fun replyBytes(token: ByteArray, parsed: String? = null) {
        tokens += token
        parsed?.let { deltas += it }
    }

    override fun loadLibrary() {
        record("loadLibrary")
        libraryFailure?.let { throw it }
    }

    override fun loadModel(path: String, contextSize: Int, threads: Int): Long {
        record("loadModel")
        opened += Opened(path, contextSize, threads)
        openFailure?.let { return failWith(it, 0L) }
        return nextHandle++
    }

    override fun modelInfo(session: Long): String {
        record("modelInfo")
        val contextSize = opened.last().contextSize
        return """{"n_ctx":$contextSize,"n_ctx_train":$trainedContext,"desc":"fake"}"""
    }

    override fun prepare(session: Long, requestJson: String): Int {
        record("prepare")
        requests += requestJson
        if (prefillWaitsForAbort) return waitForAbort()
        prepareSentinel?.let { return failWith(prepareFailure, it) }
        sampled = 0
        return promptTokens
    }

    override fun startSampling(session: Long, temp: Float, topP: Float, topK: Int, seed: Long): Int {
        record("startSampling")
        samplings += Sampling(temp, topP, topK, seed)
        return 0
    }

    override fun nextToken(session: Long): ByteArray? {
        record("nextToken")
        if (endlessReply) return "x".toByteArray().also { sampled++; deltas += delta(content = "x") }
        val token = tokens.removeFirstOrNull() ?: return failWith(tokenFailure, null)
        sampled++
        return token
    }

    override fun parseDelta(session: Long, isPartial: Boolean): String {
        record("parseDelta")
        if (!isPartial) return finalDelta
        partialParses++
        return deltas.removeFirstOrNull() ?: delta()
    }

    override fun requestAbort(session: Long) {
        abortThreads += Thread.currentThread()
        abortSignal.countDown()
    }

    override fun clearAbort(session: Long) {
        record("clearAbort")
        abortSignal = CountDownLatch(1)
    }

    override fun stats(session: Long): String {
        record("stats")
        return """{"prompt_n":$promptTokens,"prompt_decoded":$promptTokens,"prompt_ms":30.5,""" +
            """"predicted_n":$sampled,"predicted_ms":40.0,"context_full":$contextFull}"""
    }

    override fun freeSession(session: Long) {
        record("freeSession")
        freed += session
    }

    override fun lastError(): String = error

    fun callNames(): List<String> = calls.map { it.name }

    private fun waitForAbort(): Int {
        prefillStarted.countDown()
        abortSignal.await(PREFILL_LIMIT_SECONDS, TimeUnit.SECONDS)
        return LlamaNative.ABORTED
    }

    private fun <T> failWith(message: String, sentinel: T): T {
        error = message
        return sentinel
    }

    private fun record(name: String) {
        error = ""
        calls += Call(name, Thread.currentThread())
    }

    companion object {
        private const val PREFILL_LIMIT_SECONDS = 30L

        fun delta(content: String = "", reasoning: String = ""): String =
            """{"content":"$content","reasoning":"$reasoning"}"""
    }
}
