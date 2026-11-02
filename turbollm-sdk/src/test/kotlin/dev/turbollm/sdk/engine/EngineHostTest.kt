package dev.turbollm.sdk.engine

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.EngineState
import dev.turbollm.sdk.LoadedModel
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.chat.GenerationGate
import dev.turbollm.sdk.internal.LoadSentinel
import dev.turbollm.sdk.testing.FakeEngine
import dev.turbollm.sdk.testing.FakeEngine.Step
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EngineHostTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val modelRoot: File by lazy { temp.newFolder("models") }
    private val sentinelFile: File by lazy { File(modelRoot, ".turbollm-sdk/loading.json") }
    private val gate = GenerationGate(maxQueuedRequests = 8)

    @Test
    fun aLoadPublishesLoadingThenReadyWithTheLoadedModel() = runTest {
        val host = hostOf(FakeEngine(loadPauseMillis = 100))
        val target = target("org/repo/a")

        val load = async { host.load(target) }
        runCurrent()
        assertEquals(EngineState.Loading(target.ref), host.state.value)
        assertTrue("sentinel during the native load", sentinelFile.exists())
        advanceUntilIdle()

        val expected = LoadedModel(
            id = "org/repo/a",
            path = target.spec.path.absolutePath,
            format = ModelFormat.GGUF,
            engine = EngineKind.LLAMA_CPP,
            contextSize = 4096,
            backend = Backend.CPU,
            notes = emptyList(),
        )
        assertEquals(expected, load.await())
        assertEquals(EngineState.Ready(expected, busy = false, queued = 0), host.state.value)
        assertFalse("sentinel after the load", sentinelFile.exists())
    }

    @Test
    fun aLoadFailurePublishesFailedRemovesTheSentinelAndClosesTheEngine() = runTest {
        val failure = TurboLLMException.ModelLoadFailed("bad magic")
        val engine = FakeEngine(loadFailure = failure)
        val host = hostOf(engine)
        val target = target("org/repo/a")

        val thrown = runCatching { host.load(target) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(EngineState.Failed(target.ref, failure), host.state.value)
        assertFalse(sentinelFile.exists())
        assertTrue(engine.closed)
    }

    @Test
    fun anEngineUnavailableOnThisDevicePublishesFailed() = runTest {
        val host = hostOf()
        val target = target("org/repo/b", ModelFormat.LITERTLM)

        val thrown = runCatching { host.load(target) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is TurboLLMException.EngineUnavailable)
        assertEquals(EngineState.Failed(target.ref, thrown as TurboLLMException), host.state.value)
    }

    @Test
    fun aSecondLoadUnloadsAndClosesTheFirstEngine() = runTest {
        val first = FakeEngine()
        val host = hostOf(first, FakeEngine())
        host.load(target("org/repo/a"))

        host.load(target("org/repo/b"))

        assertEquals(listOf("load", "unload", "close"), first.history)
        assertEquals("org/repo/b", (host.state.value as EngineState.Ready).model.id)
    }

    @Test
    fun aConcurrentSecondLoadIsRefusedAsLoadInProgress() = runTest {
        val host = hostOf(FakeEngine(loadPauseMillis = 100), FakeEngine())
        val first = target("org/repo/a")
        launch { host.load(first) }
        runCurrent()

        val thrown = runCatching { host.load(target("org/repo/b")) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is TurboLLMException.LoadInProgress)
        assertEquals(EngineState.Loading(first.ref), host.state.value)
        advanceUntilIdle()
    }

    @Test
    fun aBackgroundLoadReadsLoadingBeforeItReturnsAndBeforeTheOldModelUnloads() = runTest {
        val host = hostOf(FakeEngine(), FakeEngine(loadPauseMillis = 100))
        host.load(target("org/repo/a"))
        val second = target("org/repo/b")

        host.loadInBackground(second, this)

        assertEquals(EngineState.Loading(second.ref), host.state.value)
        advanceUntilIdle()
        assertEquals("org/repo/b", (host.state.value as EngineState.Ready).model.id)
    }

    @Test
    fun aBackgroundLoadWhileAnotherLoadRunsIsRefusedBeforeItReturns() = runTest {
        val host = hostOf(FakeEngine(loadPauseMillis = 100), FakeEngine())
        host.loadInBackground(target("org/repo/a"), this)

        val thrown = runCatching { host.loadInBackground(target("org/repo/b"), this) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is TurboLLMException.LoadInProgress)
        advanceUntilIdle()
        assertEquals("org/repo/a", (host.state.value as EngineState.Ready).model.id)
    }

    @Test
    fun aFailedBackgroundLoadIsPublishedAndNeverThrownIntoItsScope() = runTest {
        val failure = TurboLLMException.ModelLoadFailed("bad magic")
        val host = hostOf(FakeEngine(loadFailure = failure))
        val target = target("org/repo/a")

        host.loadInBackground(target, this)
        advanceUntilIdle()

        assertEquals(EngineState.Failed(target.ref, failure), host.state.value)
    }

    @Test
    fun aSentinelThatCannotBeWrittenNeverStopsTheLoad() = runTest {
        File(modelRoot, ".turbollm-sdk").writeText("a file where the sentinel folder belongs")
        val host = hostOf(FakeEngine())
        val uncaught = mutableListOf<Throwable>()

        host.loadInBackground(target("org/repo/a"), recordingScope(uncaught))
        advanceUntilIdle()

        assertEquals("org/repo/a", (host.state.value as EngineState.Ready).model.id)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun aLoadThatCannotLinkItsNativeCodeEndsFailedAndNothingEscapes() = runTest {
        val linkFailure = UnsatisfiedLinkError("no libturbollm-llama.so")
        val host = hostOf(FakeEngine(loadFailure = linkFailure))
        val target = target("org/repo/a")
        val uncaught = mutableListOf<Throwable>()

        host.loadInBackground(target, recordingScope(uncaught))
        advanceUntilIdle()

        val failure = (host.state.value as EngineState.Failed).error
        assertTrue("got $failure", failure is TurboLLMException.ModelLoadFailed)
        assertSame(linkFailure, failure.cause)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun aLoadThatThrowsAPlainExceptionIsThrownToTheCallerAsModelLoadFailed() = runTest {
        val crash = IllegalStateException("engine state")
        val host = hostOf(FakeEngine(loadFailure = crash))

        val thrown = runCatching { host.load(target("org/repo/a")) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is TurboLLMException.ModelLoadFailed)
        assertSame(crash, thrown?.cause)
    }

    @Test
    fun aBackgroundLoadInACancelledScopeStillReleasesTheLifecycle() = runTest {
        val host = hostOf(FakeEngine(loadPauseMillis = 100), FakeEngine())
        val cancelledScope = CoroutineScope(Job().apply { cancel() } + StandardTestDispatcher(testScheduler))

        host.loadInBackground(target("org/repo/a"), cancelledScope)
        advanceUntilIdle()

        assertEquals(EngineState.Idle, host.state.value)
        assertFalse("sentinel after the cancelled load", sentinelFile.exists())
        assertEquals("org/repo/b", host.load(target("org/repo/b")).id)
    }

    @Test
    fun unloadCancelsTheInFlightGenerationBeforeTheEngineUnloads() = runTest {
        val engine = FakeEngine(script = listOf(Step.Emit(EngineEvent.Text("a")), Step.Pause(10_000)))
        val host = hostOf(engine)
        host.load(target("org/repo/a"))
        val generation = startGeneration(host)
        runCurrent()

        host.unload()

        assertEquals(listOf("load", "cancelled", "unload", "close"), engine.history)
        assertTrue(generation.isCancelled)
        assertEquals(EngineState.Idle, host.state.value)
    }

    @Test
    fun aRequestQueuedBehindTheGenerationFindsTheModelUnloaded() = runTest {
        val engine = FakeEngine(script = listOf(Step.Pause(10_000)))
        val host = hostOf(engine)
        host.load(target("org/repo/a"))
        startGeneration(host)
        runCurrent()
        val queued = async { runCatching { gate.withPermit { host.withLoaded { "ran" } } }.exceptionOrNull() }
        runCurrent()

        host.unload()

        assertTrue("got ${queued.await()}", queued.await() is TurboLLMException.ModelNotLoaded)
    }

    @Test
    fun withLoadedWhenIdleFailsFastWithModelNotLoaded() = runTest {
        val thrown = runCatching { hostOf().withLoaded { "ran" } }.exceptionOrNull()

        assertTrue("got $thrown", thrown is TurboLLMException.ModelNotLoaded)
    }

    @Test
    fun withLoadedHandsTheBlockTheEngineAndItsModel() = runTest {
        val engine = FakeEngine()
        val host = hostOf(engine)
        host.load(target("org/repo/a"))

        val seen = host.withLoaded { it.engine to it.model.id }

        assertEquals(engine to "org/repo/a", seen)
    }

    @Test
    fun unloadingWhenIdleStaysIdle() = runTest {
        val host = hostOf()

        host.unload()

        assertEquals(EngineState.Idle, host.state.value)
    }

    private fun hostOf(vararg llamaEngines: FakeEngine): EngineHost {
        val pending = ArrayDeque(llamaEngines.toList())
        val registry = EngineRegistry(
            llamaCpp = { pending.removeFirst() },
            liteRtLm = { throw TurboLLMException.EngineUnavailable(EngineKind.LITERT_LM) },
        )
        return EngineHost(registry, gate, LoadSentinel(modelRoot))
    }

    private fun TestScope.recordingScope(uncaught: MutableList<Throwable>) = CoroutineScope(
        SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> uncaught += e },
    )

    private fun target(id: String, format: ModelFormat = ModelFormat.GGUF) = LoadTarget(
        ref = ModelRef.Installed(id),
        id = id,
        format = format,
        spec = LoadSpec(
            path = File(modelRoot, "$id.gguf"),
            contextSize = null,
            threads = null,
            backend = Backend.AUTO,
            cacheDir = File(modelRoot, "cache"),
        ),
    )

    private fun TestScope.startGeneration(host: EngineHost): Job = launch {
        gate.withPermit { host.withLoaded { it.engine.generate(emptyList(), noParams).collect() } }
    }

    private val noParams = GenerationParams(maxTokens = null, temperature = null, topP = null, topK = null, seed = null)
}
