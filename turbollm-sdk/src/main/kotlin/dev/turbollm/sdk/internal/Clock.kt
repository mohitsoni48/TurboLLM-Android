package dev.turbollm.sdk.internal

/** Milliseconds on a monotonic clock; injected so tests control time. */
internal fun interface Clock {
    fun nowMillis(): Long
}

private const val NANOS_PER_MILLI = 1_000_000L

/** The production clock: monotonic, so a wall-clock change never ages a cache or a download speed. */
internal val monotonicClock = Clock { System.nanoTime() / NANOS_PER_MILLI }
