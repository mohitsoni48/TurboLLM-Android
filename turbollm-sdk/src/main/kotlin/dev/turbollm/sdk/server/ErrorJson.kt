package dev.turbollm.sdk.server

import dev.turbollm.sdk.api.ErrorBody
import dev.turbollm.sdk.api.ErrorDetail
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.api.toErrorBody
import dev.turbollm.sdk.internal.sdkJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonNull

private const val TRANSPORT_ERROR_TYPE = "invalid_request_error"

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
    respondText(sdkJson.encodeToString(ErrorBody.serializer(), reply.body), ContentType.Application.Json, reply.status)
}

private fun transportRefusal(status: Int, code: String, message: String) = ErrorReply(
    status = HttpStatusCode.fromValue(status),
    body = ErrorBody(ErrorDetail(message = message, type = TRANSPORT_ERROR_TYPE, param = JsonNull, code = code)),
)
