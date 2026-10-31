package dev.turbollm.sdk.server

import dev.turbollm.sdk.ServerHandle
import dev.turbollm.sdk.ServerOptions
import dev.turbollm.sdk.internal.Logger
import dev.turbollm.sdk.internal.serverToken
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
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

/** Screens every call through [policy], then serves `GET /health` and [routes]. No CORS headers, ever. */
internal fun Application.installLocalServer(policy: RequestPolicy, routes: Route.() -> Unit) {
    intercept(ApplicationCallPipeline.Plugins) {
        policy.admit(call) { proceed() }
        finish()
    }
    routing {
        get(HEALTH_PATH) { call.respondText(HEALTH_BODY, ContentType.Application.Json) }
        routes()
    }
}
