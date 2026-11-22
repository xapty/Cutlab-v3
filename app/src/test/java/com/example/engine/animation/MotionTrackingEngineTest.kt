package com.example.engine.animation

import com.example.engine.project.KeyframeProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class MotionTrackingEngineTest {

    private fun renderSquareLumaFrame(
        timestampMs: Long,
        width: Int = 96,
        height: Int = 96,
        centerX: Float,
        centerY: Float,
        boxHalfSize: Int = 6,
    ): LumaFrame {
        val data = FloatArray(width * height) { 0.1f }
        val cx = centerX.roundToInt()
        val cy = centerY.roundToInt()
        for (y in (cy - boxHalfSize)..(cy + boxHalfSize)) {
            for (x in (cx - boxHalfSize)..(cx + boxHalfSize)) {
                if (x in 0 until width && y in 0 until height) {
                    val dist = abs(x - cx) + abs(y - cy)
                    data[y * width + x] = (0.95f - dist * 0.04f).coerceIn(0.2f, 0.98f)
                }
            }
        }
        return LumaFrame(timestampMs = timestampMs, width = width, height = height, luma = data)
    }

    @Test
    fun `trackPatchSequence tracks moving bright target across frames within tight pixel error`() {
        val w = 96
        val h = 96
        val startX = 32f
        val startY = 48f
        val stepDx = 2.5f
        val stepDy = -1.5f

        val frames = (0..8).map { i ->
            renderSquareLumaFrame(
                timestampMs = i * 100L,
                width = w,
                height = h,
                centerX = startX + i * stepDx,
                centerY = startY + i * stepDy,
            )
        }

        val tracked = MotionTrackingEngine.trackPatchSequence(
            frames = frames,
            startNormX = startX / (w - 1).toFloat(),
            startNormY = startY / (h - 1).toFloat(),
        )

        assertEquals(frames.size, tracked.size)
        val last = tracked.last()
        val expectedNormX = (startX + 8 * stepDx) / (w - 1).toFloat()
        val expectedNormY = (startY + 8 * stepDy) / (h - 1).toFloat()
        assertEquals(expectedNormX, last.normX, 0.03f)
        assertEquals(expectedNormY, last.normY, 0.03f)
        assertTrue(last.confidence > 0.7f)

        val tracks = MotionTrackingEngine.trackingPointsToKeyframeTracks(tracked)
        assertEquals(2, tracks.size)
        assertEquals(KeyframeProperty.POSITION_X, tracks[0].property)
        assertEquals(KeyframeProperty.POSITION_Y, tracks[1].property)
        assertEquals(frames.size, tracks[0].keyframes.size)
    }

    @Test
    fun `computeStabilizationTransforms detects camera shake and computes compensatory offsets and cropScale`() {
        val w = 96
        val h = 96
        // Textured grid pattern that shifts coherently across frames with high-frequency jitter
        fun shiftedGridFrame(i: Int, shiftX: Float, shiftY: Float): LumaFrame {
            val luma = FloatArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val sx = x - shiftX
                    val sy = y - shiftY
                    val pattern = 0.5f + 0.35f * sin(sx * 0.35f) * sin(sy * 0.35f)
                    luma[y * w + x] = pattern.coerceIn(0f, 1f)
                }
            }
            return LumaFrame(i * 50L, w, h, luma)
        }

        val frames = (0..12).map { i ->
            val jitter = if (i % 2 == 0) 3.0f else -3.0f
            shiftedGridFrame(i, shiftX = jitter, shiftY = jitter * 0.5f)
        }

        val (cropScale, samples) = MotionTrackingEngine.computeStabilizationTransforms(frames, strength = 0.85f)
        assertEquals(frames.size, samples.size)
        assertTrue("Auto crop scale must overscan > 1.0x when compensating shake", cropScale > 1.02f)

        val maxAbsCorrection = samples.maxOf { abs(it.offsetX) }
        assertTrue("Stabilizer should produce measurable compensatory X shifts", maxAbsCorrection > 0.01f)

        val stabTracks = MotionTrackingEngine.stabilizationToKeyframeTracks(samples, cropScale)
        assertEquals(4, stabTracks.size) // POSITION_X, POSITION_Y, SCALE_X, SCALE_Y
        assertTrue(stabTracks.any { it.property == KeyframeProperty.SCALE_X && abs(it.keyframes.first().value - cropScale) < 0.001f })
    }
}
