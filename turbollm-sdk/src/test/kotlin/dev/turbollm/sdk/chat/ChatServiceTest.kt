package dev.turbollm.sdk.chat

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.LoadOptions
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.api.ChatCompletionMessage
import dev.turbollm.sdk.api.ChatCompletionRequest
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.ChunkDelta
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.api.StreamOptions
import dev.turbollm.sdk.api.Timings
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.api.Usage
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineHost
import dev.turbollm.sdk.engine.EngineRegistry
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.engine.LoadTarget
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.internal.LoadSentinel
import dev.turbollm.sdk.models.LoadTargets
import dev.turbollm.sdk.models.ModelStore
import dev.turbollm.sdk.testing.FakeEngine
import dev.turbollm.sdk.testing.FakeEngine.Step
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: File by lazy { temp.newFolder("models") }
    private val gate = GenerationGate(maxQueuedRequests = 1)
    private var llamaEngine = FakeEngine()
    private var liteRtEngine = FakeEngine(kind = EngineKind.LITERT_LM)
    private val host by lazy {
        val registry = EngineRegistry(llamaCpp = { llamaEngine }, liteRtLm = { liteRtEngine })
        EngineHost(registry, gate, LoadSentinel(root))
    }
    private val store by lazy { ModelStore(root, Clock { 0 }, loadedModelId = { null }) }

    @Test
    fun aStreamSendsTheRoleThenTheTextThenTheFinishWithTimingsThenTheUsage() = runTest {
        val service = loadedService(
            Step.Emit(EngineEvent.Reasoning("think")),
            Step.Emit(EngineEvent.Text("Hel")),
            Step.Emit(EngineEvent.Text("lo")),
            Step.Emit(EngineEvent.Done(FinishReason.STOP, 7, 3, 10.0, 30.0)),
        )

        val chunks = service.stream(request(includeUsage = true)).toList()

        assertEquals(
            listOf(
                ChunkDelta(role = ChatRole.ASSISTANT),
                ChunkDelta(reasoningContent = "think"),
                ChunkDelta(content = "Hel"),
                ChunkDelta(content = "lo"),
                ChunkDelta(),
            ),
            chunks.dropLast(1).map { it.choices.single().delta },
        )
        val finish = chunks[chunks.size - 2]
        assertEquals(FinishReason.STOP, finish.choices.single().finishReason)
        assertEquals(Timings(7, 10.0, 3, 30.0, 100.0), finish.timings)
        val usageChunk = chunks.last()
        assertEquals(emptyList<Any>(), usageChunk.choices)
        assertEquals(Usage(7, 3, 10), usageChunk.usage)
        assertEquals(setOf(MODEL_ID), chunks.map { it.model }.toSet())
        assertEquals(1, chunks.map { it.id }.toSet().size)
        assertEquals(setOf(CREATED), chunks.map { it.created }.toSet())
        assertTrue(chunks.first().id.startsWith("chatcmpl-"))
    }

    @Test
    fun withoutIncludeUsageTheStreamEndsAtTheFinishChunk() = runTest {
        val service = loadedService(Step.Emit(EngineEvent.Text("hi")), done(FinishReason.STOP))

        val chunks = service.stream(request()).toList()

        assertEquals(FinishReason.STOP, chunks.last().choices.single().finishReason)
        assertTrue(chunks.none { it.usage != null })
    }

    @Test
    fun aStopSplitAcrossChunksCutsTheTextFinishesWithStopAndCancelsTheEngine() = runTest {
        val service = loadedService(
            Step.Emit(EngineEvent.Text("hel")),
            Step.Emit(EngineEvent.Text("lo ST")),
            Step.Emit(EngineEvent.Text("OP wor")),
            Step.Emit(EngineEvent.Text("ld")),
            done(FinishReason.LENGTH),
        )

        val chunks = service.stream(request(stop = listOf("STOP"))).toList()

        assertEquals("hello ", chunks.mapNotNull { it.choices.singleOrNull()?.delta?.content }.joinToString(""))
        assertEquals(FinishReason.STOP, chunks.last().choices.single().finishReason)
        assertTrue(llamaEngine.cancelled)
    }

    @Test
    fun stopsNeverApplyToReasoning() = runTest {
        val service = loadedService(
            Step.Emit(EngineEvent.Reasoning("say STOP")),
            Step.Emit(EngineEvent.Text("fine")),
            done(FinishReason.LENGTH),
        )

        val reply = service.complete(request(stop = listOf("STOP")))

        assertEquals(ChatCompletionMessage(content = "fine", reasoningContent = "say STOP"), reply.choices.single().message)
        assertEquals(FinishReason.LENGTH, reply.choices.single().finishReason)
        assertFalse(llamaEngine.cancelled)
    }

    @Test
    fun aHeldBackTailIsReleasedWhenTheEngineFinishes() = runTest {
        val service = loadedService(Step.Emit(EngineEvent.Text("end ST")), done(FinishReason.STOP))

        assertEquals("end ST", service.complete(request(stop = listOf("STOP"))).choices.single().message.content)
    }

    @Test
    fun anEngineLengthFinishIsReportedAsLength() = runTest {
        val service = loadedService(Step.Emit(EngineEvent.Text("a")), done(FinishReason.LENGTH))

        val chunks = service.stream(request()).toList()

        assertEquals(FinishReason.LENGTH, chunks.last().choices.single().finishReason)
    }

    @Test
    fun aCompletionJoinsTheContentAndTheReasoningAndReportsTheEngineCounts() = runTest {
        val service = loadedService(
            Step.Emit(EngineEvent.Reasoning("Let me ")),
            Step.Emit(EngineEvent.Reasoning("think.")),
            Step.Emit(EngineEvent.Text("Hello")),
            Step.Emit(EngineEvent.Text(", world")),
            Step.Emit(EngineEvent.Done(FinishReason.STOP, 12, 4, 20.0, 40.0)),
        )

        val reply = service.complete(request())

        assertEquals("chat.completion", reply.objectType)
        assertEquals(MODEL_ID, reply.model)
        assertEquals(CREATED, reply.created)
        assertEquals(ChatCompletionMessage(content = "Hello, world", reasoningContent = "Let me think."), reply.choices.single().message)
        assertEquals(FinishReason.STOP, reply.choices.single().finishReason)
        assertEquals(Usage(12, 4, 16), reply.usage)
        assertEquals(Timings(12, 20.0, 4, 40.0, 100.0), reply.timings)
    }

    @Test
    fun aReplyWithoutReasoningHasNoReasoningContent() = runTest {
        val service = loadedService(Step.Emit(EngineEvent.Text("hi")), done(FinishReason.STOP))

        assertNull(service.complete(request()).choices.single().message.reasoningContent)
    }

    @Test
    fun theEngineReceivesTheSmallerMaxTokens() = runTest {
        val service = loadedService(done(FinishReason.LENGTH))

        service.complete(request().copy(maxTokens = 64, maxCompletionTokens = 32))

        assertEquals(32, llamaEngine.generations.single().maxTokens)
    }

    @Test
    fun aCollectorThatStopsEarlyCancelsTheEngineAndReleasesTheGate() = runTest {
        val service = loadedService(
            Step.Emit(EngineEvent.Text("a")),
            Step.Pause(1_000),
            Step.Emit(EngineEvent.Text("b")),
            done(FinishReason.STOP),
        )

        val chunks = service.stream(request()).take(2).toList()

        assertEquals(2, chunks.size)
        assertTrue(llamaEngine.cancelled)
        assertFalse(gate.busy)
    }

    @Test
    fun aRequestBeyondTheQueueLimitIsRefusedAsServerBusy() = runTest {
        val service = loadedService(Step.Pause(1_000), done(FinishReason.STOP))
        val running = async { service.complete(request()) }
        val queued = async { service.complete(request()) }
        runCurrent()

        val refused = runCatching { service.complete(request()) }.exceptionOrNull()

        assertTrue(refused is TurboLLMException.ServerBusy)
        advanceUntilIdle()
        running.await()
        queued.await()
    }

    @Test
    fun anUnloadDuringAStreamEndsItWithCancellationNotAnEngineFailure() = runTest {
        val service = loadedService(Step.Emit(EngineEvent.Text("a")), Step.Pause(1_000), done(FinishReason.STOP))
        val streaming = async { runCatching { service.stream(request()).toList() }.exceptionOrNull() }
        runCurrent()

        host.unload()

        val ended = streaming.await()
        assertTrue("ended with $ended", ended is CancellationException)
        assertFalse(ended is TurboLLMException)
        assertTrue(llamaEngine.cancelled)
    }

    @Test
    fun anEngineErrorMidStreamIsReportedAsAnEngineFailure() = runTest {
        val service = loadedService(Step.Emit(EngineEvent.Text("a")), Step.Fail(IllegalStateException("native")))

        val error = runCatching { service.stream(request()).toList() }.exceptionOrNull()

        assertTrue("got $error", error is TurboLLMException.EngineFailure)
        assertFalse(gate.busy)
    }

    @Test
    fun aCancellationRaisedByTheEngineIsNeverWrappedAsAnEngineFailure() = runTest {
        val service = loadedService(Step.Emit(EngineEvent.Text("a")), Step.Fail(CancellationException("engine cancelled")))

        val error = runCatching { service.stream(request()).toList() }.exceptionOrNull()

        assertTrue("got $error", error is CancellationException)
        assertFalse(error is TurboLLMException)
    }

    @Test
    fun aRefusedRequestNeverReachesTheEngine() = runTest {
        val service = loadedService(done(FinishReason.STOP))
        val withTools = request().copy(tools = buildJsonArray { add(buildJsonObject { put("type", JsonPrimitive("function")) }) })

        val error = runCatching { service.complete(withTools) }.exceptionOrNull()

        assertEquals("tools", (error as TurboLLMException.InvalidRequest).param)
        assertTrue(llamaEngine.generations.isEmpty())
    }

    @Test
    fun theLoadedModelIsFoundByItsAbsolutePath() = runTest {
        val service = loadedService(Step.Emit(EngineEvent.Text("ok")), done(FinishReason.STOP))

        val reply = service.complete(request(model = File(root, "$MODEL_ID.gguf").absolutePath))

        assertEquals("ok", reply.choices.single().message.content)
    }

    @Test
    fun theLoadedModelIsFoundByAPathThroughALinkedFolder() = runTest {
        val real = temp.newFolder("real")
        gguf(File(real, "models/o/r/m.gguf"))
        val link = File(temp.root, "link")
        assumeTrue("this platform cannot link folders", linkFolder(link, real))
        val linkedPath = File(link, "models/o/r/m.gguf").absolutePath
        val targets = LoadTargets(store, root, allowLoadByPath = true, cacheDir = File(root, "cache"))
        llamaEngine = FakeEngine(listOf(Step.Emit(EngineEvent.Text("ok")), done(FinishReason.STOP)))
        host.load(targets.of(ModelRef.File(linkedPath), LoadOptions()))

        val reply = service(autoLoadOnRequest = false).complete(request(model = linkedPath))

        assertEquals("ok", reply.choices.single().message.content)
    }

    @Test
    fun anUnknownModelIsNotFound() = runTest {
        val service = loadedService(done(FinishReason.STOP))

        val error = runCatching { service.complete(request(model = "org/repo/missing")) }.exceptionOrNull()

        assertTrue("got $error", error is TurboLLMException.ModelNotFound)
    }

    @Test
    fun anAbsolutePathToNoFileIsNotFound() = runTest {
        val service = loadedService(done(FinishReason.STOP))

        val error = runCatching { service.complete(request(model = File(root, "missing.gguf").absolutePath)) }
            .exceptionOrNull()

        assertTrue("got $error", error is TurboLLMException.ModelNotFound)
    }

    @Test
    fun anInstalledButUnloadedModelIsNotLoadedWhenAutoloadIsOff() = runTest {
        gguf("$MODEL_ID.gguf")
        val service = service(autoLoadOnRequest = false)

        val error = runCatching { service.complete(request()) }.exceptionOrNull()

        assertTrue("got $error", error is TurboLLMException.ModelNotLoaded)
        assertTrue(llamaEngine.loads.isEmpty())
    }

    @Test
    fun anInstalledModelIsNotLoadedWhileAnotherLoadRunsWithAutoloadOff() = runTest {
        gguf("$MODEL_ID.gguf")
        llamaEngine = FakeEngine(loadPauseMillis = 1_000)
        val loading = async { host.load(target(MODEL_ID)) }
        runCurrent()

        val error = runCatching { service(autoLoadOnRequest = false).complete(request()) }.exceptionOrNull()

        assertTrue("got $error", error is TurboLLMException.ModelNotLoaded)
        assertEquals(1, llamaEngine.loads.size)
        loading.await()
    }

    @Test
    fun anInstalledModelIsLoadedOnRequestWhenAutoloadIsOn() = runTest {
        val file = gguf("$MODEL_ID.gguf")
        llamaEngine = FakeEngine(listOf(Step.Emit(EngineEvent.Text("loaded")), done(FinishReason.STOP)))

        val reply = service(autoLoadOnRequest = true).complete(request())

        assertEquals("loaded", reply.choices.single().message.content)
        val spec = llamaEngine.loads.single()
        assertEquals(file.absolutePath, spec.path.absolutePath)
        assertEquals(Backend.AUTO, spec.backend)
        assertEquals(File(root, "cache"), spec.cacheDir)
    }

    @Test
    fun anIncompleteSplitModelIsNeverLoaded() = runTest {
        gguf("org/repo/Big-00001-of-00002.gguf")

        val error = runCatching { service(autoLoadOnRequest = true).complete(request(model = "org/repo/Big")) }.exceptionOrNull()

        assertTrue("got $error", error is TurboLLMException.ModelNotFound)
        assertTrue(llamaEngine.loads.isEmpty())
    }

    @Test
    fun aLiteRtEngineReceivesTheThinkingParamsAndStillAnswers() = runTest {
        litertlm("org/lite/gemma.litertlm")
        liteRtEngine = FakeEngine(listOf(Step.Emit(EngineEvent.Text("hi")), done(FinishReason.STOP)), kind = EngineKind.LITERT_LM)
        val kwargs = buildJsonObject {
            put("enable_thinking", JsonPrimitive(false))
            put("reasoning_effort", JsonPrimitive("low"))
        }

        val reply = service(autoLoadOnRequest = true)
            .complete(request(model = "org/lite/gemma").copy(chatTemplateKwargs = kwargs))

        assertEquals("hi", reply.choices.single().message.content)
        val params = liteRtEngine.generations.single()
        assertFalse(params.enableThinking)
        assertEquals(mapOf("reasoning_effort" to "\"low\""), params.templateKwargs)
    }

    private suspend fun loadedService(vararg script: Step): ChatService {
        llamaEngine = FakeEngine(script.toList())
        host.load(target(MODEL_ID))
        return service(autoLoadOnRequest = false)
    }

    private fun service(autoLoadOnRequest: Boolean): ChatService {
        val resolver = ModelResolver(store, host, autoLoadOnRequest, cacheDir = File(root, "cache"))
        return ChatService(resolver, gate, host, epochSeconds = { CREATED })
    }

    private fun target(id: String) = LoadTarget(
        ref = ModelRef.Installed(id),
        id = id,
        format = ModelFormat.GGUF,
        spec = LoadSpec(File(root, "$id.gguf"), contextSize = null, threads = null, Backend.AUTO, File(root, "cache")),
    )

    private fun request(
        model: String = MODEL_ID,
        stop: List<String>? = null,
        includeUsage: Boolean = false,
    ) = ChatCompletionRequest(
        model = model,
        messages = listOf(ChatMessage(ChatRole.USER, MessageContent.Text("hi"))),
        stop = stop,
        streamOptions = if (includeUsage) StreamOptions(includeUsage = true) else null,
    )

    private fun done(reason: FinishReason) = Step.Emit(EngineEvent.Done(reason, 1, 1, 1.0, 1.0))

    private fun gguf(relative: String) = gguf(File(root, relative))

    private fun gguf(file: File) = write(file, "GGUF", ONE_MIB)

    private fun litertlm(relative: String) = write(File(root, relative), "LITERTLM", 64)

    private fun write(file: File, magic: String, size: Int): File {
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { out ->
            val magicBytes = magic.toByteArray(Charsets.US_ASCII)
            out.write(magicBytes)
            out.write(ByteArray(size - magicBytes.size))
        }
        return file
    }

    private fun linkFolder(link: File, target: File): Boolean =
        runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess ||
            junctionOnWindows(link, target)

    /** Windows without Developer Mode refuses symlinks, but any user may make a junction, and toRealPath resolves it. */
    private fun junctionOnWindows(link: File, target: File): Boolean {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
        val mklink = ProcessBuilder("cmd", "/c", "mklink", "/J", link.path, target.path).redirectErrorStream(true).start()
        return mklink.waitFor(30, TimeUnit.SECONDS) && mklink.exitValue() == 0
    }

    private companion object {
        const val MODEL_ID = "org/repo/model-Q4_K_M"
        const val CREATED = 1_730_246_400L
        const val ONE_MIB = 1024 * 1024
    }
}
