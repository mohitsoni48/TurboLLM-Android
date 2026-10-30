package dev.turbollm.sdk.internal

/** Milliseconds on a monotonic clock; injected so tests control time. */
internal fun interface Clock {
    fun nowMillis(): Long
}
