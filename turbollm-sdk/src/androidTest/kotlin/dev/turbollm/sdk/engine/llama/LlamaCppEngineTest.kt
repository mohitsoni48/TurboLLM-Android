package dev.turbollm.sdk.engine.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.internal.Logger
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LlamaCppEngine] on the real native library and a real tiny GGUF, taken from the instrumentation
 * argument `turbollmTestGguf`. Every test is bounded, and so is every wait inside one.
 */
@RunWith(AndroidJUnit4::class)
class LlamaCppEngineTest {
    private val engine = LlamaCppEngine(JniLlamaBinding(Logger { }), defaultThreads = { THREADS })

    @After
    fun closeTheEngine() = engine.close()

    @Test(timeout = 120_000)
    fun aReplyStreamsTextAndEndsWithStop() = runBlocking {
        engine.load(spec())

        val events = withTimeout(GENERATION_MILLIS) { engine.generate(QUESTION, GREEDY).toList() }

        assertTrue("the model generated no text", events.any { it is EngineEvent.Text })
        val done = events.last() as EngineEvent.Done
        assertEquals(FinishReason.STOP, done.reason)
        assertTrue(done.promptTokens > 0)
    }

    @Test(timeout = 120_000)
    fun cancellingAfterTheFirstTokenEndsPromptlyAndTheNextReplyWorks() = runBlocking {
        engine.load(spec())

        withTimeout(GENERATION_MILLIS) { engine.generate(QUESTION, ENDLESS).first { it is EngineEvent.Text } }
        val next = withTimeout(GENERATION_MILLIS) { engine.generate(QUESTION, SHORT).toList() }

        assertTrue(next.last() is EngineEvent.Done)
    }

    @Test(timeout = 120_000)
    fun aModelUnloadedCanBeLoadedAgain() = runBlocking {
        engine.load(spec())
        engine.unload()

        engine.load(spec())
        val events = withTimeout(GENERATION_MILLIS) { engine.generate(QUESTION, SHORT).toList() }

        assertTrue(events.last() is EngineEvent.Done)
    }

    @Test(timeout = 120_000)
    fun aGpuRequestRunsOnCpuWithTheNote() = runBlocking {
        val info = engine.load(spec(Backend.GPU))

        assertEquals(Backend.CPU, info.backend)
        assertEquals(listOf("GPU is not available for llama.cpp in M1; ran on CPU"), info.notes)
    }

    private fun spec(backend: Backend = Backend.AUTO): LoadSpec {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return LoadSpec(File(testModelPath()), CONTEXT_SIZE, null, backend, context.cacheDir)
    }

    private fun testModelPath(): String =
        checkNotNull(InstrumentationRegistry.getArguments().getString("turbollmTestGguf")) {
            "Pass the GGUF path as the instrumentation argument turbollmTestGguf."
        }

    private companion object {
        const val CONTEXT_SIZE = 1024
        const val THREADS = 2
        const val GENERATION_MILLIS = 60_000L
        val QUESTION = listOf(ChatMessage(ChatRole.USER, MessageContent.Text("Reply with the single word: yes.")))
        val GREEDY = GenerationParams(maxTokens = null, temperature = 0f, topP = null, topK = null, seed = null)
        val SHORT = GREEDY.copy(maxTokens = 8)
        val ENDLESS = GREEDY.copy(maxTokens = 512)
    }
}
