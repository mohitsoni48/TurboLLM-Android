package dev.turbollm.sdk.server

import dev.turbollm.sdk.api.TurboLLMException
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.util.concurrent.Semaphore

internal const val MAX_CONCURRENT_CALLS = 16
internal const val MAX_REQUEST_BODY_BYTES = 8L * 1024 * 1024
internal const val HEALTH_PATH = "/health"
private const val LOCALHOST = "localhost"

/**
 * What every request must pass before a route sees it (architecture §4.1), in this order: a `Host` naming this
 * server (DNS rebinding → 421), the bearer (401, except `GET /health`), a JSON body on POST (415), at most 8 MiB
 * of body (413) and at most [MAX_CONCURRENT_CALLS] calls in flight (429).
 *
 * @param boundPort the port actually bound, read per request because an ephemeral port is known only after bind.
 */
internal class RequestPolicy(private val auth: BearerAuth, private val boundPort: () -> Int) {

    private val callSlots = Semaphore(MAX_CONCURRENT_CALLS)

    suspend fun admit(call: ApplicationCall, route: suspend () -> Unit) {
        val refusal = refusalOf(call.request)
        when {
            refusal != null -> call.respondError(refusal)
            !callSlots.tryAcquire() -> call.respondError(TurboLLMException.ServerBusy().toErrorReply())
            else -> try {
                runWithinBodyLimit(call, route)
            } finally {
                callSlots.release()
            }
        }
    }

    private fun refusalOf(request: ApplicationRequest): ErrorReply? = when {
        !isAddressedToThisServer(request) -> MISDIRECTED_REQUEST
        !isHealthCheck(request) && !auth.admits(request.header(HttpHeaders.Authorization)) ->
            TurboLLMException.Unauthorized().toErrorReply()
        request.httpMethod == HttpMethod.Post && !carriesJson(request) -> UNSUPPORTED_MEDIA_TYPE
        (request.contentLength() ?: 0) > MAX_REQUEST_BODY_BYTES -> REQUEST_TOO_LARGE
        else -> null
    }

    private suspend fun runWithinBodyLimit(call: ApplicationCall, route: suspend () -> Unit) {
        try {
            route()
        } catch (_: RequestBodyTooLarge) {
            call.respondError(REQUEST_TOO_LARGE)
        }
    }

    private fun isAddressedToThisServer(request: ApplicationRequest): Boolean {
        val host = request.header(HttpHeaders.Host)?.lowercase() ?: return false
        val port = boundPort()
        return host == "$LOOPBACK_HOST:$port" || host == "$LOCALHOST:$port"
    }

    private fun isHealthCheck(request: ApplicationRequest): Boolean =
        request.httpMethod == HttpMethod.Get && request.path() == HEALTH_PATH

    private fun carriesJson(request: ApplicationRequest): Boolean {
        val contentType = request.header(HttpHeaders.ContentType) ?: return false
        return try {
            ContentType.parse(contentType).match(ContentType.Application.Json)
        } catch (_: BadContentTypeFormatException) {
            false
        }
    }
}

/**
 * The request body as UTF-8 text, read to at most [MAX_REQUEST_BODY_BYTES]. A body sent with no `Content-Length`
 * passes the policy's header check, so routes read bodies only through this.
 */
internal suspend fun ApplicationCall.receiveBoundedText(): String {
    val bytes = receiveChannel().readRemaining(MAX_REQUEST_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_REQUEST_BODY_BYTES) throw RequestBodyTooLarge()
    return bytes.decodeToString()
}

private class RequestBodyTooLarge : Exception("The request body is larger than $MAX_REQUEST_BODY_BYTES bytes.")
