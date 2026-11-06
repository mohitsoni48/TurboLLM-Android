package dev.turbollm.sdk.server

import dev.turbollm.sdk.api.ErrorBody
import dev.turbollm.sdk.api.ErrorDetail
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.api.toErrorBody
import dev.turbollm.sdk.internal.sdkJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonNull

private const val TRANSPORT_ERROR_TYPE = "invalid_request_error"
private const val EMBEDDINGS_PATH = "/v1/embeddings"

/** An error the server answers with: a status, the OpenAI error envelope and, for a busy server, `Retry-After`. */
internal class ErrorReply(val status: HttpStatusCode, val body: ErrorBody, val retryAfterSeconds: Int? = null)

/** Refusals made before a request reaches a route (architecture §4.1); they have no [TurboLLMException]. */
internal val MISDIRECTED_REQUEST =
    transportRefusal(421, "misdirected_request", "The Host header does not name this server.")
internal val UNSUPPORTED_MEDIA_TYPE =
    transportRefusal(415, "unsupported_media_type", "The request body must be application/json.")
internal val REQUEST_TOO_LARGE =
    transportRefusal(413, "request_too_large", "The request body is larger than 8 MiB.")

internal fun TurboLLMException.toErrorReply(): ErrorReply = ErrorReply(
    status = HttpStatusCode.fromValue(httpStatus),
    body = toErrorBody(),
    retryAfterSeconds = (this as? TurboLLMException.ServerBusy)?.retryAfterSeconds,
)

internal suspend fun ApplicationCall.respondError(reply: ErrorReply) {
    reply.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it) }
    respondText(reply.encodedBody(), ContentType.Application.Json, reply.status)
}

/** The reply as content, for a reply already on its way out; the same JSON and type [respondError] sends. */
internal fun ErrorReply.asTextContent() =
    TextContent(encodedBody(), ContentType.Application.Json.withCharset(Charsets.UTF_8), status)

private fun ErrorReply.encodedBody(): String = sdkJson.encodeToString(ErrorBody.serializer(), body)

/**
 * A call no route answered, with the [status] routing resolved (QA B-08): Ktor's own reply has an empty body,
 * which OpenAI clients cannot read.
 */
internal fun unroutedCallReply(status: HttpStatusCode, method: HttpMethod, path: String): ErrorReply =
    when (status) {
        HttpStatusCode.MethodNotAllowed ->
            transportRefusal(status.value, "method_not_allowed", "$path does not accept ${method.value}.")
        else -> transportRefusal(status.value, "not_found", noEndpointMessage(method, path))
    }

private fun noEndpointMessage(method: HttpMethod, path: String): String = when (path) {
    EMBEDDINGS_PATH -> "This server does not serve embeddings, only chat completions."
    else -> "This server has no endpoint ${method.value} $path."
}

private fun transportRefusal(status: Int, code: String, message: String) = ErrorReply(
    status = HttpStatusCode.fromValue(status),
    body = ErrorBody(ErrorDetail(message = message, type = TRANSPORT_ERROR_TYPE, param = JsonNull, code = code)),
)
