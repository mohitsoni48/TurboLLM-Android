package dev.turbollm.sdk.server

import dev.turbollm.sdk.Backend
import dev.turbollm.sdk.EngineId
import dev.turbollm.sdk.FinishReason
import dev.turbollm.sdk.ModelFormat
import dev.turbollm.sdk.ModelRef
import dev.turbollm.sdk.ServerOptions
import dev.turbollm.sdk.chat.ChatService
import dev.turbollm.sdk.chat.GenerationGate
import dev.turbollm.sdk.chat.ModelResolver
import dev.turbollm.sdk.engine.EngineEvent
import dev.turbollm.sdk.engine.EngineHost
import dev.turbollm.sdk.engine.LoadSpec
import dev.turbollm.sdk.engine.LoadTarget
import dev.turbollm.sdk.internal.Clock
import dev.turbollm.sdk.internal.LoadSentinel
import dev.turbollm.sdk.models.LoadTargets
import dev.turbollm.sdk.models.ModelStore
import dev.turbollm.sdk.testing.FakeEngine
import dev.turbollm.sdk.testing.FakeEngine.Step
import dev.turbollm.sdk.testing.testCatalog
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val MODEL_ID = "org/repo/model-Q4_K_M"
private const val PREFILL_BOUND_MILLIS = 500L
private const val GENERATION_BOUND_MILLIS = 1_000L
private const val GIVE_UP_MILLIS = 10_000L
private const val POLL_MILLIS = 20L

/**
 * A client leaving over a real loopback socket (QA E-10, E-12): Ktor's in-memory client cannot close a
 * connection mid-response, so these tests bind the production [KtorTransport] to an ephemeral port and close a
 * raw socket. The client closes with nothing left unread, so the server sees a FIN, never a reset.
 */
class ClientDisconnectOverSocketTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root: File by lazy { temp.newFolder("models") }
    private val gate = GenerationGate(maxQueuedRequests = 1)
    private val backgroundWork = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine = FakeEngine()
    private val catalog = testCatalog(llamaCpp = { engine }, liteRtLm = { engine })
    private val host by lazy { EngineHost(catalog, gate, LoadSentinel(root)) }
    private val store by lazy { ModelStore(root, Clock { 0 }, loadedModelId = { null }) }
    private val server by lazy {
        val targets = LoadTargets(store, root, allowLoadByPath = false, cacheDir = File(root, "cache"), catalog)
        val resolver = ModelResolver(store, host, autoLoadOnRequest = false, File(root, "cache"), catalog)
        val chat = ChatService(resolver, gate, host)
        val control = ControlRoutes(targets, host, gate, backgroundWork)
        val openAi = OpenAiRoutes(chat, ModelListing(store) { host.state.value })
        LocalServer(KtorTransport(), logger = {}) {
            control.install(this)
            openAi.install(this)
        }
    }

    @After
    fun stopTheServer() {
        server.stop()
        backgroundWork.cancel()
    }

    @Test
    fun aClientThatLeavesDuringAPrefillFreesTheGateWithinHalfASecond() = runBlocking {
        load(Step.Pause(60_000), Step.Emit(EngineEvent.Text("never sent")), done())
        val port = server.start(ServerOptions(token = TOKEN)).port

        val freedAfter = Socket(LOOPBACK_HOST, port).use { client ->
            client.sendStreamingChat(port)
            client.getInputStream().readUntil("\"role\":\"assistant\"")
            client.close()
            millisUntilTheGateIsFree(port)
        }

        assertTrue("the gate was freed ${freedAfter}ms after the client left", freedAfter <= PREFILL_BOUND_MILLIS)
        assertTrue(engine.cancelled)
    }

    @Test
    fun aClientThatLeavesWhileTokensAreFlowingFreesTheGateWithinASecond() = runBlocking {
        val slowTokens = Array(200) { listOf(Step.Pause(400), Step.Emit(EngineEvent.Text("b"))) }.flatMap { it }
        load(Step.Emit(EngineEvent.Text("a")), *slowTokens.toTypedArray(), done())
        val port = server.start(ServerOptions(token = TOKEN)).port

        val freedAfter = Socket(LOOPBACK_HOST, port).use { client ->
            client.sendStreamingChat(port)
            client.getInputStream().readUntil("\"content\":\"a\"")
            client.close()
            millisUntilTheGateIsFree(port)
        }

        assertTrue("the gate was freed ${freedAfter}ms after the client left", freedAfter <= GENERATION_BOUND_MILLIS)
        assertTrue(engine.cancelled)
    }

    private suspend fun load(vararg script: Step) {
        engine = FakeEngine(script.toList())
        val spec = LoadSpec(File(root, "$MODEL_ID.gguf"), contextSize = null, threads = null, Backend.AUTO, root)
        host.load(LoadTarget(ModelRef.Installed(MODEL_ID), MODEL_ID, ModelFormat.GGUF, spec, EngineId.LLAMA_CPP_CPU))
    }

    private fun done() = Step.Emit(EngineEvent.Done(FinishReason.STOP, 1, 1, 1.0, 1.0))

    private fun millisUntilTheGateIsFree(port: Int): Long {
        val leftAt = System.nanoTime()
        while (statusSaysBusy(port) || !engine.cancelled) {
            check(elapsedMillisSince(leftAt) < GIVE_UP_MILLIS) { "the gate was never freed" }
            Thread.sleep(POLL_MILLIS)
        }
        return elapsedMillisSince(leftAt)
    }

    private fun elapsedMillisSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private fun statusSaysBusy(port: Int): Boolean {
        val connection = URL("http://$LOOPBACK_HOST:$port$STATUS_PATH").openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $TOKEN")
        return try {
            "\"busy\":true" in connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun Socket.sendStreamingChat(port: Int) {
        val body = """{"model":"$MODEL_ID","messages":[{"role":"user","content":"hi"}],"stream":true}"""
        val request = "POST $CHAT_COMPLETIONS_PATH HTTP/1.1\r\n" +
            "Host: $LOOPBACK_HOST:$port\r\n" +
            "Authorization: Bearer $TOKEN\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
            body
        getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }
    }

    /**
     * Reads byte by byte to the end of the HTTP chunk carrying the frame that holds [marker], so nothing is left
     * unread when the client closes.
     */
    private fun InputStream.readUntil(marker: String) {
        val seen = StringBuilder()
        while (!seen.endsWith("\n\n\r\n") || marker !in seen) {
            val next = read()
            check(next >= 0) { "the stream ended before $marker: $seen" }
            seen.append(next.toChar())
        }
    }
}
