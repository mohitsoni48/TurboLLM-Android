package dev.turbollm.sdk.server

import dev.turbollm.sdk.api.TurboLLMException
import dev.turbollm.sdk.chat.GenerationGate
import dev.turbollm.sdk.engine.EngineHost
import dev.turbollm.sdk.internal.sdkJson
import dev.turbollm.sdk.models.LoadTargets
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer

internal const val LOAD_PATH = "/sdk/v1/load"
internal const val STATUS_PATH = "/sdk/v1/status"
internal const val UNLOAD_PATH = "/sdk/v1/unload"

/**
 * The control surface for hosts and the daemon's attach transport (architecture §4.3). A load is checked, then
 * accepted with 202 and run in [loadScope]: loads can outlast an HTTP client's patience, so callers poll status.
 */
internal class ControlRoutes(
    private val targets: LoadTargets,
    private val host: EngineHost,
    private val gate: GenerationGate,
    private val loadScope: CoroutineScope,
) {
    fun install(route: Route) {
        route.post(LOAD_PATH) { call.acceptLoad() }
        route.get(STATUS_PATH) { call.respondStatus() }
        route.post(UNLOAD_PATH) { call.unload() }
    }

    private suspend fun ApplicationCall.acceptLoad() {
        try {
            val request = decodeLoadRequest(receiveBoundedText())
            val target = targets.of(request.modelRef(), request.options())
            host.loadInBackground(target, loadScope)
        } catch (refusal: TurboLLMException) {
            return respondError(refusal.toErrorReply())
        }
        respondJson(StateReply.serializer(), StateReply(ControlState.LOADING), HttpStatusCode.Accepted)
    }

    private suspend fun ApplicationCall.respondStatus() =
        respondJson(EngineStatus.serializer(), engineStatus(host.state.value, gate.busy, gate.queued))

    private suspend fun ApplicationCall.unload() {
        host.unload()
        respondJson(StateReply.serializer(), StateReply(ControlState.IDLE))
    }
}

private suspend fun <T> ApplicationCall.respondJson(
    serializer: KSerializer<T>,
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(sdkJson.encodeToString(serializer, value), ContentType.Application.Json, status)

private fun decodeLoadRequest(body: String): LoadRequest = try {
    sdkJson.decodeFromString(LoadRequest.serializer(), body)
} catch (malformed: IllegalArgumentException) {
    val reason = malformed.message.orEmpty()
    throw TurboLLMException.InvalidRequest(param = null, "The body is not a load request: $reason")
}
