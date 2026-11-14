package dev.turbollm.sdk

/**
 * How [TurboLLM.startServer] listens. The server only ever binds 127.0.0.1, and every route but `GET /health`
 * requires `Authorization: Bearer <token>`.
 *
 * @property port 0 picks an ephemeral port; the bound one is in [ServerHandle.port].
 * @property token null generates 32 random bytes, hex-encoded. A blank token is refused: there is no token-less mode.
 */
data class ServerOptions(
    val port: Int = 0,
    val token: String? = null,
) {
    init {
        require(token == null || token.isNotBlank()) { "The server token must not be blank." }
    }
}
