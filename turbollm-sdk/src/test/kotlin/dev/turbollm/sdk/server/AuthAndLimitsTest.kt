package dev.turbollm.sdk.server

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/** The port the policy believes it is bound to. Nothing binds it: `testApplication` is in-memory. */
private const val PRETEND_BOUND_PORT = 6997
private const val LOOPBACK_AUTHORITY = "127.0.0.1:$PRETEND_BOUND_PORT"
private const val MIB = 1024 * 1024
private val MISDIRECTED = HttpStatusCode.fromValue(421)

private fun HttpRequestBuilder.addressedTo(authority: String) {
    headers[HttpHeaders.Host] = authority
}

class AuthAndLimitsTest {

    @Test
    fun aRequestWithoutABearerIsRefusedWithTheGoldenBody() = serverTest {
        val response = post("/probe") { jsonBody("{}") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(golden("invalid_api_key"), response.bodyAsText())
    }

    @Test
    fun aWrongBearerIsRefusedWithTheGoldenBody() = serverTest {
        val response = post("/probe") {
            header(HttpHeaders.Authorization, "Bearer ${TOKEN.reversed()}")
            jsonBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(golden("invalid_api_key"), response.bodyAsText())
    }

    @Test
    fun aTokenSentWithoutTheBearerSchemeIsRefused() = serverTest {
        val response = post("/probe") {
            header(HttpHeaders.Authorization, TOKEN)
            jsonBody("{}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun theRightBearerReachesTheRoute() = serverTest {
        val response = post("/probe") { authorized(); jsonBody("{\"a\":1}") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("7", response.bodyAsText())
    }

    @Test
    fun aRefusedRequestNeverRunsItsRoute() = serverTest {
        post("/probe/record") { jsonBody("{}") }
        post("/probe/record") { addressedTo("evil.com:$PRETEND_BOUND_PORT"); authorized(); jsonBody("{}") }
        post("/probe/record") { authorized(); header(HttpHeaders.ContentType, ContentType.Text.Plain.toString()) }

        assertEquals(0, routeRuns.get())
    }

    @Test
    fun aGetRouteOtherThanHealthAlsoNeedsTheBearer() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, get("/probe/get").status)
        assertEquals(HttpStatusCode.OK, get("/probe/get") { authorized() }.status)
    }

    @Test
    fun healthAnswersWithoutATokenAndSaysNothingAboutTheModel() = serverTest {
        val response = get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
        assertFalse(response.bodyAsText().contains("model"))
    }

    @Test
    fun aForeignHostIsMisdirected() = serverTest {
        val response = get("/health") { addressedTo("evil.com:$PRETEND_BOUND_PORT") }

        assertEquals(MISDIRECTED, response.status)
        assertEquals(golden("misdirected_request"), response.bodyAsText())
    }

    @Test
    fun aLoopbackHostOnAnotherPortIsMisdirected() = serverTest {
        val response = get("/health") { addressedTo("127.0.0.1:${PRETEND_BOUND_PORT - 1}") }

        assertEquals(MISDIRECTED, response.status)
    }

    @Test
    fun localhostOnTheBoundPortIsAccepted() = serverTest {
        assertEquals(HttpStatusCode.OK, get("/health") { addressedTo("localhost:$PRETEND_BOUND_PORT") }.status)
    }

    @Test
    fun aMisdirectedRequestIsRefusedBeforeItsTokenIsChecked() = serverTest {
        val response = post("/probe") { addressedTo("evil.com:$PRETEND_BOUND_PORT"); authorized(); jsonBody("{}") }

        assertEquals(MISDIRECTED, response.status)
    }

    @Test
    fun aPlainTextPostIsAnUnsupportedMediaType() = serverTest {
        val response = post("/probe") {
            authorized()
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody("{}")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals(golden("unsupported_media_type"), response.bodyAsText())
    }

    @Test
    fun aPostWithoutAContentTypeIsAnUnsupportedMediaType() = serverTest {
        val response = post("/probe") { authorized() }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun jsonWithACharsetIsAccepted() = serverTest {
        val response = post("/probe") {
            authorized()
            header(HttpHeaders.ContentType, "application/json; charset=utf-8")
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun aNineMebibyteBodyIsTooLarge() = serverTest {
        val response = post("/probe") { authorized(); jsonBody("x".repeat(9 * MIB)) }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(golden("request_too_large"), response.bodyAsText())
    }

    @Test
    fun aDeclaredNineMebibyteBodyIsRefusedEvenWhenTheRouteNeverReadsIt() = serverTest {
        val response = post("/probe/ignore-body") { authorized(); jsonBody("x".repeat(9 * MIB)) }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun aNineMebibyteChunkedBodyWithNoLengthIsTooLarge() = serverTest {
        val response = post("/probe") { authorized(); setBody(ChunkedJson(9 * MIB)) }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun aBodyOfExactlyEightMebibytesIsAccepted() = serverTest {
        val response = post("/probe") { authorized(); setBody(ChunkedJson(8 * MIB)) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals((8 * MIB).toString(), response.bodyAsText())
    }

    @Test
    fun noResponseEverCarriesACorsHeader() = serverTest {
        val responses = listOf(
            get("/health") { header(HttpHeaders.Origin, "https://evil.com") },
            post("/probe") { header(HttpHeaders.Origin, "https://evil.com"); jsonBody("{}") },
            post("/probe") { authorized(); jsonBody("{}") },
            options("/probe") {
                header(HttpHeaders.Origin, "https://evil.com")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
            },
            get("/health") { addressedTo("evil.com:$PRETEND_BOUND_PORT") },
        )

        responses.forEach { response ->
            val corsHeaders = response.headers.names().filter { it.startsWith("Access-Control-", ignoreCase = true) }
            assertTrue("${response.status} carried $corsHeaders", corsHeaders.isEmpty())
        }
    }

    @Test
    fun aSeventeenthConcurrentRequestIsRefusedAsBusy() = serverTest {
        coroutineScope {
            val held = (1..MAX_CONCURRENT_CALLS).map { async { get("/probe/hold") { authorized() } } }
            repeat(MAX_CONCURRENT_CALLS) { holdsEntered.receive() }

            val extra = get("/probe/hold") { authorized() }
            releaseHolds.complete(Unit)

            assertEquals(HttpStatusCode.TooManyRequests, extra.status)
            assertEquals("1", extra.headers[HttpHeaders.RetryAfter])
            assertEquals(golden("server_busy"), extra.bodyAsText())
            assertTrue(held.awaitAll().all { it.status == HttpStatusCode.OK })
        }
    }

    @Test
    fun aFinishedRequestFreesItsSlot() = serverTest {
        repeat(MAX_CONCURRENT_CALLS + 1) {
            assertEquals(HttpStatusCode.OK, get("/probe/get") { authorized() }.status)
        }
    }

    private val routeRuns = AtomicInteger()
    private val holdsEntered = Channel<Unit>(Channel.UNLIMITED)
    private val releaseHolds = CompletableDeferred<Unit>()

    private fun serverTest(block: suspend ServerProbe.() -> Unit) = testApplication {
        application {
            installLocalServer(RequestPolicy(BearerAuth(TOKEN)) { PRETEND_BOUND_PORT }) { probeRoutes() }
        }
        ServerProbe(client).block()
    }

    private fun Route.probeRoutes() {
        post("/probe") { call.respondText(call.receiveBoundedText().length.toString()) }
        get("/probe/get") { call.respondText("ok") }
        post("/probe/ignore-body") { call.respondText("ignored") }
        post("/probe/record") { call.respondText(routeRuns.incrementAndGet().toString()) }
        get("/probe/hold") {
            holdsEntered.send(Unit)
            releaseHolds.await()
            call.respondText("held")
        }
    }

    /** The in-memory client, sending `Host: 127.0.0.1:<bound port>` unless a request names another host. */
    private class ServerProbe(private val client: HttpClient) {
        suspend fun get(path: String, request: HttpRequestBuilder.() -> Unit = {}) =
            client.get(path) { addressedTo(LOOPBACK_AUTHORITY); request() }

        suspend fun post(path: String, request: HttpRequestBuilder.() -> Unit = {}) =
            client.post(path) { addressedTo(LOOPBACK_AUTHORITY); request() }

        suspend fun options(path: String, request: HttpRequestBuilder.() -> Unit = {}) =
            client.options(path) { addressedTo(LOOPBACK_AUTHORITY); request() }
    }

    private fun HttpRequestBuilder.authorized() = header(HttpHeaders.Authorization, "Bearer $TOKEN")

    private fun HttpRequestBuilder.jsonBody(json: String) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(json)
    }

    /** A JSON-typed body streamed with no `Content-Length`, so only the read itself can enforce the limit. */
    private class ChunkedJson(private val size: Int) : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.Application.Json

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val block = ByteArray(64 * 1024) { 'x'.code.toByte() }
            var remaining = size
            while (remaining > 0) {
                val length = minOf(block.size, remaining)
                channel.writeFully(block, 0, length)
                remaining -= length
            }
        }
    }

    private fun golden(code: String): String {
        val resource = checkNotNull(javaClass.getResourceAsStream("/errors/$code.json")) { "no golden for $code" }
        return resource.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
