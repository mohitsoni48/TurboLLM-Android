package dev.turbollm.sdk.models

import dev.turbollm.sdk.internal.Clock

private const val MEASURE_OVER_MILLIS = 500L
private const val STALLED_AFTER_MILLIS = 3_000L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * A download's speed, as the daemon's `downloads.ts` measures it: recomputed at most every half second as bytes
 * arrive, and read as 0 once no byte has arrived for three seconds (`SPEED_STALE_MS`).
 */
internal class DownloadSpeed(private val clock: Clock, startBytes: Long) {
    private var measuredAt = clock.nowMillis()
    private var measuredBytes = startBytes
    private var lastBytesAt = measuredAt
    private var bytesPerSec = 0L

    /** [bytes] is the running total received so far. */
    fun record(bytes: Long) {
        val now = clock.nowMillis()
        lastBytesAt = now
        val elapsed = now - measuredAt
        if (elapsed < MEASURE_OVER_MILLIS) return
        bytesPerSec = (bytes - measuredBytes) * MILLIS_PER_SECOND / elapsed
        measuredAt = now
        measuredBytes = bytes
    }

    fun bytesPerSec(): Long = if (clock.nowMillis() - lastBytesAt >= STALLED_AFTER_MILLIS) 0 else bytesPerSec
}
