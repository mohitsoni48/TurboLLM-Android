package dev.turbollm.sdk.server

import dev.turbollm.sdk.api.ChatCompletion
import dev.turbollm.sdk.api.ChatCompletionRequest
import dev.turbollm.sdk.api.ModelList
import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.chat.ChatService
import dev.turbollm.sdk.internal.sdkJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.serialization.KSerializer

internal const val MODELS_PATH = "/v1/models"
internal const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
internal val KEEP_ALIVE_INTERVAL = 15.seconds
private const val NO_CACHE = "no-cache"

/**
 * The OpenAI surface (architecture §4.2): a thin codec over [ChatService] and [ModelListing]. A failure before
 * a reply's first event is answered with its status; once a stream has started it travels as an error event.
 */
internal class OpenAiRoutes(
    private val chat: ChatService,
    private val listing: ModelListing,
    private val keepAliveInterval: Duration = KEEP_ALIVE_INTERVAL,
    private val probeInterval: Duration = LIVENESS_PROBE_INTERVAL,
) {
    fun install(route: Route) {
        route.get(MODELS_PATH) { call.respondJson(ModelList.serializer(), listing.current()) }
        route.post(CHAT_COMPLETIONS_PATH) { call.answerChat() }
    }

    private suspend fun ApplicationCall.answerChat() {
        val body = receiveBoundedText()
        val request = try {
            decodeChatRequest(body)
        } catch (refusal: TurboLLMException) {
            return respondError(refusal.toErrorReply())
        }
        if (request.stream) streamReply(request) else completeReply(request)
    }

    private suspend fun ApplicationCall.completeReply(request: ChatCompletionRequest) {
        val completion = try {
            chat.complete(request)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            return respondError(failure.asReplyFailure().toErrorReply())
        }
        respondJson(ChatCompletion.serializer(), completion)
    }

    /** Waits for the reply's first event (a chunk, a ping or a failure) before committing to a status. */
    private suspend fun ApplicationCall.streamReply(request: ChatCompletionRequest) = coroutineScope {
        val events = replyEvents(request).produceIn(this)
        when (val first = events.receiveCatching().getOrNull()) {
            is StreamEvent.Failure -> respondError(first.error.toErrorReply())
            else -> respondEventStream(first, events, probeInterval)
        }
    }

    private fun replyEvents(request: ChatCompletionRequest): Flow<StreamEvent> =
        chat.stream(request).asStreamEvents().withKeepAlive(keepAliveInterval)
}

private suspend fun ApplicationCall.respondEventStream(
    first: StreamEvent?,
    rest: ReceiveChannel<StreamEvent>,
    probeInterval: Duration,
) {
    response.header(HttpHeaders.CacheControl, NO_CACHE)
    respondBytesWriter(ContentType.Text.EventStream) { SseWriter(this, probeInterval).relay(first, rest) }
}

private suspend fun <T> ApplicationCall.respondJson(serializer: KSerializer<T>, value: T) =
    respondText(sdkJson.encodeToString(serializer, value), ContentType.Application.Json)

private fun decodeChatRequest(body: String): ChatCompletionRequest = try {
    sdkJson.decodeFromString(ChatCompletionRequest.serializer(), body)
} catch (malformed: IllegalArgumentException) {
    val reason = malformed.message.orEmpty()
    throw TurboLLMException.InvalidRequest(param = null, "The body is not a chat completion request: $reason")
}
