package dev.turbollm.sdk.server

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineKind
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.api.ChatCompletionRequest
import dev.turbollm.sdk.api.ChatMessage
import dev.turbollm.sdk.api.ChatRole
import dev.turbollm.sdk.api.MessageContent
import dev.turbollm.sdk.chat.ChatService
import dev.turbollm.sdk.chat.GenerationGate
import dev.turbollm.sdk.chat.ModelResolver
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineHost
import dev.turbollm.sdk.engine.EngineRegistry
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.engine.LoadTarget
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.internal.LoadSentinel
import dev.turbollm.sdk.models.ModelStore
import dev.turbollm.sdk.testing.FakeEngine
import dev.turbollm.sdk.testing.FakeEngine.Step
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/** The port the policy believes it is bound to. Nothing binds it: `testApplication` is in-memory. */
private const val PRETEND_BOUND_PORT = 6997
private const val MODEL_ID = "org/repo/model-Q4_K_M"
private const val CREATED = 1_730_246_400L
private const val ONE_MIB = 1024 * 1024
private const val DONE_FRAME = "data: [DONE]\n\n"
private val COMPLETION_ID = Regex("chatcmpl-[0-9a-f]{24}")

/** These tests assert exact frames; a client leaving is covered over a real socket in ClientDisconnectOverSocketTest. */
private val NO_LIVENESS_PROBES = Duration.INFINITE

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiRoutesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: File by lazy { temp.newFolder("models") }
    private val outsideRoot: File by lazy { temp.newFolder("elsewhere") }
    private val gate = GenerationGate(maxQueuedRequests = 1)
    private var engine = FakeEngine()
    private val host by lazy {
        EngineHost(EngineRegistry(llamaCpp = { engine }, liteRtLm = { engine }), gate, LoadSentinel(root))
    }
    private val store by lazy { ModelStore(root, Clock { 0 }, loadedModelId = { null }) }
    private val chat by lazy {
        val resolver = ModelResolver(store, host, autoLoadOnRequest = false, cacheDir = File(root, "cache"))
        ChatService(resolver, gate, host, epochSeconds = { CREATED })
    }

    @Test
    fun aThreeTokenStreamIsFramedExactlyAsServerSentEvents() = routesTest(
        Step.Emit(EngineEvent.Text("a")),
        Step.Emit(EngineEvent.Text("b")),
        Step.Emit(EngineEvent.Text("c")),
        Step.Emit(EngineEvent.Done(FinishReason.STOP, 7, 3, 10.0, 30.0)),
    ) {
        val response = chatRequest(streaming = true)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/event-stream", response.headers[HttpHeaders.ContentType])
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        val expected = frame(roleChunk()) + frame(contentChunk("a")) + frame(contentChunk("b")) +
            frame(contentChunk("c")) + frame(finishChunk()) + DONE_FRAME
        assertEquals(expected, withOneCompletionId(response.bodyAsText()))
    }

    @Test
    fun includeUsageAddsAChunkWithNoChoicesBeforeDone() = routesTest(
        Step.Emit(EngineEvent.Text("a")),
        Step.Emit(EngineEvent.Done(FinishReason.STOP, 7, 3, 10.0, 30.0)),
    ) {
        val response = chatRequest(streaming = true, extraFields = """"stream_options":{"include_usage":true},""")

        val usageChunk = chunkPrefix() + """"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":3,""" +
            """"total_tokens":10}}"""
        val body = withOneCompletionId(response.bodyAsText())
        assertTrue(body, body.endsWith(frame(finishChunk()) + frame(usageChunk) + DONE_FRAME))
    }

    @Test
    fun anErrorAfterTheHeadersIsSentAsAnErrorEventThenDone() = routesTest(
        Step.Emit(EngineEvent.Text("a")),
        Step.Fail(IllegalStateException("boom")),
    ) {
        val response = chatRequest(streaming = true)

        assertEquals(HttpStatusCode.OK, response.status)
        val errorEvent = """{"error":{"message":"boom","type":"server_error","param":null,"code":"engine_error"}}"""
        val expected = frame(roleChunk()) + frame(contentChunk("a")) + frame(errorEvent) + DONE_FRAME
        assertEquals(expected, withOneCompletionId(response.bodyAsText()))
    }

    @Test
    fun aStreamingRequestThatFailsBeforeItsFirstChunkIsAnsweredWithAStatus() = routesTest {
        val response = chatRequest(streaming = true, model = "org/repo/missing")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("model_not_found", errorCode(response))
    }

    @Test
    fun aRequestThatIsNotAChatCompletionIsAnInvalidRequest() = routesTest {
        val response = authorizedPost("/v1/chat/completions", """{"messages":"nope"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", errorCode(response))
    }

    @Test
    fun aRoleTheSdkDoesNotServeIsAnInvalidRequest() = routesTest {
        val body = """{"model":"$MODEL_ID","messages":[{"role":"tool","content":"x"}]}"""

        val response = authorizedPost("/v1/chat/completions", body)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", errorCode(response))
    }

    @Test
    fun aRefusedParameterIsAnsweredWithAStatusNamingIt() = routesTest {
        val response = chatRequest(streaming = true, extraFields = """"tools":[],""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("unsupported_parameter", errorCode(response))
    }

    @Test
    fun embeddingsAreRefusedWithAnOpenAiErrorSayingTheyAreNotServed() = routesTest {
        val response = authorizedPost("/v1/embeddings", """{"model":"$MODEL_ID","input":"hi"}""")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        val expected = """{"error":{"message":"This server does not serve embeddings, only chat completions.",""" +
            """"type":"invalid_request_error","param":null,"code":"not_found"}}"""
        assertEquals(expected, response.bodyAsText())
    }

    @Test
    fun aWrongMethodOnARealEndpointIsAnOpenAiShapedMethodNotAllowed() = routesTest {
        val response = authorizedGet("/v1/chat/completions")

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        val expected = """{"error":{"message":"/v1/chat/completions does not accept GET.",""" +
            """"type":"invalid_request_error","param":null,"code":"method_not_allowed"}}"""
        assertEquals(expected, response.bodyAsText())
    }

    @Test
    fun anUnknownEndpointWithoutTheBearerIsStillUnauthorized() = routesTest {
        val response = client.post("/v1/embeddings") {
            header(HttpHeaders.Host, "127.0.0.1:$PRETEND_BOUND_PORT")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("invalid_api_key", errorCode(response))
    }

    @Test
    fun anEndpointTheServerDoesNotHaveIsAnOpenAiShapedNotFound() = routesTest {
        val response = authorizedGet("/v1/nothing-here")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val expected = """{"error":{"message":"This server has no endpoint GET /v1/nothing-here.",""" +
            """"type":"invalid_request_error","param":null,"code":"not_found"}}"""
        assertEquals(expected, response.bodyAsText())
    }

    /**
     * Ktor 3.0.1's in-memory client receives a response only once the server has finished it (measured), so a
     * client leaving mid-stream is played at the relay: the client's channel is cancelled, and the relay's own
     * failure is swallowed, as an engine that never reports a writer's failure to the route would.
     */
    @Test
    fun aClientThatDisconnectsMidStreamCancelsTheEngine() = runTest {
        load(Step.Emit(EngineEvent.Text("a")), *steadyTokens(count = 200, millisApart = 50), done())
        val client = ByteChannel()

        val events = chat.stream(chatRequestObject()).asStreamEvents().withKeepAlive(15.seconds)
            .produceIn(backgroundScope)
        val relay = backgroundScope.launch { runCatching { SseWriter(client).relay(events.receive(), events) } }
        client.readFramesUntil("\"content\":\"a\"")
        client.cancel(IOException("The client closed the connection."))
        relay.join()
        advanceUntilIdle()

        assertTrue(engine.cancelled)
    }

    @Test
    fun anUnloadMidStreamEndsTheStreamWithAnErrorEvent() = runTest {
        load(Step.Emit(EngineEvent.Text("a")), Step.Pause(60_000), Step.Emit(EngineEvent.Text("never sent")))
        val client = ByteChannel()

        val rest = coroutineScope {
            val events = chat.stream(chatRequestObject()).asStreamEvents().withKeepAlive(15.seconds).produceIn(this)
            launch {
                SseWriter(client).relay(events.receive(), events)
                client.flushAndClose()
            }
            client.readFramesUntil("\"content\":\"a\"")
            host.unload()
            client.readRemaining().readString()
        }

        assertEquals(frame(errorEventCoded("model_not_loaded")) + DONE_FRAME, rest.trimStart())
    }

    @Test
    fun aNonStreamingReplyEqualsTheGoldenJson() = routesTest(
        Step.Emit(EngineEvent.Text("Hello")),
        Step.Emit(EngineEvent.Text(" world")),
        Step.Emit(EngineEvent.Done(FinishReason.STOP, 7, 3, 10.0, 30.0)),
    ) {
        val response = chatRequest(streaming = false)

        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = ContentType.parse(checkNotNull(response.headers[HttpHeaders.ContentType]))
        assertEquals(ContentType.Application.Json, contentType.withoutParameters())
        assertEquals(resource("/openai/chat_completion.json"), withOneCompletionId(response.bodyAsText()))
    }

    @Test
    fun anUnloadDuringANonStreamingReplyIsAnsweredAsModelNotLoaded() = routesTest(Step.Pause(60_000), done()) {
        val response = coroutineScope {
            val reply = async { chatRequest(streaming = false) }
            awaitInRealTime { engine.generations.isNotEmpty() }
            host.unload()
            reply.await()
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("model_not_loaded", errorCode(response))
    }

    @Test
    fun aNonStreamingFailureIsAnsweredWithItsStatus() = routesTest {
        val response = chatRequest(streaming = false, model = "org/repo/missing")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("model_not_found", errorCode(response))
    }

    @Test
    fun modelsListsOnlyCompleteModelsAndMarksTheLoadedOne() = routesTest {
        gguf("$MODEL_ID.gguf").setLastModified(CREATED * 1_000)
        write("org/lite/gemma.litertlm", "LITERTLM", 64).setLastModified((CREATED + 60) * 1_000)
        gguf("org/split/big-00001-of-00002.gguf")

        val response = authorizedGet("/v1/models")

        assertEquals(HttpStatusCode.OK, response.status)
        val expected = """{"object":"list","data":[""" +
            """{"id":"org/lite/gemma","object":"model","created":${CREATED + 60},"owned_by":"turbollm-sdk",""" +
            """"meta":{"format":"litertlm","engine":"litert-lm","size_bytes":64,"loaded":false}},""" +
            """{"id":"$MODEL_ID","object":"model","created":$CREATED,"owned_by":"turbollm-sdk",""" +
            """"meta":{"format":"gguf","engine":"llama.cpp","size_bytes":$ONE_MIB,"loaded":true}}]}"""
        assertEquals(expected, response.bodyAsText())
    }

    @Test
    fun aModelLoadedByPathOutsideTheRootIsListedByItsAbsolutePath() = routesTest(loaded = false) {
        val outside = write(outsideRoot, "side.gguf", "GGUF", ONE_MIB).apply { setLastModified(CREATED * 1_000) }
        host.load(target(id = outside.absolutePath, file = outside, ref = ModelRef.File(outside.absolutePath)))

        val response = authorizedGet("/v1/models")

        val entry = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"].toString()
        val expected = """[{"id":${Json.encodeToString(String.serializer(), outside.absolutePath)},""" +
            """"object":"model","created":$CREATED,"owned_by":"turbollm-sdk",""" +
            """"meta":{"format":"gguf","engine":"llama.cpp","size_bytes":$ONE_MIB,"loaded":true}}]"""
        assertEquals(expected, entry)
    }

    @Test
    fun aPingIsSentWhenTheFirstTokenTakesLongerThanFifteenSeconds() = runTest {
        load(Step.Pause(16_000), Step.Emit(EngineEvent.Text("a")), done())

        val events = chat.stream(chatRequestObject()).asStreamEvents().withKeepAlive(15.seconds).toList()

        assertEquals(listOf("role", "ping", "content:a", "finish"), events.map(::label))
    }

    @Test
    fun pingsRepeatEveryFifteenSecondsWhilePrefilling() = runTest {
        load(Step.Pause(31_000), Step.Emit(EngineEvent.Text("a")), done())

        val events = chat.stream(chatRequestObject()).asStreamEvents().withKeepAlive(15.seconds).toList()

        assertEquals(listOf("role", "ping", "ping", "content:a", "finish"), events.map(::label))
    }

    @Test
    fun noPingIsSentOnceTokensFlow() = runTest {
        load(Step.Emit(EngineEvent.Text("a")), Step.Pause(40_000), Step.Emit(EngineEvent.Text("b")), done())

        val events = chat.stream(chatRequestObject()).asStreamEvents().withKeepAlive(15.seconds).toList()

        assertEquals(listOf("role", "content:a", "content:b", "finish"), events.map(::label))
    }

    @Test
    fun aPingCommitsTheStreamSoALaterFailureIsAnErrorEvent() = routesTest(
        Step.Pause(300),
        Step.Fail(IllegalStateException("late")),
        keepAlive = Duration.parse("100ms"),
    ) {
        val response = chatRequest(streaming = true)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = withOneCompletionId(response.bodyAsText())
        assertTrue(body, body.startsWith(frame(roleChunk()) + ": ping\n\n"))
        assertTrue(body, body.endsWith(frame(errorEventCoded("engine_error", "late")) + DONE_FRAME))
    }

    private fun routesTest(
        vararg script: Step,
        loaded: Boolean = true,
        keepAlive: Duration = 15.seconds,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val routes = OpenAiRoutes(chat, ModelListing(store) { host.state.value }, keepAlive, NO_LIVENESS_PROBES)
        application {
            installLocalServer(RequestPolicy(BearerAuth(TOKEN)) { PRETEND_BOUND_PORT }) { routes.install(this) }
        }
        if (loaded) load(*script)
        block()
    }

    private suspend fun load(vararg script: Step) {
        engine = FakeEngine(script.toList())
        host.load(target(MODEL_ID, File(root, "$MODEL_ID.gguf"), ModelRef.Installed(MODEL_ID)))
    }

    private fun target(id: String, file: File, ref: ModelRef) = LoadTarget(
        ref = ref,
        id = id,
        format = ModelFormat.GGUF,
        spec = LoadSpec(file, contextSize = null, threads = null, Backend.AUTO, File(root, "cache")),
    )

    private suspend fun ApplicationTestBuilder.chatRequest(
        streaming: Boolean,
        model: String = MODEL_ID,
        extraFields: String = "",
    ): HttpResponse = client.post("/v1/chat/completions") { chatBody(streaming, model, extraFields) }

    private fun HttpRequestBuilder.chatBody(streaming: Boolean, model: String = MODEL_ID, extraFields: String = "") {
        authorizedJson(
            """{"model":"$model",$extraFields"messages":[{"role":"user","content":"hi"}],"stream":$streaming}""",
        )
    }

    private suspend fun ApplicationTestBuilder.authorizedPost(path: String, json: String) =
        client.post(path) { authorizedJson(json) }

    private suspend fun ApplicationTestBuilder.authorizedGet(path: String) = client.get(path) {
        header(HttpHeaders.Host, "127.0.0.1:$PRETEND_BOUND_PORT")
        header(HttpHeaders.Authorization, "Bearer $TOKEN")
    }

    private fun HttpRequestBuilder.authorizedJson(json: String) {
        header(HttpHeaders.Host, "127.0.0.1:$PRETEND_BOUND_PORT")
        header(HttpHeaders.Authorization, "Bearer $TOKEN")
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(json)
    }

    private fun chatRequestObject() = ChatCompletionRequest(
        model = MODEL_ID,
        messages = listOf(ChatMessage(ChatRole.USER, MessageContent.Text("hi"))),
        stream = true,
    )

    private fun label(event: StreamEvent): String = when (event) {
        StreamEvent.Ping -> "ping"
        is StreamEvent.Failure -> "failure:${event.error.code}"
        is StreamEvent.Chunk -> {
            val choice = event.chunk.choices.single()
            when {
                choice.delta.role != null -> "role"
                choice.finishReason != null -> "finish"
                else -> "content:${choice.delta.content}"
            }
        }
    }

    /** Tokens arriving in real time, so the server writes again soon after a client leaves. */
    private fun steadyTokens(count: Int, millisApart: Long): Array<Step> =
        Array(count) { listOf(Step.Pause(millisApart), Step.Emit(EngineEvent.Text("x"))) }.flatMap { it }.toTypedArray()

    private fun done() = Step.Emit(EngineEvent.Done(FinishReason.STOP, 1, 1, 1.0, 1.0))

    private fun chunkPrefix() =
        """{"id":"chatcmpl-ID","object":"chat.completion.chunk","created":$CREATED,"model":"$MODEL_ID","""

    private fun roleChunk() = chunkPrefix() + """"choices":[{"index":0,"delta":{"role":"assistant"}}]}"""

    private fun contentChunk(text: String) = chunkPrefix() + """"choices":[{"index":0,"delta":{"content":"$text"}}]}"""

    private fun finishChunk() = chunkPrefix() + """"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
        """"timings":{"prompt_n":7,"prompt_ms":10.0,"predicted_n":3,"predicted_ms":30.0,""" +
        """"predicted_per_second":100.0}}"""

    private fun errorEventCoded(code: String, message: String? = null): String {
        val type = if (code == "engine_error") "server_error" else "invalid_request_error"
        val text = message ?: "The model was unloaded."
        return """{"error":{"message":"$text","type":"$type","param":null,"code":"$code"}}"""
    }

    private fun frame(json: String) = "data: $json\n\n"

    /** Every chunk of one reply shares one id; replacing it makes the body comparable to a fixed expectation. */
    private fun withOneCompletionId(body: String): String {
        assertTrue(body, COMPLETION_ID.findAll(body).map { it.value }.toSet().size <= 1)
        return body.replace(COMPLETION_ID, "chatcmpl-ID")
    }

    private suspend fun errorCode(response: HttpResponse): String {
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
        return error.getValue("code").jsonPrimitive.content
    }

    private suspend fun ByteReadChannel.readFramesUntil(marker: String) {
        while (true) {
            val line = checkNotNull(readUTF8Line()) { "the stream ended before $marker" }
            if (marker in line) return
        }
    }

    private suspend fun awaitInRealTime(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }

    private fun gguf(relative: String) = write(root, relative, "GGUF", ONE_MIB)

    private fun write(relative: String, magic: String, size: Int) = write(root, relative, magic, size)

    private fun write(base: File, relative: String, magic: String, size: Int): File {
        val file = File(base, relative)
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { out ->
            val magicBytes = magic.toByteArray(Charsets.US_ASCII)
            out.write(magicBytes)
            out.write(ByteArray(size - magicBytes.size))
        }
        return file
    }

    private fun resource(path: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "no resource $path" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
