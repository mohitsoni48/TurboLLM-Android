package dev.turbollm.sdk.engine.litert

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.testing.FakeLiteRt
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** [LiteRtLmEngine]'s own logic, driven through a scripted LiteRT-LM on the JVM. */
class LiteRtLmEngineFakeRuntimeTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private val liteRt = FakeLiteRt()
    private val engine = LiteRtLmEngine(liteRt, defaultThreads = { DEVICE_THREADS }, clock = liteRt.clock)

    @After
    fun closeTheEngine() = engine.close()

    @Test
    fun aCpuLoadOpensTheModelWithItsThreadsContextAndCacheFolder() = runBlocking {
        val info = engine.load(spec(Backend.CPU, contextSize = 2048, threads = 3))

        assertEquals(
            listOf(LiteRtModelConfig(MODEL.absolutePath, LiteRtBackend.Cpu(3), 2048, CACHE.absolutePath)),
            liteRt.opened,
        )
        assertEquals(EngineKind.LITERT_LM, engine.kind)
        assertEquals(2048, info.contextSize)
        assertNull(info.trainedContext)
        assertEquals(Backend.CPU, info.backend)
        assertEquals(emptyList<String>(), info.notes)
    }

    @Test
    fun withoutAThreadCountTheDevicesDefaultIsUsed() = runBlocking {
        engine.load(spec(Backend.CPU))

        assertEquals(LiteRtBackend.Cpu(DEVICE_THREADS), liteRt.opened.single().backend)
    }

    @Test
    fun withoutAContextSizeFourThousandNinetySixTokensAreUsed() = runBlocking {
        val info = engine.load(spec(Backend.CPU, contextSize = null))

        assertEquals(4096, liteRt.opened.single().maxTokens)
        assertEquals(4096, info.contextSize)
    }

    @Test
    fun autoRunsOnTheGpuWhenItInitialises() = runBlocking {
        val info = engine.load(spec(Backend.AUTO))

        assertEquals(listOf(LiteRtBackend.Gpu), liteRt.opened.map { it.backend })
        assertEquals(Backend.GPU, info.backend)
        assertEquals(emptyList<String>(), info.notes)
    }

    @Test
    fun autoRetriesOnTheCpuWithANoteWhenTheGpuFails() = runBlocking {
        liteRt.gpuFailure = IllegalStateException("no OpenCL")

        val info = engine.load(spec(Backend.AUTO))

        assertEquals(listOf(LiteRtBackend.Gpu, LiteRtBackend.Cpu(DEVICE_THREADS)), liteRt.opened.map { it.backend })
        assertEquals(Backend.CPU, info.backend)
        assertEquals(listOf("GPU unavailable: no OpenCL"), info.notes)
    }

    @Test
    fun anExplicitGpuRequestThatFailsIsAModelLoadFailureWithoutACpuRetry() = runBlocking<Unit> {
        liteRt.gpuFailure = IllegalStateException("no OpenCL")

        val failure = assertThrows(TurboLLMException.ModelLoadFailed::class.java) {
            runBlocking { engine.load(spec(Backend.GPU)) }
        }

        assertTrue(failure.message!!.contains("no OpenCL"))
        assertEquals(listOf(LiteRtBackend.Gpu), liteRt.opened.map { it.backend })
    }

    @Test
    fun aCpuFailureIsAModelLoadFailureWithTheReason() = runBlocking<Unit> {
        liteRt.cpuFailure = IllegalArgumentException("not a LiteRT-LM bundle")

        val failure = assertThrows(TurboLLMException.ModelLoadFailed::class.java) {
            runBlocking { engine.load(spec(Backend.CPU)) }
        }

        assertTrue(failure.message!!.contains("not a LiteRT-LM bundle"))
    }

    @Test
    fun anEngineThatCannotRunOnThisDeviceIsUnavailableAndNotRetriedOnTheCpu() = runBlocking<Unit> {
        liteRt.gpuFailure = TurboLLMException.EngineUnavailable(EngineKind.LITERT_LM)

        val failure = assertThrows(TurboLLMException.EngineUnavailable::class.java) {
            runBlocking { engine.load(spec(Backend.AUTO)) }
        }

        assertEquals(EngineKind.LITERT_LM, failure.kind)
        assertEquals(listOf(LiteRtBackend.Gpu), liteRt.opened.map { it.backend })
    }

    @Test
    fun aReplyStreamsEachChunkAsTextThenDoneWithTheTokenCountDeltas() = runBlocking {
        liteRt.chunks = listOf("Hel", "lo")
        liteRt.tokensBeforeReply = 10
        liteRt.tokensAfterReply = 15
        engine.load(spec())

        val events = withTimeout(WAIT_MILLIS) { engine.generate(QUESTION, UNSET).toList() }

        assertEquals(listOf(EngineEvent.Text("Hel"), EngineEvent.Text("lo")), events.dropLast(1))
        val done = events.last() as EngineEvent.Done
        assertEquals(FinishReason.STOP, done.reason)
        assertEquals(10, done.promptTokens)
        assertEquals(5, done.completionTokens)
    }

    @Test
    fun timingsAreWallClockFromTheSendToTheFirstChunkAndThenToTheEnd() = runBlocking {
        liteRt.prefillMillis = 30
        liteRt.generationMillis = 70
        engine.load(spec())

        val done = withTimeout(WAIT_MILLIS) { engine.generate(QUESTION, UNSET).toList() }.last() as EngineEvent.Done

        assertEquals(30.0, done.promptMs, 0.0)
        assertEquals(70.0, done.genMs, 0.0)
    }

    @Test
    fun anEmptyChunkIsNotEmitted() = runBlocking {
        liteRt.chunks = listOf("", "Hi", "")
        engine.load(spec())

        val events = withTimeout(WAIT_MILLIS) { engine.generate(QUESTION, UNSET).toList() }

        assertEquals(listOf(EngineEvent.Text("Hi")), events.dropLast(1))
    }

    @Test
    fun aReplyThatUsesAllItsMaxTokensEndsWithLength() = runBlocking {
        liteRt.tokensBeforeReply = 10
        liteRt.tokensAfterReply = 15
        engine.load(spec())

        val done = withTimeout(WAIT_MILLIS) {
            engine.generate(QUESTION, UNSET.copy(maxTokens = 5)).toList()
        }.last() as EngineEvent.Done

        assertEquals(FinishReason.LENGTH, done.reason)
    }

    @Test
    fun aReplyShorterThanItsMaxTokensEndsWithStop() = runBlocking {
        liteRt.tokensBeforeReply = 10
        liteRt.tokensAfterReply = 15
        engine.load(spec())

        val done = withTimeout(WAIT_MILLIS) {
            engine.generate(QUESTION, UNSET.copy(maxTokens = 6)).toList()
        }.last() as EngineEvent.Done

        assertEquals(FinishReason.STOP, done.reason)
    }

    @Test
    fun eachRequestGetsItsOwnConversationBuiltFromTheMappedPromptAndClosedAfterwards() = runBlocking {
        engine.load(spec())
        val conversation = listOf(
            ChatMessage(ChatRole.SYSTEM, MessageContent.Text("Be brief.")),
            ChatMessage(ChatRole.USER, MessageContent.Text("Say hi.")),
        )

        withTimeout(WAIT_MILLIS) { engine.generate(conversation, UNSET.copy(maxTokens = 9)).toList() }
        withTimeout(WAIT_MILLIS) { engine.generate(QUESTION, UNSET).toList() }

        assertEquals(2, liteRt.conversations.size)
        assertEquals(LiteRtPrompt.of(conversation, UNSET.copy(maxTokens = 9)), liteRt.conversations[0].prompt)
        liteRt.conversations.forEach { assertEquals(listOf("reply", "close"), it.history) }
    }

    @Test
    fun aFinalTurnNotFromTheUserIsRefusedBeforeAnyConversationOpens() = runBlocking<Unit> {
        engine.load(spec())
        val endsWithTheModel = QUESTION + ChatMessage(ChatRole.ASSISTANT, MessageContent.Text("Hi."))

        val failure = assertThrows(TurboLLMException.InvalidRequest::class.java) {
            runBlocking { engine.generate(endsWithTheModel, UNSET).toList() }
        }

        assertEquals("invalid_last_message", failure.code)
        assertTrue(liteRt.conversations.isEmpty())
    }

    @Test
    fun aCollectorThatStopsMidStreamCancelsTheProcessThenClosesTheConversation() = runBlocking {
        liteRt.endlessReply = true
        engine.load(spec())

        withTimeout(WAIT_MILLIS) { engine.generate(QUESTION, UNSET).first { it is EngineEvent.Text } }

        assertEquals(listOf("reply", "cancel", "close"), liteRt.conversations.single().history)
    }

    @Test
    fun aFailureRaisedAfterTheCancelIsStillACancellation() = runBlocking {
        liteRt.endlessReply = true
        liteRt.failureOnCancel = IllegalStateException("Process cancelled")
        liteRt.cancelFailure = IllegalStateException("The session is not alive")
        engine.load(spec())
        val firstText = CompletableDeferred<Unit>()
        val ending = CompletableDeferred<Throwable>()

        val collector = launch(Dispatchers.Default) {
            try {
                engine.generate(QUESTION, UNSET).collect { firstText.complete(Unit) }
            } catch (end: Throwable) {
                ending.complete(end)
                throw end
            }
        }
        withTimeout(WAIT_MILLIS) { firstText.await() }
        collector.cancelAndJoin()

        val end = withTimeout(WAIT_MILLIS) { ending.await() }
        assertTrue("expected a cancellation, got $end", end is CancellationException)
        assertEquals(listOf("reply", "cancel", "close"), liteRt.conversations.single().history)
    }

    @Test
    fun aFailedReplyIsAnEngineFailureAndItsConversationIsStillClosed() = runBlocking<Unit> {
        liteRt.replyFailure = IllegalStateException("decode failed")
        engine.load(spec())

        val failure = assertThrows(TurboLLMException.EngineFailure::class.java) {
            runBlocking { engine.generate(QUESTION, UNSET).toList() }
        }

        assertTrue(failure.message!!.contains("decode failed"))
        assertEquals("close", liteRt.conversations.single().history.last())
    }

    @Test
    fun everyLiteRtCallRunsOnTheEnginesOwnThread() = runBlocking {
        engine.load(spec())
        withTimeout(WAIT_MILLIS) { engine.generate(QUESTION, UNSET).toList() }
        liteRt.endlessReply = true
        withTimeout(WAIT_MILLIS) { engine.generate(QUESTION, UNSET).first() }
        engine.unload()

        val threads = liteRt.calls.map { it.thread }.toSet()
        assertEquals("calls: ${liteRt.calls.map { it.name }}", 1, threads.size)
        assertEquals("turbollm-litert", threads.single().name)
        assertTrue(liteRt.calls.map { it.name }.containsAll(listOf("open", "cancel", "closeModel")))
    }

    @Test
    fun generatingBeforeALoadIsModelNotLoaded() {
        assertThrows(TurboLLMException.ModelNotLoaded::class.java) {
            runBlocking { engine.generate(QUESTION, UNSET).toList() }
        }
    }

    @Test
    fun unloadClosesTheModelAndStopsTheEngineThread() = runBlocking<Unit> {
        engine.load(spec())
        val engineThread = liteRt.calls.first().thread

        engine.unload()

        assertEquals(1, liteRt.modelsClosed)
        engineThread.join(WAIT_MILLIS)
        assertFalse(engineThread.isAlive)
        assertThrows(TurboLLMException.ModelNotLoaded::class.java) {
            runBlocking { engine.generate(QUESTION, UNSET).toList() }
        }
    }

    @Test
    fun anUnloadedEngineCanLoadAgain() = runBlocking {
        engine.load(spec())
        engine.unload()

        engine.load(spec())
        val events = withTimeout(WAIT_MILLIS) { engine.generate(QUESTION, UNSET).toList() }

        assertTrue(events.last() is EngineEvent.Done)
    }

    @Test
    fun closingALoadedEngineClosesItsModel() = runBlocking {
        engine.load(spec())
        val engineThread = liteRt.calls.first().thread

        engine.close()
        engineThread.join(WAIT_MILLIS)

        assertEquals(1, liteRt.modelsClosed)
        assertFalse(engineThread.isAlive)
    }

    @Test
    fun closingAfterAFailedLoadStopsTheEngineThread() = runBlocking<Unit> {
        liteRt.cpuFailure = IllegalStateException("bad file")
        assertThrows(TurboLLMException.ModelLoadFailed::class.java) {
            runBlocking { engine.load(spec(Backend.CPU)) }
        }
        val engineThread = liteRt.calls.first().thread

        engine.close()
        engineThread.join(WAIT_MILLIS)

        assertFalse(engineThread.isAlive)
        assertEquals(0, liteRt.modelsClosed)
    }

    private fun spec(backend: Backend = Backend.CPU, contextSize: Int? = 1024, threads: Int? = null) =
        LoadSpec(MODEL, contextSize, threads, backend, CACHE)

    private companion object {
        const val DEVICE_THREADS = 4
        const val WAIT_MILLIS = 2_000L
        val MODEL = File("models/gemma.litertlm")
        val CACHE = File("cache/turbollm")
        val QUESTION = listOf(ChatMessage(ChatRole.USER, MessageContent.Text("Say hi.")))
        val UNSET = GenerationParams(maxTokens = null, temperature = null, topP = null, topK = null, seed = null)
    }
}
