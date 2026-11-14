package dev.turbollm.sdk.server

import io.ktor.server.application.Application

internal const val LOOPBACK_HOST = "127.0.0.1"

/**
 * The seam between the server's routes and the socket under them (architecture §7 R4): Ktor CIO today, a
 * hand-rolled `ServerSocket` transport if CIO fails on API 24, with no route touched either way.
 */
internal interface HttpTransport {

    /** Listens on [LOOPBACK_HOST]:[port] (0 = ephemeral), serves [module], and returns the port actually bound. */
    suspend fun start(port: Int, module: Application.() -> Unit): Int

    fun stop()
}
