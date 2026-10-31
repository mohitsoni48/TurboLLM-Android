package dev.turbollm.sdk.engine.litert

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.GenerationParams
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.internal.monotonicClock
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LiteRtLmEngine] on the real LiteRT-LM AAR and a small `.litertlm`, taken from the instrumentation argument
 * `turbollmTestLitertlm`. CPU only, so it also answers whether LiteRT runs on the x86_64 emulator (F7).
 * Every test is bounded, and so is every wait inside one.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtLmEngineTest {
    private val engine = LiteRtLmEngine(AarLiteRtRuntime, defaultThreads = { THREADS }, clock = monotonicClock)

    @After
    fun closeTheEngine() = engine.close()

    @Test(timeout = 180_000)
    fun aCpuLoadStreamsTextAndThenDone() = runBlocking {
        engine.load(spec())

        val events = withTimeout(GENERATION_MILLIS) { engine.generate(QUESTION, SHORT).toList() }

        assertTrue("the model generated no text", events.any { it is EngineEvent.Text })
        val done = events.last() as EngineEvent.Done
        assertTrue(done.completionTokens > 0)
    }

    @Test(timeout = 180_000)
    fun cancellingMidStreamEndsAsACancellationAndTheNextReplyWorks() = runBlocking {
        engine.load(spec())
        val firstText = CompletableDeferred<Unit>()
        val ending = CompletableDeferred<Throwable>()

        val collector = launch(Dispatchers.Default) {
            try {
                engine.generate(QUESTION, ENDLESS).collect { firstText.complete(Unit) }
            } catch (end: Throwable) {
                ending.complete(end)
                throw end
            }
        }
        withTimeout(GENERATION_MILLIS) { firstText.await() }
        withTimeout(CANCEL_MILLIS) { collector.cancelAndJoin() }

        assertTrue(withTimeout(CANCEL_MILLIS) { ending.await() } is CancellationException)
        val next = withTimeout(GENERATION_MILLIS) { engine.generate(QUESTION, SHORT).toList() }
        assertTrue("a cancelled conversation left the engine unusable", next.last() is EngineEvent.Done)
    }

    @Test(timeout = 180_000)
    fun aCpuRequestReportsTheCpuBackend() = runBlocking {
        assertEquals(Backend.CPU, engine.load(spec()).backend)
    }

    private fun spec(): LoadSpec {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return LoadSpec(File(testModelPath()), CONTEXT_SIZE, null, Backend.CPU, File(context.cacheDir, "turbollm"))
    }

    private fun testModelPath(): String =
        checkNotNull(InstrumentationRegistry.getArguments().getString("turbollmTestLitertlm")) {
            "Pass the .litertlm path as the instrumentation argument turbollmTestLitertlm."
        }

    private companion object {
        const val CONTEXT_SIZE = 1024
        const val THREADS = 2
        const val GENERATION_MILLIS = 90_000L
        const val CANCEL_MILLIS = 10_000L
        val QUESTION = listOf(ChatMessage(ChatRole.USER, MessageContent.Text("Reply with the single word: yes.")))
        val SHORT = GenerationParams(maxTokens = 8, temperature = 0f, topP = null, topK = null, seed = null)
        val ENDLESS = SHORT.copy(maxTokens = 512)
    }
}
