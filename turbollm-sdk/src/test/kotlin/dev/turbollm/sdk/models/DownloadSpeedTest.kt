package dev.turbollm.sdk.models

import dev.turbollm.sdk.internal.Clock
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSpeedTest {

    private var now = 0L
    private val speed = DownloadSpeed(Clock { now }, startBytes = 0)

    @Test
    fun theSpeedIsRecomputedAtMostEveryHalfSecond() {
        now = 200
        speed.record(100)
        assertEquals(0, speed.bytesPerSec())

        now = 500
        speed.record(1_000)
        assertEquals(2_000, speed.bytesPerSec())

        now = 700
        speed.record(5_000)
        assertEquals(2_000, speed.bytesPerSec())

        now = 1_000
        speed.record(5_000)
        assertEquals(8_000, speed.bytesPerSec())
    }

    @Test
    fun theSpeedReadsZeroAfterThreeSecondsWithNoBytes() {
        now = 500
        speed.record(1_000)

        now = 3_499
        assertEquals(2_000, speed.bytesPerSec())

        now = 3_500
        assertEquals(0, speed.bytesPerSec())
    }
}
