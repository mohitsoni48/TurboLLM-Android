package dev.turbollm.sdk.engine.llama

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineModelInfo
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.testing.FakeLlama
import dev.turbollm.sdk.testing.FakeLlama.Companion.delta
import dev.turbollm.sdk.testing.FakeLlama.Opened
import dev.turbollm.sdk.testing.FakeLlama.Sampling
import dev.turbollm.sdk.testing.pieces
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** [LlamaCppEngine]'s own logic, driven through a scripted native layer on the JVM. */
class LlamaCppEngineFakeNativeTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private val llama = FakeLlama()
    private val engine = LlamaCppEngine(llama, defaultThreads = { DEVICE_THREADS })
    private val background = CoroutineScope(Dispatchers.Default)

    @After
    fun closeTheEngine() {
        engine.close()
        background.cancel()
    }

    @Test
    fun aLoadOpensTheModelAtTheRequestedContextAndThreadsOnCpu() = runBlocking {
        val info = engine.load(spec(contextSize = 2048, threads = 3))

        assertEquals(listOf(Opened(MODEL.absolutePath, 2048, 3)), llama.opened)
        assertEquals(EngineModelInfo(2048, 32_768, Backend.CPU, emptyList()), info)
        assertEquals(EngineKind.LLAMA_CPP, engine.kind)
    }

    @Test
    fun withoutAContextSizeTheContextIsFourThousandNinetySixTokens() = runBlocking {
        val info = engine.load(spec())

        assertEquals(listOf(4096), llama.opened.map { it.contextSize })
        assertEquals(4096, info.contextSize)
    }

    @Test
    fun aModelTrainedOnFewerTokensIsReopenedAtItsTrainedContext() = runBlocking {
        llama.trainedContext = 2048

        val info = engine.load(spec())

        assertEquals(listOf(4096, 2048), llama.opened.map { it.contextSize })
        assertEquals(listOf(1L), llama.freed)
        assertEquals(2048, info.contextSize)
        assertEquals(2048, info.trainedContext)
    }

    @Test
    fun anExplicitContextSizeIsKeptEvenAboveTheTrainedContext() = runBlocking {
        llama.trainedContext = 2048

        val info = engine.load(spec(contextSize = 8192))

        assertEquals(listOf(8192), llama.opened.map { it.contextSize })
        assertEquals(8192, info.contextSize)
    }

    @Test
    fun withoutAThreadCountTheDevicesDefaultIsUsed() = runBlocking {
        engine.load(spec())

        assertEquals(listOf(DEVICE_THREADS), llama.opened.map { it.threads })
    }

    @Test
    fun aGpuRequestRunsOnCpuWithANote() = runBlocking {
        val info = engine.load(spec(backend = Backend.GPU))

        assertEquals(Backend.CPU, info.backend)
        assertEquals(listOf("GPU is not available for llama.cpp in M1; ran on CPU"), info.notes)
    }

    @Test
    fun aModelTheNativeLayerCannotOpenFailsAsModelLoadFailedWithItsReason() {
        llama.openFailure = "unknown model architecture: 'mamba9'"

        val failure = assertThrows(TurboLLMException.ModelLoadFailed::class.java) {
            runBlocking { engine.load(spec()) }
        }

        assertTrue(failure.message!!.contains("unknown model architecture: 'mamba9'"))
    }

    @Test
    fun anEngineWhoseLibraryCannotLoadIsUnavailableAndOpensNothing() {
        llama.libraryFailure = TurboLLMException.EngineUnavailable(EngineKind.LLAMA_CPP)

        assertThrows(TurboLLMException.EngineUnavailable::class.java) { runBlocking { engine.load(spec()) } }
        assertTrue(llama.opened.isEmpty())
    }

    @Test
    fun theLibraryIsLoadedBeforeTheModelIsOpened() = runBlocking {
        engine.load(spec())

        assertEquals(listOf("loadLibrary", "loadModel"), llama.callNames().take(2))
    }

    @Test
    fun everyNativeCallRunsOnTheEnginesOwnThread() = runBlocking {
        engine.load(spec())
        llama.reply("Hi" to delta(content = "Hi"))
        engine.generate(QUESTION, PARAMS).toList()
        engine.unload()

        val threads = llama.calls.map { it.thread }.toSet()
        assertEquals(1, threads.size)
        assertEquals("turbollm-llama", threads.single().name)
        assertNotEquals(Thread.currentThread(), threads.single())
    }

    @Test
    fun aReplyReportsItsPrefilledPromptThenStreamsReasoningAndTextThenDoneWithTheNativeStats() = runBlocking {
        engine.load(spec())
        llama.reply(
            "<think>" to delta(),
            "hmm" to delta(reasoning = "hmm"),
            "</think>" to delta(),
            "Hi" to delta(content = "Hi"),
            final = delta(content = "!"),
        )

        val events = engine.generate(QUESTION, PARAMS).toList()

        val expected = listOf(
            EngineEvent.Prefilled(promptTokens = 12, promptMs = 30.5),
            EngineEvent.Reasoning("hmm"),
            EngineEvent.Text("Hi"),
            EngineEvent.Text("!"),
            EngineEvent.Done(FinishReason.STOP, promptTokens = 12, completionTokens = 4, promptMs = 30.5, genMs = 40.0),
        )
        assertEquals(expected, events)
    }

    @Test
    fun aDeltaWithBothPartsEmitsTheReasoningFirst() = runBlocking {
        engine.load(spec())
        llama.reply("x" to delta(content = "answer", reasoning = "thought"))

        val events = engine.generate(QUESTION, PARAMS).toList()

        assertEquals(listOf(EngineEvent.Reasoning("thought"), EngineEvent.Text("answer")), events.pieces())
    }

    @Test
    fun thePromptCarriesTheMessagesTheThinkingSwitchAndTheTemplateKwargs() = runBlocking {
        engine.load(spec())
        val params = PARAMS.copy(enableThinking = false, templateKwargs = mapOf("custom" to "\"value\""))

        engine.generate(QUESTION, params).toList()

        val request = Json.parseToJsonElement(llama.requests.single()).jsonObject
        val message = request["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("Hello?", message["content"]!!.jsonPrimitive.content)
        assertFalse(request["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals("\"value\"", (request["chat_template_kwargs"] as JsonObject)["custom"]!!.jsonPrimitive.content)
    }

    @Test
    fun unsetSamplingParametersUseTheDefaultsAndARandomSeed() = runBlocking {
        engine.load(spec())

        engine.generate(QUESTION, PARAMS).toList()

        assertEquals(listOf(Sampling(temperature = 0.8f, topP = 0.95f, topK = 40, seed = -1)), llama.samplings)
    }

    @Test
    fun givenSamplingParametersReachTheSampler() = runBlocking {
        engine.load(spec())
        val params = PARAMS.copy(temperature = 0f, topP = 0.5f, topK = 7, seed = 42)

        engine.generate(QUESTION, params).toList()

        assertEquals(listOf(Sampling(temperature = 0f, topP = 0.5f, topK = 7, seed = 42)), llama.samplings)
    }

    @Test
    fun aReplyThatFillsTheContextEndsWithLength() = runBlocking {
        engine.load(spec())
        llama.reply("a" to delta(content = "a"))
        llama.contextFull = true

        val done = engine.generate(QUESTION, PARAMS).toList().last() as EngineEvent.Done

        assertEquals(FinishReason.LENGTH, done.reason)
    }

    @Test
    fun theReplyEndsWithLengthOnceMaxTokensTokensAreSampled() = runBlocking {
        engine.load(spec())
        llama.endlessReply = true

        val done = engine.generate(QUESTION, PARAMS.copy(maxTokens = 5)).toList().last() as EngineEvent.Done

        assertEquals(FinishReason.LENGTH, done.reason)
        assertEquals(5, done.completionTokens)
        assertEquals(5, llama.callNames().count { it == "nextToken" })
    }

    @Test
    fun aTokenThatOnlyBeginsACharacterIsParsedOnceTheCharacterIsComplete() = runBlocking {
        engine.load(spec())
        val smile = "😀".toByteArray()
        llama.replyBytes(smile.copyOfRange(0, 2))
        llama.replyBytes(smile.copyOfRange(2, 4), delta(content = "😀"))
        llama.replyBytes("!".toByteArray(), delta(content = "!"))

        val events = engine.generate(QUESTION, PARAMS).toList()

        assertEquals(2, llama.partialParses)
        assertEquals(listOf(EngineEvent.Text("😀"), EngineEvent.Text("!")), events.pieces())
    }

    @Test
    fun aPromptThatDoesNotFitIsContextLengthExceeded() {
        loaded()
        llama.prepareSentinel = LlamaNative.CONTEXT_LENGTH_EXCEEDED
        llama.prepareFailure = "The prompt is 5000 tokens, and the context holds 4096."

        val failure = assertThrows(TurboLLMException.ContextLengthExceeded::class.java) { generateAll() }

        assertEquals("The prompt is 5000 tokens, and the context holds 4096.", failure.message)
    }

    @Test
    fun aTemplateThatCannotRenderIsTemplateUnsupported() {
        loaded()
        llama.prepareSentinel = LlamaNative.TEMPLATE_UNSUPPORTED

        assertThrows(TurboLLMException.TemplateUnsupported::class.java) { generateAll() }
    }

    @Test
    fun aFailedPrefillIsAnEngineFailureWithTheNativeReason() {
        loaded()
        llama.prepareSentinel = LlamaNative.FAILED
        llama.prepareFailure = "llama_decode returned 1"

        val failure = assertThrows(TurboLLMException.EngineFailure::class.java) { generateAll() }

        assertTrue(failure.message!!.contains("llama_decode returned 1"))
    }

    @Test
    fun aFailedTokenIsAnEngineFailureWithTheNativeReason() {
        loaded()
        llama.reply("a" to delta(content = "a"))
        llama.tokenFailure = "llama_decode returned -3"

        val failure = assertThrows(TurboLLMException.EngineFailure::class.java) { generateAll() }

        assertTrue(failure.message!!.contains("llama_decode returned -3"))
    }

    @Test
    fun theAbortFlagIsClearedBeforeEveryPrompt() = runBlocking {
        engine.load(spec())

        engine.generate(QUESTION, PARAMS).toList()
        engine.generate(QUESTION, PARAMS).toList()

        val promptSteps = llama.callNames().filter { it == "clearAbort" || it == "prepare" }
        assertEquals(listOf("clearAbort", "prepare", "clearAbort", "prepare"), promptSteps)
    }

    @Test
    fun cancellingDuringPrefillAbortsTheNativeDecodeAndCompletesPromptly() = runBlocking {
        engine.load(spec())
        llama.prefillWaitsForAbort = true
        val collecting = launch(Dispatchers.Default) { engine.generate(QUESTION, PARAMS).toList() }
        withContext(Dispatchers.IO) { llama.prefillStarted.await(5, TimeUnit.SECONDS) }

        withTimeout(PROMPT_MILLIS) { collecting.cancelAndJoin() }

        assertEquals(1, llama.abortThreads.size)
        assertNotEquals("turbollm-llama", llama.abortThreads.single().name)
    }

    @Test
    fun aReplyCancelledDuringPrefillLeavesTheEngineReadyForTheNext() = runBlocking {
        engine.load(spec())
        llama.prefillWaitsForAbort = true
        val collecting = launch(Dispatchers.Default) { engine.generate(QUESTION, PARAMS).toList() }
        withContext(Dispatchers.IO) { llama.prefillStarted.await(5, TimeUnit.SECONDS) }
        withTimeout(PROMPT_MILLIS) { collecting.cancelAndJoin() }
        llama.prefillWaitsForAbort = false

        val events = withTimeout(PROMPT_MILLIS) { engine.generate(QUESTION, PARAMS).toList() }

        assertEquals(FinishReason.STOP, (events.last() as EngineEvent.Done).reason)
    }

    @Test
    fun aCollectorThatStopsAfterTheFirstTokenAbortsAndTheNextReplyWorks() = runBlocking {
        engine.load(spec())
        llama.endlessReply = true

        withTimeout(PROMPT_MILLIS) { engine.generate(QUESTION, PARAMS).first { it is EngineEvent.Text } }
        llama.endlessReply = false
        val next = withTimeout(PROMPT_MILLIS) { engine.generate(QUESTION, PARAMS).toList() }

        assertEquals(1, llama.abortThreads.size)
        assertEquals(FinishReason.STOP, (next.last() as EngineEvent.Done).reason)
    }

    @Test
    fun aReplyThatEndsNormallyNeverRequestsAnAbort() = runBlocking {
        engine.load(spec())
        llama.reply("Hi" to delta(content = "Hi"))

        engine.generate(QUESTION, PARAMS).toList()

        assertTrue(llama.abortThreads.isEmpty())
    }

    @Test
    fun unloadFreesTheSessionAndStopsTheEngineThread() = runBlocking {
        engine.load(spec())
        val engineThread = llama.calls.first().thread

        engine.unload()

        assertEquals(listOf(1L), llama.freed)
        engineThread.join(STOP_MILLIS)
        assertFalse(engineThread.isAlive)
    }

    @Test
    fun unloadingDuringAPrefillAbortsItAndReturnsOnlyOnceThePrefillHasEndedAndTheSessionIsFree() = runBlocking {
        engine.load(spec())
        startAPrefillThatOnlyAnAbortEnds()

        val lifecycleWhenUnloaded = background.async { engine.unload(); llama.lifecycle.toList() }

        val expected = listOf("requestAbort", "prefill ended", "freeSession")
        assertEquals(expected, withTimeout(PROMPT_MILLIS) { lifecycleWhenUnloaded.await() })
    }

    @Test
    fun closingDuringAPrefillAbortsItAndFreesTheSessionOnlyOnceThePrefillHasEnded() = runBlocking {
        engine.load(spec())
        val engineThread = llama.calls.first().thread
        startAPrefillThatOnlyAnAbortEnds()

        engine.close()
        engineThread.join(STOP_MILLIS)

        assertEquals(listOf("requestAbort", "prefill ended", "freeSession"), llama.lifecycle)
    }

    @Test
    fun aModelCanBeLoadedAgainAfterUnload() = runBlocking {
        engine.load(spec())
        engine.unload()

        engine.load(spec())
        val events = engine.generate(QUESTION, PARAMS).toList()

        assertEquals(2, llama.opened.size)
        assertEquals(FinishReason.STOP, (events.last() as EngineEvent.Done).reason)
    }

    @Test
    fun closingAfterAFailedLoadStopsTheEngineThread() {
        llama.openFailure = "out of memory"
        assertThrows(TurboLLMException.ModelLoadFailed::class.java) { runBlocking { engine.load(spec()) } }
        val engineThread = llama.calls.first().thread

        engine.close()

        engineThread.join(STOP_MILLIS)
        assertFalse(engineThread.isAlive)
    }

    @Test
    fun closingALoadedEngineFreesItsSession() = runBlocking {
        engine.load(spec())
        val engineThread = llama.calls.first().thread

        engine.close()
        engineThread.join(STOP_MILLIS)

        assertEquals(listOf(1L), llama.freed)
        assertFalse(engineThread.isAlive)
    }

    @Test
    fun generatingWithNothingLoadedIsModelNotLoaded() {
        assertThrows(TurboLLMException.ModelNotLoaded::class.java) { generateAll() }
    }

    private fun loaded() = runBlocking { engine.load(spec()) }

    /** A reply whose prefill, like a long real one, returns only once the native abort flag is set. */
    private suspend fun startAPrefillThatOnlyAnAbortEnds() {
        llama.prefillWaitsForAbort = true
        background.launch { runCatching { engine.generate(QUESTION, PARAMS).toList() } }
        withContext(Dispatchers.IO) { llama.prefillStarted.await(5, TimeUnit.SECONDS) }
    }

    private fun generateAll() = runBlocking { engine.generate(QUESTION, PARAMS).toList() }

    private fun spec(contextSize: Int? = null, threads: Int? = null, backend: Backend = Backend.AUTO) =
        LoadSpec(MODEL, contextSize, threads, backend, File("cache"))

    private companion object {
        const val DEVICE_THREADS = 6
        const val PROMPT_MILLIS = 2_000L
        const val STOP_MILLIS = 2_000L
        val MODEL = File("models/tiny.gguf")
        val QUESTION = listOf(ChatMessage(ChatRole.USER, MessageContent.Text("Hello?")))
        val PARAMS = GenerationParams(maxTokens = null, temperature = null, topP = null, topK = null, seed = null)
    }
}
