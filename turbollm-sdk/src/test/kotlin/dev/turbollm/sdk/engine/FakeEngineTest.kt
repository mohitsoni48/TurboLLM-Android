package dev.turbollm.sdk.engine

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.testing.FakeEngine
import dev.turbollm.sdk.testing.FakeEngine.Step
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeEngineTest {

    private val done = EngineEvent.Done(
        reason = FinishReason.STOP,
        promptTokens = 4,
        completionTokens = 2,
        promptMs = 10.0,
        genMs = 20.0,
    )

    @Test
    fun emitsTheScriptedEventsInOrderAfterTheirVirtualDelays() = runTest {
        val engine = FakeEngine(
            script = listOf(
                Step.Emit(EngineEvent.Reasoning("hmm")),
                Step.Pause(100),
                Step.Emit(EngineEvent.Text("hi")),
                Step.Pause(50),
                Step.Emit(done),
            ),
        )

        val events = engine.generate(emptyList(), noParams).toList()

        assertEquals(listOf(EngineEvent.Reasoning("hmm"), EngineEvent.Text("hi"), done), events)
        assertEquals(150, currentTime)
        assertFalse(engine.cancelled)
    }

    @Test
    fun recordsEachLoadAndReturnsTheScriptedModelInfo() = runTest {
        val info = EngineModelInfo(contextSize = 2048, trainedContext = 8192, backend = Backend.CPU, notes = listOf("n"))
        val engine = FakeEngine(modelInfo = info)

        val loaded = engine.load(spec)

        assertSame(info, loaded)
        assertEquals(listOf(spec), engine.loads)
    }

    @Test
    fun recordsEachUnload() = runTest {
        val engine = FakeEngine()

        engine.unload()
        engine.unload()

        assertEquals(2, engine.unloads)
    }

    @Test
    fun aScriptedLoadFailureIsThrownAfterTheLoadIsRecorded() = runTest {
        val failure = TurboLLMException.ModelLoadFailed("bad magic")
        val engine = FakeEngine(loadFailure = failure)

        val thrown = runCatching { engine.load(spec) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf(spec), engine.loads)
    }

    @Test
    fun cancellingTheCollectorMidStreamMarksTheEngineCancelled() = runTest {
        val engine = FakeEngine(script = listOf(Step.Emit(EngineEvent.Text("a")), Step.Pause(1_000), Step.Emit(done)))
        val collector = launch { engine.generate(emptyList(), noParams).collect() }

        advanceTimeBy(500)
        collector.cancel()
        collector.join()

        assertTrue(engine.cancelled)
    }

    @Test
    fun aCollectorThatStopsEarlyMarksTheEngineCancelled() = runTest {
        val engine = FakeEngine(script = listOf(Step.Emit(EngineEvent.Text("a")), Step.Emit(EngineEvent.Text("b"))))

        engine.generate(emptyList(), noParams).take(1).toList()

        assertTrue(engine.cancelled)
    }

    @Test
    fun reportsTheEngineKindItWasGiven() {
        assertEquals(EngineKind.LITERT_LM, FakeEngine(kind = EngineKind.LITERT_LM).kind)
    }

    private val noParams = GenerationParams(maxTokens = null, temperature = null, topP = null, topK = null, seed = null)

    private val spec = LoadSpec(
        path = File("model.gguf"),
        contextSize = null,
        threads = null,
        backend = Backend.AUTO,
        cacheDir = File("cache"),
    )
}
