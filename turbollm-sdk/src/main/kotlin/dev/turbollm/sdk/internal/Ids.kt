package dev.turbollm.sdk.internal

import java.security.SecureRandom

private const val CHAT_COMPLETION_PREFIX = "chatcmpl-"
private const val CHAT_COMPLETION_RANDOM_BYTES = 12
private const val SERVER_TOKEN_RANDOM_BYTES = 32

private val idRandom = SecureRandom()

internal fun chatCompletionId(): String = CHAT_COMPLETION_PREFIX + randomHex(CHAT_COMPLETION_RANDOM_BYTES)

/** The local server's bearer when the host passes none: 32 bytes from [SecureRandom], hex-encoded. */
internal fun serverToken(): String = randomHex(SERVER_TOKEN_RANDOM_BYTES)

private fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount).also(idRandom::nextBytes)
    return bytes.joinToString(separator = "") { "%02x".format(it) }
}
