package dev.turbollm.sdk.server

import dev.turbollm.sdk.ServerHandle
import dev.turbollm.sdk.ServerOptions
import dev.turbollm.sdk.internal.Logger
import dev.turbollm.sdk.internal.serverToken
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.atomic.AtomicInteger

private const val HEALTH_BODY = """{"status":"ok"}"""

/**
 * The opt-in loopback HTTP server (architecture §4.1): binds 127.0.0.1 only, always requires a bearer, and
 * refuses rebinding and browser-shaped requests before [routes] see them. The token is never logged.
 */
internal class LocalServer(
    private val transport: HttpTransport,
    private val logger: Logger,
    private val routes: Route.() -> Unit = {},
) {

    suspend fun start(options: ServerOptions): ServerHandle {
        val token = options.token ?: serverToken()
        val boundPort = AtomicInteger()
        val policy = RequestPolicy(BearerAuth(token), boundPort::get)
        boundPort.set(transport.start(options.port) { installLocalServer(policy, routes) })
        val handle = ServerHandle(baseUrl = "http://$LOOPBACK_HOST:${boundPort.get()}", port = boundPort.get(), token)
        logger.info("Local server listening on ${handle.baseUrl}")
        return handle
    }

    fun stop() = transport.stop()
}

/**
 * Screens every call through [policy], then serves `GET /health` and [routes]. A call no route answers gets the
 * status routing resolved (404 for an unknown path, 405 for a wrong method on a real one) with an OpenAI error
 * body. No CORS headers, ever.
 */
internal fun Application.installLocalServer(policy: RequestPolicy, routes: Route.() -> Unit) {
    intercept(ApplicationCallPipeline.Plugins) {
        policy.admit(call) { proceed() }
        finish()
    }
    sendPipeline.intercept(ApplicationSendPipeline.Before) { reply ->
        if (reply is HttpStatusCode && reply in UNROUTED_STATUSES) proceedWith(call.unroutedCallBody(reply))
    }
    routing {
        get(HEALTH_PATH) { call.respondText(HEALTH_BODY, ContentType.Application.Json) }
        routes()
    }
}

/**
 * Ktor's fallback answers a call routing could not place with a bare status: 404 for an unknown path, 405 for
 * a wrong method on a real one. Every reply of the SDK's own carries a body, so a bare 404 or 405 is that fallback.
 */
private val UNROUTED_STATUSES = setOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)

private fun ApplicationCall.unroutedCallBody(status: HttpStatusCode): TextContent =
    unroutedCallReply(status, request.httpMethod, request.path()).asTextContent()
