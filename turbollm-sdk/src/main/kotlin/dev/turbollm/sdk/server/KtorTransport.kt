package dev.turbollm.sdk.server

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer

private const val IDLE_CONNECTION_TIMEOUT_SECONDS = 60
private const val STOP_GRACE_MILLIS = 500L
private const val STOP_TIMEOUT_MILLIS = 2_000L

/** [HttpTransport] on Ktor's CIO engine, listening on loopback only. */
internal class KtorTransport : HttpTransport {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override suspend fun start(port: Int, module: Application.() -> Unit): Int {
        val started = embeddedServer(CIO, configure = { listenOnLoopbackOnly(port) }, module = module)
            .start(wait = false)
        server = started
        return started.engine.resolvedConnectors().single().port
    }

    override fun stop() {
        server?.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS)
        server = null
    }
}

internal fun CIOApplicationEngine.Configuration.listenOnLoopbackOnly(port: Int) {
    connector {
        host = LOOPBACK_HOST
        this.port = port
    }
    connectionIdleTimeoutSeconds = IDLE_CONNECTION_TIMEOUT_SECONDS
}
