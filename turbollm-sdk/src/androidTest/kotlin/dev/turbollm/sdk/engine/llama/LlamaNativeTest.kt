package dev.turbollm.sdk.engine.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The JNI edge itself: failures come back as sentinels plus [LlamaNative.lastError], handles are
 * checked rather than dereferenced, and text survives the trip in both directions.
 *
 * The tests that need a model read it from the instrumentation argument `turbollmTestGguf`.
 */
@RunWith(AndroidJUnit4::class)
class LlamaNativeTest {
    @Before
    fun loadTheLibrary() {
        CpuVariant.loadBest(File("/proc/cpuinfo").readText())
        LlamaNative.backendInit()
    }

    @Test(timeout = 30_000)
    fun aModelThatDoesNotExistGivesNoSessionAndSaysWhy() {
        val session = LlamaNative.loadModel("/data/local/tmp/no-such-model.gguf", CONTEXT_SIZE, THREADS)

        assertEquals(0L, session)
        assertTrue("lastError is empty", LlamaNative.lastError().isNotEmpty())
    }

    @Test(timeout = 30_000)
    fun aFailureMessageKeepsCharactersOutsideTheBasicPlane() {
        val path = "/data/local/tmp/no-such-model-\uD83D\uDE00.gguf"

        LlamaNative.loadModel(path, CONTEXT_SIZE, THREADS)

        assertTrue(LlamaNative.lastError(), LlamaNative.lastError().contains(path))
    }

    @Test(timeout = 30_000)
    fun aCallThatSucceedsClearsThePreviousFailure() {
        LlamaNative.loadModel("/data/local/tmp/no-such-model.gguf", CONTEXT_SIZE, THREADS)

        LlamaNative.freeSession(UNKNOWN_SESSION)

        assertEquals("", LlamaNative.lastError())
    }

    @Test(timeout = 30_000)
    fun aHandleThatWasNeverIssuedIsRefusedNotDereferenced() {
        assertEquals(LlamaNative.FAILED, LlamaNative.prepare(UNKNOWN_SESSION, conversation("Hi")))
        assertTrue("lastError is empty", LlamaNative.lastError().isNotEmpty())
        assertNull(LlamaNative.nextToken(UNKNOWN_SESSION))
        assertEquals("", LlamaNative.stats(UNKNOWN_SESSION))
        LlamaNative.requestAbort(UNKNOWN_SESSION)
    }

    @Test(timeout = 60_000)
    fun freeingASessionTwiceDoesNotCrash() {
        val session = loadedTestModel()

        LlamaNative.freeSession(session)
        LlamaNative.freeSession(session)

        assertEquals(LlamaNative.FAILED, LlamaNative.prepare(session, conversation("Hi")))
    }

    @Test(timeout = 120_000)
    fun nextTokenAfterTheEndOfTheReplyReturnsNull() {
        val session = loadedTestModel()
        try {
            assertTrue(LlamaNative.lastError(), LlamaNative.prepare(session, conversation("Say OK.")) > 0)
            assertEquals(0, LlamaNative.startSampling(session, 0f, 1f, 0, 0L))

            val generated = generateUntilTheReplyEnds(session)

            assertTrue("no end of reply within $MAX_GENERATED_TOKENS tokens", generated < MAX_GENERATED_TOKENS)
            assertNull(LlamaNative.nextToken(session))
            assertEquals("", LlamaNative.lastError())
        } finally {
            LlamaNative.freeSession(session)
        }
    }

    @Test(timeout = 120_000)
    fun freeingASessionDuringAPrefillAbortsItWaitsForItAndDoesNotCrash() {
        val session = loadedTestModel()
        val outcome = AtomicInteger(Int.MIN_VALUE)
        val longPrompt = conversation("count the words ".repeat(CONTEXT_SIZE / 4))
        val prefill = thread(name = "in-flight prefill") { outcome.set(LlamaNative.prepare(session, longPrompt)) }
        Thread.sleep(IN_FLIGHT_HEAD_START_MS)

        val freeMs = millisToRun { LlamaNative.freeSession(session) }
        prefill.join(FREE_DEADLINE_MS)

        assertFalse("the prefill is still running after the free", prefill.isAlive)
        assertTrue("freeSession took $freeMs ms", freeMs < FREE_DEADLINE_MS)
        assertEquals(LlamaNative.ABORTED, outcome.get())
        assertEquals(LlamaNative.FAILED, LlamaNative.prepare(session, conversation("Hi")))
    }

    @Test(timeout = 120_000)
    fun freeingASessionWhileTokensAreBeingGeneratedEndsTheReplyAndDoesNotCrash() {
        val session = loadedTestModel()
        assertTrue(LlamaNative.lastError(), LlamaNative.prepare(session, conversation("Count to one thousand.")) > 0)
        assertEquals(0, LlamaNative.startSampling(session, 0f, 1f, 0, 0L))
        val generating = thread(name = "in-flight generation") { generateUntilTheReplyEnds(session) }
        Thread.sleep(IN_FLIGHT_HEAD_START_MS)

        val freeMs = millisToRun { LlamaNative.freeSession(session) }
        generating.join(FREE_DEADLINE_MS)

        assertFalse("the generation is still running after the free", generating.isAlive)
        assertTrue("freeSession took $freeMs ms", freeMs < FREE_DEADLINE_MS)
        assertNull(LlamaNative.nextToken(session))
    }

    private fun millisToRun(call: () -> Unit): Long {
        val startedAt = System.nanoTime()
        call()
        return (System.nanoTime() - startedAt) / 1_000_000
    }

    private fun generateUntilTheReplyEnds(session: Long): Int {
        repeat(MAX_GENERATED_TOKENS) { generated ->
            LlamaNative.nextToken(session) ?: return generated
        }
        return MAX_GENERATED_TOKENS
    }

    private fun loadedTestModel(): Long {
        val session = LlamaNative.loadModel(testModelPath(), CONTEXT_SIZE, THREADS)
        assertNotEquals(LlamaNative.lastError(), 0L, session)
        return session
    }

    private fun conversation(userText: String): String = JSONObject()
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
        .toString()

    private fun testModelPath(): String = requireNotNull(
        InstrumentationRegistry.getArguments().getString("turbollmTestGguf"),
    ) { "Pass -e turbollmTestGguf <path to a tiny GGUF on the device>." }

    private companion object {
        const val CONTEXT_SIZE = 2048
        const val THREADS = 4
        const val MAX_GENERATED_TOKENS = 1024
        const val UNKNOWN_SESSION = 0x7fff_0000L
        const val IN_FLIGHT_HEAD_START_MS = 200L
        const val FREE_DEADLINE_MS = 5_000L
    }
}
