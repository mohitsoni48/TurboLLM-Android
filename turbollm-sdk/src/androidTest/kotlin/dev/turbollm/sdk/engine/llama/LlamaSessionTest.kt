package dev.turbollm.sdk.engine.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Drives the native llama session through [LlamaNative] on a real tiny GGUF.
 *
 * The model comes from the instrumentation argument `turbollmTestGguf`. The thinking case needs a
 * Qwen3-family model, whose template honours `enable_thinking`.
 */
@RunWith(AndroidJUnit4::class)
class LlamaSessionTest {
    private var session = 0L

    @Before
    fun loadTheTestModel() {
        CpuVariant.loadBest(File("/proc/cpuinfo").readText())
        LlamaNative.backendInit()
        session = LlamaNative.loadModel(testModelPath(), CONTEXT_SIZE, THREADS)
        assertNotEquals(LlamaNative.lastError(), 0L, session)
    }

    @After
    fun freeTheSession() {
        LlamaNative.freeSession(session)
    }

    @Test(timeout = 120_000)
    fun greedyGenerationGivesTheSameTextTwice() {
        val question = conversation(user("Name three colours."))

        val first = generateGreedily(question)
        val second = generateGreedily(question)

        assertTrue("the model generated nothing", first.isNotEmpty())
        assertEquals(first, second)
    }

    @Test(timeout = 120_000)
    fun aSecondTurnDecodesOnlyThePromptAfterTheSharedPrefix() {
        val opening = conversation(user("Hello there."))
        val reply = generateGreedily(opening)
        val followUp = conversation(user("Hello there."), assistant(reply), user("And how are you?"))

        val promptTokens = prepared(followUp)
        val decoded = JSONObject(LlamaNative.stats(session)).getInt("prompt_decoded")

        assertTrue("decoded $decoded of $promptTokens", decoded in 1 until promptTokens)
    }

    @Test(timeout = 60_000)
    fun aPromptAsLongAsTheContextIsRefusedAsContextLengthExceeded() {
        val tooLong = conversation(user("hello ".repeat(CONTEXT_SIZE * 2)))

        assertEquals(LlamaNative.CONTEXT_LENGTH_EXCEEDED, LlamaNative.prepare(session, tooLong))
    }

    @Test(timeout = 60_000)
    fun anAbortDuringPrefillReturnsWithin500Milliseconds() {
        val longPrompt = conversation(user("count the words ".repeat(CONTEXT_SIZE / 4)))
        val result = AtomicInteger(Int.MIN_VALUE)
        val prefill = thread { result.set(LlamaNative.prepare(session, longPrompt)) }

        Thread.sleep(PREFILL_HEAD_START_MS)
        val abortedAt = System.nanoTime()
        LlamaNative.requestAbort(session)
        prefill.join(ABORT_DEADLINE_MS * 10)
        val returnedAfterMs = (System.nanoTime() - abortedAt) / 1_000_000

        assertTrue("prepare returned ${returnedAfterMs} ms after the abort", returnedAfterMs < ABORT_DEADLINE_MS)
        assertTrue("prepare gave ${result.get()}", result.get() == LlamaNative.ABORTED || result.get() > 0)
    }

    @Test(timeout = 120_000)
    fun anAbortedPrefillLeavesTheSessionUsable() {
        val question = conversation(user("count the words ".repeat(CONTEXT_SIZE / 4)))
        LlamaNative.requestAbort(session)
        assertEquals(LlamaNative.ABORTED, LlamaNative.prepare(session, question))

        LlamaNative.clearAbort(session)

        assertTrue(prepared(question) > 0)
    }

    @Test(timeout = 60_000)
    fun turningThinkingOffRendersADifferentPrompt() {
        val question = user("What is two plus two?")
        val thinking = prepared(JSONObject().put("messages", JSONArray().put(question)).toString())
        val notThinking = prepared(
            JSONObject().put("messages", JSONArray().put(question)).put("enable_thinking", false).toString(),
        )

        assertNotEquals(thinking, notThinking)
    }

    private fun generateGreedily(request: String): String {
        prepared(request)
        LlamaNative.startSampling(session, 0f, 1f, 0, 0L)
        val text = StringBuilder()
        val decoder = Utf8StreamDecoder()
        repeat(MAX_GENERATED_TOKENS) {
            val bytes = LlamaNative.nextToken(session) ?: return text.append(decoder.flush()).toString()
            text.append(decoder.decode(bytes))
        }
        return text.append(decoder.flush()).toString()
    }

    private fun prepared(request: String): Int {
        val promptTokens = LlamaNative.prepare(session, request)
        assertTrue("prepare failed: ${LlamaNative.lastError()}", promptTokens > 0)
        return promptTokens
    }

    private fun conversation(vararg messages: JSONObject): String =
        JSONObject().put("messages", JSONArray(messages.toList())).toString()

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)

    private fun assistant(text: String) = JSONObject().put("role", "assistant").put("content", text)

    private fun testModelPath(): String = requireNotNull(
        InstrumentationRegistry.getArguments().getString("turbollmTestGguf"),
    ) { "Pass -e turbollmTestGguf <path to a tiny GGUF on the device>." }

    private companion object {
        const val CONTEXT_SIZE = 2048
        const val THREADS = 4
        const val MAX_GENERATED_TOKENS = 24
        const val PREFILL_HEAD_START_MS = 50L
        const val ABORT_DEADLINE_MS = 500L
    }
}
