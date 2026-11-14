package dev.turbollm.sdk

/** A running local server: where it listens (`baseUrl` = `http://127.0.0.1:<port>`) and the bearer it requires. */
data class ServerHandle(val baseUrl: String, val port: Int, val token: String)
