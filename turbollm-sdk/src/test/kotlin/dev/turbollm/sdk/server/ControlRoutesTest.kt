package dev.turbollm.sdk.server

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.chat.ChatService
import dev.turbollm.sdk.chat.GenerationGate
import dev.turbollm.sdk.chat.ModelResolver
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineHost
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.internal.LoadSentinel
import dev.turbollm.sdk.models.LoadTargets
import dev.turbollm.sdk.models.ModelStore
import dev.turbollm.sdk.testing.FakeEngine
import dev.turbollm.sdk.testing.FakeEngine.Step
import dev.turbollm.sdk.testing.testCatalog
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/** The port the policy believes it is bound to. Nothing binds it: `testApplication` is in-memory. */
private const val PRETEND_BOUND_PORT = 6997
private const val MODEL_ID = "org/repo/model-Q4_K_M"
private const val ONE_MIB = 1024 * 1024

class ControlRoutesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: File by lazy { temp.newFolder("models").canonicalFile }
    private val outsideRoot: File by lazy { temp.newFolder("elsewhere").canonicalFile }
    private val sentinelFile: File by lazy { File(root, ".turbollm-sdk/loading.json") }
    private val gate = GenerationGate(maxQueuedRequests = 1)
    private val backgroundWork = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine = FakeEngine()
    private var enginesCreated = 0
    private val host by lazy {
        val catalog = testCatalog(llamaCpp = ::nextEngine, liteRtLm = ::nextEngine)
        EngineHost(catalog, gate, LoadSentinel(root))
    }
    private val store by lazy { ModelStore(root, Clock { 0 }, loadedModelId = { null }) }

    @Test
    fun aLoadIsAcceptedAtOnceThenStatusGoesFromLoadingToReadyWithTheModel() = controlTest(allowLoadByPath = false) {
        val file = gguf("$MODEL_ID.gguf")
        engine = FakeEngine(loadPauseMillis = 300)

        val accepted = authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID"}""")

        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals(json("""{"state":"loading"}"""), json(accepted.bodyAsText()))
        assertEquals("loading", stateOf(status()))
        awaitState("ready")
        val expected = """{"state":"ready","model":{"id":"$MODEL_ID","path":${quoted(file.path)},"format":"gguf",""" +
            """"engine":"llama.cpp","contextSize":4096,"backend":"cpu","notes":[]},"busy":false,"queued":0}"""
        assertEquals(json(expected), json(status().bodyAsText()))
    }

    @Test
    fun statusWhenNothingIsLoadedIsIdle() = controlTest {
        val response = status()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(json("""{"state":"idle","busy":false,"queued":0}"""), json(response.bodyAsText()))
    }

    @Test
    fun aSecondLoadWhileTheFirstIsLoadingIsRefusedAsLoadInProgress() = controlTest {
        gguf("$MODEL_ID.gguf")
        gguf("org/repo/other.gguf")
        engine = FakeEngine(loadPauseMillis = 300)
        authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID"}""")

        val second = authorizedPost(LOAD_PATH, """{"model":"org/repo/other"}""")

        assertRefused(second, HttpStatusCode.Conflict, "load_in_progress")
        awaitState("ready")
        assertEquals(MODEL_ID, modelIdOf(status()))
    }

    @Test
    fun aLoadReplacingTheLoadedModelReadsLoadingAsSoonAsItIsAccepted() = controlTest {
        gguf("$MODEL_ID.gguf")
        gguf("org/repo/other.gguf")
        authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID"}""")
        awaitState("ready")
        engine = FakeEngine(loadPauseMillis = 300)

        authorizedPost(LOAD_PATH, """{"model":"org/repo/other"}""")

        assertEquals("loading", stateOf(status()))
        awaitState("ready")
        assertEquals("org/repo/other", modelIdOf(status()))
    }

    @Test
    fun aFailedLoadIsReportedAsAnErrorStatusWithItsMessageAndCode() = controlTest {
        gguf("$MODEL_ID.gguf")
        engine = FakeEngine(loadFailure = TurboLLMException.ModelLoadFailed("The native load failed."))

        authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID"}""")

        awaitState("error")
        val expected = """{"state":"error","busy":false,"queued":0,""" +
            """"error":{"message":"The native load failed.","code":"model_load_failed"}}"""
        assertEquals(json(expected), json(status().bodyAsText()))
    }

    @Test
    fun theLoadOptionsReachTheEngine() = controlTest {
        gguf("$MODEL_ID.gguf")

        authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID","contextSize":2048,"threads":3,"backend":"gpu"}""")

        awaitState("ready")
        val spec = engine.loads.single()
        assertEquals(2048, spec.contextSize)
        assertEquals(3, spec.threads)
        assertEquals(Backend.GPU, spec.backend)
    }

    @Test
    fun aNonPositiveContextSizeOrThreadCountIsAnInvalidRequest() = controlTest {
        gguf("$MODEL_ID.gguf")

        val zeroContext = authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID","contextSize":0}""")
        val negativeThreads = authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID","threads":-1}""")

        assertRefused(zeroContext, HttpStatusCode.BadRequest, "invalid_request", param = "contextSize")
        assertRefused(negativeThreads, HttpStatusCode.BadRequest, "invalid_request", param = "threads")
        assertNoLoadWasAttempted()
    }

    @Test
    fun aBackendTheSdkDoesNotKnowIsAnInvalidRequest() = controlTest {
        gguf("$MODEL_ID.gguf")

        val response = authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID","backend":"npu"}""")

        assertRefused(response, HttpStatusCode.BadRequest, "invalid_request")
        assertNoLoadWasAttempted()
    }

    @Test
    fun aLoadOfAnIdNoInstalledModelHasIsModelNotFound() = controlTest {
        val response = authorizedPost(LOAD_PATH, """{"model":"org/repo/missing"}""")

        assertRefused(response, HttpStatusCode.NotFound, "model_not_found")
        assertNoLoadWasAttempted()
    }

    @Test
    fun aLoadMustNameExactlyOneOfModelAndPath() = controlTest {
        val file = gguf("$MODEL_ID.gguf")

        val neither = authorizedPost(LOAD_PATH, """{}""")
        val both = authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID","path":${quoted(file.path)}}""")

        assertRefused(neither, HttpStatusCode.BadRequest, "invalid_request")
        assertRefused(both, HttpStatusCode.BadRequest, "invalid_request")
        assertNoLoadWasAttempted()
    }

    @Test
    fun aPathLoadIsForbiddenWhenLoadByPathIsDisabled() = controlTest(allowLoadByPath = false) {
        val file = gguf("$MODEL_ID.gguf")

        val response = loadByPath(file.path)

        assertRefused(response, HttpStatusCode.Forbidden, "load_by_path_disabled")
        assertNoLoadWasAttempted()
    }

    @Test
    fun aPathLoadInsideTheRootIsKnownByTheModelsId() = controlTest {
        val file = gguf("$MODEL_ID.gguf")

        assertEquals(HttpStatusCode.Accepted, loadByPath(file.path).status)

        awaitState("ready")
        assertEquals(MODEL_ID, modelIdOf(status()))
    }

    @Test
    fun aPathLoadOutsideTheRootIsKnownByItsAbsolutePath() = controlTest {
        val file = write(outsideRoot, "loose.litertlm", "LITERTLM", 64)

        assertEquals(HttpStatusCode.Accepted, loadByPath(file.path).status)

        awaitState("ready")
        assertEquals(file.path, modelIdOf(status()))
        assertEquals(file.path, engine.loads.single().path.path)
    }

    @Test
    fun aPathLoadOfShardOneOfACompleteGroupLoadsTheGroup() = controlTest {
        val first = write(root, "org/repo/big-00001-of-00002.gguf", "GGUF", ONE_MIB)
        write(root, "org/repo/big-00002-of-00002.gguf", "GGUF", ONE_MIB)

        assertEquals(HttpStatusCode.Accepted, loadByPath(first.path).status)

        awaitState("ready")
        assertEquals("org/repo/big", modelIdOf(status()))
    }

    @Test
    fun aRelativePathIsRefused() = controlTest {
        gguf("$MODEL_ID.gguf")

        assertPathRefused("$MODEL_ID.gguf", "absolute")
    }

    @Test
    fun aDirectoryIsRefused() = controlTest {
        assertPathRefused(File(root, "org").apply { mkdirs() }.path, "regular file")
    }

    @Test
    fun aMissingFileIsRefused() = controlTest {
        assertPathRefused(File(root, "absent.gguf").path, "regular file")
    }

    @Test
    fun aFileWithNoModelExtensionIsRefused() = controlTest {
        assertPathRefused(write(root, "weights.bin", "GGUF", ONE_MIB).path, "extension")
    }

    @Test
    fun aGgufWithoutItsMagicIsRefused() = controlTest {
        assertPathRefused(write(root, "fake.gguf", "HTML", ONE_MIB).path, "magic")
    }

    @Test
    fun aLiteRtLmBundleWithoutItsMagicIsRefused() = controlTest {
        assertPathRefused(write(root, "fake.litertlm", "GGUF", 64).path, "magic")
    }

    @Test
    fun aGgufUnderOneMebibyteIsRefused() = controlTest {
        assertPathRefused(write(root, "short.gguf", "GGUF", ONE_MIB - 1).path, "1 MiB")
    }

    @Test
    fun aVisionProjectorIsRefused() = controlTest {
        assertPathRefused(write(root, "org/repo/mmproj-model-F16.gguf", "GGUF", ONE_MIB).path, "projector")
    }

    @Test
    fun aShardOtherThanTheFirstIsRefused() = controlTest {
        write(root, "org/repo/big-00001-of-00002.gguf", "GGUF", ONE_MIB)
        val second = write(root, "org/repo/big-00002-of-00002.gguf", "GGUF", ONE_MIB)

        assertPathRefused(second.path, "shard 2 of 2")
    }

    @Test
    fun theFirstShardOfAGroupMissingAShardIsRefused() = controlTest {
        val first = write(root, "org/repo/big-00001-of-00003.gguf", "GGUF", ONE_MIB)
        write(root, "org/repo/big-00002-of-00003.gguf", "GGUF", ONE_MIB)

        assertPathRefused(first.path, "missing shard 3 of 3")
    }

    @Test
    fun aShardWithoutTheGgufMagicCountsAsMissing() = controlTest {
        val first = write(root, "org/repo/big-00001-of-00002.gguf", "GGUF", ONE_MIB)
        write(root, "org/repo/big-00002-of-00002.gguf", "HTML", ONE_MIB)

        assertPathRefused(first.path, "missing shard 2 of 2")
    }

    @Test
    fun aDotDotPathIsCanonicalisedBeforeItIsChecked() = controlTest {
        write(outsideRoot, "prefs.xml", "<?xml", 64)
        val escaping = root.path + File.separator + ".." + File.separator + "elsewhere" + File.separator + "prefs.xml"

        assertPathRefused(escaping, "extension")
    }

    @Test
    fun aSymlinkNamedLikeAModelIsJudgedByTheFileItPointsAt() = controlTest {
        val target = write(outsideRoot, "prefs.xml", "<?xml", ONE_MIB)
        val link = File(root, "ok.gguf")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue("this file system cannot create a symbolic link here", linked)

        assertPathRefused(link.path, "extension")
    }

    @Test
    fun unloadDuringAStreamAnswersIdleAndEndsTheStream() = controlTest {
        gguf("$MODEL_ID.gguf")
        engine = FakeEngine(listOf(Step.Pause(60_000), Step.Emit(EngineEvent.Done(FinishReason.STOP, 1, 1, 1.0, 1.0))))
        authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID"}""")
        awaitState("ready")
        val stream = backgroundWork.async { streamingChat() }
        awaitInRealTime { engine.generations.isNotEmpty() }

        val unloaded = authorizedPost(UNLOAD_PATH, "{}")

        assertEquals(HttpStatusCode.OK, unloaded.status)
        assertEquals(json("""{"state":"idle"}"""), json(unloaded.bodyAsText()))
        assertEquals(listOf("load", "cancelled", "unload", "close"), engine.history)
        val body = withContext(Dispatchers.Default) { withTimeout(10_000) { stream.await().bodyAsText() } }
        assertTrue(body, body.endsWith("data: [DONE]\n\n"))
        assertFalse(body, "engine_error" in body)
        assertEquals("idle", stateOf(status()))
    }

    @Test
    fun statusReportsAGenerationInFlightAsBusy() = controlTest {
        gguf("$MODEL_ID.gguf")
        engine = FakeEngine(listOf(Step.Pause(60_000)))
        authorizedPost(LOAD_PATH, """{"model":"$MODEL_ID"}""")
        awaitState("ready")
        backgroundWork.async { streamingChat() }
        awaitInRealTime { gate.busy }

        val busy = Json.parseToJsonElement(status().bodyAsText()).jsonObject.getValue("busy").jsonPrimitive.content

        assertEquals("true", busy)
        authorizedPost(UNLOAD_PATH, "{}")
    }

    @Test
    fun unloadWhenNothingIsLoadedAnswersIdle() = controlTest {
        val unloaded = authorizedPost(UNLOAD_PATH, "{}")

        assertEquals(HttpStatusCode.OK, unloaded.status)
        assertEquals(json("""{"state":"idle"}"""), json(unloaded.bodyAsText()))
    }

    @Test
    fun everyControlRouteRequiresTheBearer() = controlTest {
        val load = client.post(LOAD_PATH) { addressed(); jsonBody("""{"model":"$MODEL_ID"}""") }
        val status = client.get(STATUS_PATH) { addressed() }
        val unload = client.post(UNLOAD_PATH) { addressed(); jsonBody("{}") }

        listOf(load, status, unload).forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
        assertNoLoadWasAttempted()
    }

    private fun controlTest(
        allowLoadByPath: Boolean = true,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val targets = LoadTargets(store, root, allowLoadByPath, cacheDir = File(root, "cache"))
        val control = ControlRoutes(targets, host, gate, backgroundWork)
        val chat = ChatService(ModelResolver(store, host, autoLoadOnRequest = false, File(root, "cache")), gate, host)
        val openAi = OpenAiRoutes(chat, ModelListing(store) { host.state.value })
        application {
            installLocalServer(RequestPolicy(BearerAuth(TOKEN)) { PRETEND_BOUND_PORT }) {
                control.install(this)
                openAi.install(this)
            }
        }
        try {
            block()
        } finally {
            backgroundWork.cancel()
        }
    }

    private fun nextEngine(): FakeEngine {
        enginesCreated++
        return engine
    }

    private suspend fun ApplicationTestBuilder.assertPathRefused(path: String, rule: String) {
        val response = loadByPath(path)

        val message = assertRefused(response, HttpStatusCode.BadRequest, "invalid_request", param = "path")
        assertTrue("'$message' should name the rule '$rule'", message.contains(rule, ignoreCase = true))
        assertNoLoadWasAttempted()
        assertEquals("idle", stateOf(status()))
    }

    private suspend fun assertRefused(
        response: HttpResponse,
        status: HttpStatusCode,
        code: String,
        param: String? = null,
    ): String {
        assertEquals(status, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
        assertEquals(code, error.getValue("code").jsonPrimitive.content)
        param?.let { assertEquals(it, error.getValue("param").jsonPrimitive.content) }
        return error.getValue("message").jsonPrimitive.content
    }

    private fun assertNoLoadWasAttempted() {
        assertEquals("engines created", 0, enginesCreated)
        assertTrue("engine loads ${engine.loads}", engine.loads.isEmpty())
        assertFalse("sentinel left behind", sentinelFile.exists())
    }

    private suspend fun ApplicationTestBuilder.loadByPath(path: String) =
        authorizedPost(LOAD_PATH, """{"path":${quoted(path)}}""")

    private suspend fun ApplicationTestBuilder.streamingChat() = authorizedPost(
        CHAT_COMPLETIONS_PATH,
        """{"model":"$MODEL_ID","messages":[{"role":"user","content":"hi"}],"stream":true}""",
    )

    private suspend fun ApplicationTestBuilder.awaitState(state: String) = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            while (stateOf(status()) != state) delay(10)
        }
    }

    private suspend fun awaitInRealTime(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }
    }

    private suspend fun ApplicationTestBuilder.status() = client.get(STATUS_PATH) {
        addressed()
        header(HttpHeaders.Authorization, "Bearer $TOKEN")
    }

    private suspend fun ApplicationTestBuilder.authorizedPost(path: String, body: String) = client.post(path) {
        addressed()
        header(HttpHeaders.Authorization, "Bearer $TOKEN")
        jsonBody(body)
    }

    private fun HttpRequestBuilder.addressed() {
        header(HttpHeaders.Host, "127.0.0.1:$PRETEND_BOUND_PORT")
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(body)
    }

    private suspend fun stateOf(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("state").jsonPrimitive.content

    private suspend fun modelIdOf(response: HttpResponse): String {
        val model = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("model").jsonObject
        return model.getValue("id").jsonPrimitive.content
    }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun quoted(text: String) = JsonPrimitive(text).toString()

    private fun gguf(relative: String) = write(root, relative, "GGUF", ONE_MIB)

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
}
