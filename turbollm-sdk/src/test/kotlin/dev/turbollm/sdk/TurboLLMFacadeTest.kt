package dev.turbollm.sdk

import dev.turbollm.sdk.api.ChatCompletionRequest
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineFactory
import dev.turbollm.sdk.engine.EngineRegistry
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.internal.LoadSentinel
import dev.turbollm.sdk.internal.Logger
import dev.turbollm.sdk.models.HttpFetcher
import dev.turbollm.sdk.models.HttpResponse
import dev.turbollm.sdk.server.HttpTransport
import dev.turbollm.sdk.testing.FakeEngine
import dev.turbollm.sdk.testing.FakeEngine.Step
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val MODEL_ID = "m"
private const val ONE_MIB = 1 shl 20

/** The port a [RecordingTransport] reports as bound. Nothing binds it. */
private const val PRETEND_BOUND_PORT = 6997

@OptIn(ExperimentalCoroutinesApi::class)
class TurboLLMFacadeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val transport = RecordingTransport()
    private var enginesCreated = 0
    private var engine = FakeEngine(listOf(Step.Emit(EngineEvent.Text("hi")), done()))

    private val config: TurboLLMConfig get() = TurboLLMConfig(modelRoot = folder.root)
    private var scheduler: TestCoroutineScheduler? = null

    /**
     * Lets the closed core's unload finish on this test's scheduler, as a live dispatcher would: the next test's
     * core waits for it before any load.
     */
    @After
    fun closeTheLiveCore() {
        TurboLLM.currentOrNull()?.close()
        scheduler?.advanceUntilIdle()
    }

    @Test
    fun anEqualConfigGetsTheSameInstance() = runTest {
        val first = create()

        assertSame(first, create(config.copy()))
        assertSame(first, TurboLLM.currentOrNull())
    }

    @Test
    fun aConfigThatDiffersFromTheLiveOneIsRefused() = runTest {
        create()

        assertThrows(IllegalStateException::class.java) { create(config.copy(modelRoot = folder.newFolder())) }
    }

    @Test
    fun creatingTheCoreLoadsNothingAndWritesNothing() = runTest {
        create()
        runCurrent()

        assertEquals(0, enginesCreated)
        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
        assertEquals(EngineState.Idle, TurboLLM.currentOrNull()!!.state.value)
    }

    @Test
    fun closeStopsTheServer() = runTest {
        val core = create()
        core.startServer()

        core.close()

        assertTrue(transport.stopped)
        assertNull(core.server.value)
    }

    @Test
    fun closeUnloadsTheModel() = runTest {
        val core = loadedCore()

        core.close()
        advanceUntilIdle()

        assertEquals(1, engine.unloads)
        assertTrue(engine.closed)
    }

    /** runCurrent, not advanceUntilIdle: a download left running ticks its speed forever in virtual time. */
    @Test
    fun closePausesTheRunningDownloads() = runTest {
        val core = create(fetcher = NeverAnsweringFetcher())
        core.models.download("owner/repo", "model.gguf")
        runCurrent()
        assertEquals(DownloadState.RUNNING, core.models.downloads.value.single().state)

        core.close()
        runCurrent()

        assertEquals(DownloadState.PAUSED, core.models.downloads.value.single().state)
    }

    @Test
    fun aClosedCoreIsReplacedByAFreshOne() = runTest {
        val closed = create()
        closed.close()

        assertNull(TurboLLM.currentOrNull())
        assertNotSame(closed, create())
    }

    @Test
    fun chatStreamRunsNothingUntilItIsCollected() = runTest {
        val core = loadedCore()

        val stream = core.chatStream(request())
        advanceUntilIdle()
        assertTrue(engine.generations.isEmpty())

        val chunks = stream.toList()
        assertEquals(1, engine.generations.size)
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun startingTheServerTwiceReturnsTheSameHandle() = runTest {
        val core = create()

        val first = core.startServer()
        val second = core.startServer()

        assertSame(first, second)
        assertEquals(1, transport.starts)
        assertEquals(first, core.server.value)
    }

    @Test
    fun theServerServesTheControlAndOpenAiRoutes() = runTest {
        val token = create().startServer(ServerOptions(token = "facade-token")).token

        testApplication {
            application(transport.module)
            val status = client.get("/sdk/v1/status") { addressedWith(token) }
            val models = client.get("/v1/models") { addressedWith(token) }

            assertEquals(HttpStatusCode.OK, status.status)
            assertTrue(status.bodyAsText(), "\"idle\"" in status.bodyAsText())
            assertEquals(HttpStatusCode.OK, models.status)
        }
    }

    @Test
    fun anInterruptedLoadIsReportedOnceByTheNextCore() = runTest {
        val model = gguf("$MODEL_ID.gguf")
        LoadSentinel(folder.root).write(model)

        val first = create()
        assertEquals(model.absolutePath, first.interruptedLoad)
        first.close()

        assertNull(create().interruptedLoad)
    }

    @Test
    fun noInterruptedLoadWhenTheLastLoadFinished() = runTest {
        assertNull(create().interruptedLoad)
    }

    /**
     * C-6: close() returns before its unload ends. The next core must neither read the closing core's live
     * sentinel as a crash nor load its own model while the closing core's model is still resident.
     */
    @Test
    fun aCoreCreatedWhileTheClosedOneStillLoadsWaitsForItsUnloadAndReportsNoInterruptedLoad() =
        runTest(timeout = 10.seconds) {
            val closingLoad = CompletableDeferred<Unit>()
            val closingEngine = FakeEngine(listOf(done()), loadGate = closingLoad)
            val nextEngine = FakeEngine(listOf(done()), loadGate = CompletableDeferred())
            gguf("x.gguf")
            val y = gguf("y.gguf")
            engine = closingEngine
            val closing = create()
            backgroundScope.launch { closing.load(ModelRef.Installed("x")) }
            closing.state.first { it is EngineState.Loading }
            runCurrent()

            closing.close()
            engine = nextEngine
            val next = create()
            backgroundScope.launch { next.load(ModelRef.Installed("y")) }
            next.state.first { it is EngineState.Loading }
            runCurrent()

            assertNull(next.interruptedLoad)
            assertTrue("the next load began while the closed model was resident", nextEngine.loads.isEmpty())
            closingLoad.complete(Unit)
            runCurrent()
            assertEquals(listOf("load", "unload", "close"), closingEngine.history)
            assertEquals(1, nextEngine.loads.size)
            assertEquals(y.canonicalPath, LoadSentinel(folder.root).consumeStale())
        }

    @Test
    fun aCoreClosedBeforeItLoadsStillPassesTheEarlierUnloadOnToTheNextCore() = runTest(timeout = 10.seconds) {
        val firstLoad = CompletableDeferred<Unit>()
        val firstEngine = FakeEngine(listOf(done()), loadGate = firstLoad)
        val thirdEngine = FakeEngine(listOf(done()))
        gguf("x.gguf")
        gguf("y.gguf")
        engine = firstEngine
        val first = create()
        backgroundScope.launch { first.load(ModelRef.Installed("x")) }
        first.state.first { it is EngineState.Loading }
        runCurrent()

        first.close()
        create().close()
        engine = thirdEngine
        val third = create()
        backgroundScope.launch { third.load(ModelRef.Installed("y")) }
        third.state.first { it is EngineState.Loading }
        runCurrent()

        assertTrue("the third load began while the first model was resident", thirdEngine.loads.isEmpty())
        firstLoad.complete(Unit)
        runCurrent()
        assertEquals(1, thirdEngine.loads.size)
    }

    /** G-9: a Quit kills the process mid-load, so the load's own cleanup never runs. The next process must see it. */
    @Test
    fun aLoadTheProcessEndsDuringIsLeftForTheNextProcessAsInterrupted() = runTest(timeout = 10.seconds) {
        engine = FakeEngine(listOf(done()), loadPauseMillis = 60_000)
        val model = gguf("$MODEL_ID.gguf")
        val core = create()

        backgroundScope.launch { core.load(ModelRef.Installed(MODEL_ID)) }
        core.state.first { it is EngineState.Loading }
        runCurrent()

        assertEquals(model.canonicalPath, LoadSentinel(folder.root).consumeStale())
    }

    @Test
    fun stateShowsAGenerationInFlightAsBusyAndAWaitingOneAsQueued() = runTest {
        engine = FakeEngine(listOf(Step.Pause(60_000), done()))
        val core = loadedCore()

        val first = backgroundScope.launch { core.chat(request()) }
        val second = backgroundScope.launch { core.chat(request()) }
        runCurrent()
        assertEquals(true to 1, core.state.value.occupancy())

        first.cancel()
        second.cancel()
        runCurrent()
        assertEquals(false to 0, core.state.value.occupancy())
    }

    @Test
    fun stateShowsTheModelFreeAgainOnceItsOnlyGenerationEnds() = runTest {
        engine = FakeEngine(listOf(Step.Pause(1_000), done()))
        val core = loadedCore()

        backgroundScope.launch { core.chat(request()) }
        runCurrent()
        assertEquals(true to 0, core.state.value.occupancy())

        advanceTimeBy(2_000)
        assertEquals(false to 0, core.state.value.occupancy())
    }

    private suspend fun TestScope.loadedCore(): TurboLLM {
        gguf("$MODEL_ID.gguf")
        return create().also { it.load(ModelRef.Installed(MODEL_ID)) }
    }

    private fun TestScope.create(
        config: TurboLLMConfig = this@TurboLLMFacadeTest.config,
        fetcher: HttpFetcher = NeverAnsweringFetcher(),
    ): TurboLLM {
        scheduler = testScheduler
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dependencies = TurboLLMDependencies(
            engines = EngineRegistry(llamaCpp = EngineFactory(::nextEngine), liteRtLm = EngineFactory(::nextEngine)),
            fetcher = fetcher,
            transport = transport,
            logger = Logger {},
            clock = Clock { testScheduler.currentTime },
            coreDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            freeBytes = { Long.MAX_VALUE },
            defaultCacheDir = File(folder.root, ".cache"),
        )
        return TurboLLM.createForTest(config, dependencies)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.addressedWith(token: String) {
        header(HttpHeaders.Host, "127.0.0.1:$PRETEND_BOUND_PORT")
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun nextEngine(): FakeEngine {
        enginesCreated++
        return engine
    }

    private fun gguf(relative: String): File {
        val file = File(folder.root, relative)
        file.outputStream().use { out ->
            out.write("GGUF".toByteArray(Charsets.US_ASCII))
            out.write(ByteArray(ONE_MIB))
        }
        return file
    }

    private fun request() = ChatCompletionRequest(
        model = MODEL_ID,
        messages = listOf(ChatMessage(ChatRole.USER, MessageContent.Text("hi"))),
    )

    private fun done() = Step.Emit(EngineEvent.Done(FinishReason.STOP, 1, 1, 1.0, 1.0))

    private fun EngineState.occupancy(): Pair<Boolean, Int> {
        val ready = this as EngineState.Ready
        return ready.busy to ready.queued
    }

    /** Holds every request open until it is cancelled, so a download stays RUNNING. */
    private class NeverAnsweringFetcher : HttpFetcher {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = awaitCancellation()
    }

    private class RecordingTransport : HttpTransport {
        var starts = 0
        var stopped = false
        var module: Application.() -> Unit = {}

        override suspend fun start(port: Int, module: Application.() -> Unit): Int {
            starts++
            this.module = module
            return PRETEND_BOUND_PORT
        }

        override fun stop() {
            stopped = true
        }
    }
}
