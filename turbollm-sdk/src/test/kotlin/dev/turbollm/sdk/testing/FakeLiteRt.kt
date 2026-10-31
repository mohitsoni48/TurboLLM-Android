package dev.turbollm.sdk.testing

import dev.turbollm.sdk.engine.litert.LiteRtBackend
import dev.turbollm.sdk.engine.litert.LiteRtConversation
import dev.turbollm.sdk.engine.litert.LiteRtModel
import dev.turbollm.sdk.engine.litert.LiteRtModelConfig
import dev.turbollm.sdk.engine.litert.LiteRtPrompt
import dev.turbollm.sdk.engine.litert.LiteRtRuntime
import dev.turbollm.sdk.internal.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scripted LiteRT-LM. It records every call with the thread that made it, so tests can check the engine's
 * threading, and it moves [clock] by the scripted prefill and generation times while a reply runs.
 */
internal class FakeLiteRt(val clock: SteppingClock = SteppingClock()) : LiteRtRuntime {
    data class Call(val name: String, val thread: Thread)

    var gpuFailure: Exception? = null
    var cpuFailure: Exception? = null
    var chunks = listOf("Hello")
    var tokensBeforeReply = 10
    var tokensAfterReply = 15
    var prefillMillis = 0L
    var generationMillis = 0L
    var endlessReply = false
    var replyFailure: Exception? = null
    var failureOnCancel: Exception? = null
    var cancelFailure: Exception? = null

    val calls: MutableList<Call> = CopyOnWriteArrayList()
    val opened: MutableList<LiteRtModelConfig> = CopyOnWriteArrayList()
    val conversations: MutableList<FakeConversation> = CopyOnWriteArrayList()

    @Volatile
    var modelsClosed = 0
        private set

    override fun open(config: LiteRtModelConfig): LiteRtModel {
        record("open")
        opened += config
        failureOn(config.backend)?.let { throw it }
        return FakeModel()
    }

    private fun failureOn(backend: LiteRtBackend): Exception? = when (backend) {
        LiteRtBackend.Gpu -> gpuFailure
        is LiteRtBackend.Cpu -> cpuFailure
    }

    private fun record(name: String) {
        calls += Call(name, Thread.currentThread())
    }

    inner class FakeModel : LiteRtModel {
        override fun newConversation(prompt: LiteRtPrompt): LiteRtConversation {
            record("newConversation")
            return FakeConversation(prompt).also { conversations += it }
        }

        override fun close() {
            record("closeModel")
            modelsClosed++
        }
    }

    /** [history] lists what happened to this conversation: `reply`, `cancel`, `close`. */
    inner class FakeConversation(val prompt: LiteRtPrompt) : LiteRtConversation {
        val history: MutableList<String> = CopyOnWriteArrayList()

        @Volatile
        private var replied = false

        override fun reply(userTurn: String): Flow<String> = flow {
            happened("reply")
            clock.advance(prefillMillis)
            chunks.forEach { emit(it) }
            if (endlessReply) awaitCancelOrFail()
            replyFailure?.let { throw it }
            clock.advance(generationMillis)
            replied = true
        }

        override fun tokenCount(): Int {
            record("tokenCount")
            return if (replied) tokensAfterReply else tokensBeforeReply
        }

        override fun cancel() {
            happened("cancel")
            cancelFailure?.let { throw it }
        }

        override fun close() = happened("close")

        /** The real cancel's effect on an in-flight reply is undocumented; it may fail the reply with any error. */
        private suspend fun awaitCancelOrFail(): Nothing {
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw failureOnCancel ?: cancelled
            }
        }

        private fun happened(event: String) {
            record(event)
            history += event
        }
    }
}

internal class SteppingClock(start: Long = 1_000L) : Clock {
    private val now = AtomicLong(start)

    override fun nowMillis(): Long = now.get()

    fun advance(millis: Long) {
        now.addAndGet(millis)
    }
}
