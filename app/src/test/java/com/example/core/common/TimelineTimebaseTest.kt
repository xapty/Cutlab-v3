package com.example.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTimebaseTest {

    @Test
    fun `rational time converts frames millis and nanos with rounding`() {
        val oneSecAt24 = RationalTime.fromFrames(24L, 24)
        assertEquals(1_000L, oneSecAt24.toMillis())
        assertEquals(1_000_000_000L, oneSecAt24.toNanos())

        val frame1At24 = RationalTime.fromFrames(1L, 24)
        assertEquals(42L, frame1At24.toMillis()) // 41.666ms rounds to 42ms, not truncated 41ms
        assertEquals(41L, frame1At24.toTruncatedMillis())
        assertEquals(1L, frame1At24.toFrames(24))
    }

    @Test
    fun `stepFrameMillis has zero cumulative rounding drift at 24fps 30fps and 60fps`() {
        listOf(24, 30, 60).forEach { fps ->
            var ms = 0L
            repeat(fps * 10) {
                ms = TimelineTimebase.stepFrameMillis(ms, +1, fps)
            }
            assertEquals("10 seconds at ${fps}fps should land on exact 10_000ms", 10_000L, ms)

            repeat(fps * 10) {
                ms = TimelineTimebase.stepFrameMillis(ms, -1, fps)
            }
            assertEquals("Stepping back to start at ${fps}fps should land on exact 0ms", 0L, ms)
        }
    }

    @Test
    fun `frameToPtsNs stays monotonically aligned to frame grid`() {
        assertEquals(0L, TimelineTimebase.frameToPtsNs(0L, 30))
        assertEquals(1_000_000_000L, TimelineTimebase.frameToPtsNs(30L, 30))
        assertEquals(10_000_000_000L, TimelineTimebase.frameToPtsNs(240L, 24))
        assertEquals(1_000, TimelineTimebase.frameCountForDurationMs(33_333L, 30))
    }

    @Test
    fun `audioFramesDelta yields exact sample total without drift across uneven chunks`() {
        val sampleRate = 44_100
        var written = 0L
        var t = 0L
        val stepMs = 33L // purposefully non-divisible chunk size
        while (t < 1_000L) {
            val nextT = (t + stepMs).coerceAtMost(1_000L)
            val delta = TimelineTimebase.audioFramesDelta(
                windowEndMs = nextT,
                alreadyWrittenFrames = written,
                sampleRate = sampleRate,
            )
            written += delta
            t = nextT
        }
        assertEquals(44_100L, written)
    }

    @Test
    fun `speed scaling rounds cleanly instead of truncating`() {
        assertEquals(333L, TimelineTimebase.scaleSourceToTimelineMs(1_000L, 3.0f))
        assertEquals(667L, TimelineTimebase.scaleSourceToTimelineMs(2_000L, 3.0f))
        assertEquals(999L, TimelineTimebase.scaleTimelineToSourceMs(333L, 3.0f))
        assertEquals(200L, TimelineTimebase.scaleSourceToTimelineMs(300L, 1.5f))
    }
}
