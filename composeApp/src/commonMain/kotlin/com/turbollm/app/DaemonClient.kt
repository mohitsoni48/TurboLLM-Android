package com.turbollm.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DaemonStatus(
    val ok: Boolean = false,
    val node: String = "",
    val v8: String = "",
    val uv: String = "",
    val platform: String = "",
    val arch: String = "",
)

/** Thin client for the daemon's own status endpoint (127.0.0.1:6996) — shared across every
 *  platform target since it's plain HTTP with no platform-specific behavior. The concrete
 *  HTTP engine (ktor-client-android on Android, ktor-client-darwin on iOS) is resolved from
 *  whichever engine artifact that platform's source set depends on — see build.gradle.kts. */
class DaemonClient {
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 2000
            requestTimeoutMillis = 2000
        }
    }

    /** Null on any failure (daemon not up yet, timeout, malformed response) — the UI treats
     *  null as "still waiting," never crashes over a daemon that hasn't started listening. */
    suspend fun status(): DaemonStatus? =
        try {
            client.get("http://127.0.0.1:6996/").body()
        } catch (e: Exception) {
            null
        }
}
