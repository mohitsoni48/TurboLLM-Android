package dev.turbollm.sdk.server

import java.security.MessageDigest

private const val BEARER_SCHEME = "Bearer "

/** Checks an `Authorization` header against the server's token in constant time. */
internal class BearerAuth(token: String) {

    private val expected = token.toByteArray(Charsets.UTF_8)

    fun admits(authorization: String?): Boolean {
        val presented = authorization?.bearerCredential() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected)
    }

    private fun String.bearerCredential(): String? =
        if (startsWith(BEARER_SCHEME, ignoreCase = true)) substring(BEARER_SCHEME.length) else null
}
