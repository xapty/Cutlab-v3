package com.example.engine.animation

import com.example.engine.project.BezierCurveSpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.KeyframeTrack
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MaskShape
import com.example.engine.project.MotionPreset
import com.example.engine.project.PropertyKeyframe
import com.example.engine.project.ShaderEffectType
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TimelineClipRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationEngineTest {

    private val engine = AnimationEngine()

    private fun baseClip(
        durationMs: Long = 2_000L,
        tracks: List<KeyframeTrack> = emptyList(),
    ): TimelineClipRef = TimelineClipRef(
        clipId = "clip-1",
        assetId = "asset-1",
        trackId = "track-1",
        timelineStartMs = 0L,
        sourceStartMs = 0L,
        durationMs = durationMs,
        sourceTotalDurationMs = durationMs,
        clipKind = ClipKind.MEDIA,
        transform = LayerTransform(scaleX = 1.0f, scaleY = 1.0f, opacity = 1.0f),
        adjustments = ClipAdjustments(blurRadius = 2.0f),
        shaderEffect = ClipShaderEffect(ShaderEffectType.GLOW, intensity = 0.5f),
        mask = LayerMask(shape = MaskShape.ELLIPSE, feather = 0.08f),
        volume = 0.9f,
        keyframeTracks = tracks,
    )

    @Test
    fun `evaluateCubicBezier handles linear identity, ease-out, and overshoot curves`() {
        val linear = BezierCurveSpec(0f, 0f, 1f, 1f)
        assertEquals(0f, engine.evaluateCubicBezier(0f, linear), 0.001f)
        assertEquals(0.25f, engine.evaluateCubicBezier(0.25f, linear), 0.002f)
        assertEquals(0.5f, engine.evaluateCubicBezier(0.5f, linear), 0.002f)
        assertEquals(0.75f, engine.evaluateCubicBezier(0.75f, linear), 0.002f)
        assertEquals(1f, engine.evaluateCubicBezier(1f, linear), 0.001f)

        val easeOut = BezierCurveSpec(0.16f, 1.0f, 0.3f, 1.0f)
        assertTrue(engine.evaluateCubicBezier(0.3f, easeOut) > 0.5f)

        val overshoot = BezierCurveSpec(0.34f, 1.55f, 0.64f, 1.0f)
        assertTrue(engine.evaluateCubicBezier(0.55f, overshoot) > 1.0f)
    }

    @Test
    fun `evaluateTrack interpolates Linear, EaseIn, EaseOut, EaseInOut, Hold, and clamps endpoints`() {
        val linearTrack = KeyframeTrack(
            property = KeyframeProperty.POSITION_X,
            keyframes = listOf(
                PropertyKeyframe("k1", 500L, 0.0f, KeyframeInterpolation.LINEAR),
                PropertyKeyframe("k2", 1_500L, 1.0f, KeyframeInterpolation.LINEAR),
            ),
        )
        assertEquals(0.0f, engine.evaluateTrack(linearTrack, 0L)!!, 0.001f)
        assertEquals(0.5f, engine.evaluateTrack(linearTrack, 1_000L)!!, 0.001f)
        assertEquals(1.0f, engine.evaluateTrack(linearTrack, 2_000L)!!, 0.001f)

        val easeInTrack = linearTrack.copy(
            keyframes = listOf(
                PropertyKeyframe("k1", 0L, 0f, KeyframeInterpolation.EASE_IN),
                PropertyKeyframe("k2", 1_000L, 1f, KeyframeInterpolation.LINEAR),
            ),
        )
        assertEquals(0.25f, engine.evaluateTrack(easeInTrack, 500L)!!, 0.002f)

        val easeOutTrack = linearTrack.copy(
            keyframes = listOf(
                PropertyKeyframe("k1", 0L, 0f, KeyframeInterpolation.EASE_OUT),
                PropertyKeyframe("k2", 1_000L, 1f, KeyframeInterpolation.LINEAR),
            ),
        )
        assertEquals(0.75f, engine.evaluateTrack(easeOutTrack, 500L)!!, 0.002f)

        val holdTrack = linearTrack.copy(
            keyframes = listOf(
                PropertyKeyframe("k1", 0L, 0.2f, KeyframeInterpolation.HOLD),
                PropertyKeyframe("k2", 1_000L, 0.9f, KeyframeInterpolation.LINEAR),
            ),
        )
        assertEquals(0.2f, engine.evaluateTrack(holdTrack, 950L)!!, 0.001f)
        assertEquals(0.9f, engine.evaluateTrack(holdTrack, 1_000L)!!, 0.001f)
    }

    @Test
    fun `evaluateTransform, adjustments, shader, mask, volume, and textSpec reflect animated tracks`() {
        val clip = baseClip(
            durationMs = 2_000L,
            tracks = listOf(
                KeyframeTrack(
                    KeyframeProperty.SCALE_X,
                    listOf(
                        PropertyKeyframe("s1", 0L, 1.0f, KeyframeInterpolation.LINEAR),
                        PropertyKeyframe("s2", 2_000L, 2.0f, KeyframeInterpolation.LINEAR),
                    ),
                ),
                KeyframeTrack(
                    KeyframeProperty.ROTATION,
                    listOf(
                        PropertyKeyframe("r1", 0L, 0f, KeyframeInterpolation.LINEAR),
                        PropertyKeyframe("r2", 2_000L, 90f, KeyframeInterpolation.LINEAR),
                    ),
                ),
                KeyframeTrack(
                    KeyframeProperty.OPACITY,
                    listOf(
                        PropertyKeyframe("o1", 0L, 0f, KeyframeInterpolation.LINEAR),
                        PropertyKeyframe("o2", 2_000L, 1f, KeyframeInterpolation.LINEAR),
                    ),
                ),
                KeyframeTrack(
                    KeyframeProperty.BLUR,
                    listOf(
                        PropertyKeyframe("b1", 0L, 0f, KeyframeInterpolation.LINEAR),
                        PropertyKeyframe("b2", 2_000L, 12f, KeyframeInterpolation.LINEAR),
                    ),
                ),
                KeyframeTrack(
                    KeyframeProperty.SHADER_INTENSITY,
                    listOf(
                        PropertyKeyframe("g1", 0L, 0.2f, KeyframeInterpolation.LINEAR),
                        PropertyKeyframe("g2", 2_000L, 0.8f, KeyframeInterpolation.LINEAR),
                    ),
                ),
                KeyframeTrack(
                    KeyframeProperty.MASK_FEATHER,
                    listOf(
                        PropertyKeyframe("m1", 0L, 0.0f, KeyframeInterpolation.LINEAR),
                        PropertyKeyframe("m2", 2_000L, 0.3f, KeyframeInterpolation.LINEAR),
                    ),
                ),
                KeyframeTrack(
                    KeyframeProperty.VOLUME,
                    listOf(
                        PropertyKeyframe("v1", 0L, 0.0f, KeyframeInterpolation.LINEAR),
                        PropertyKeyframe("v2", 2_000L, 1.0f, KeyframeInterpolation.LINEAR),
                    ),
                ),
                KeyframeTrack(
                    KeyframeProperty.TEXT_FONT_SCALE,
                    listOf(
                        PropertyKeyframe("t1", 0L, 1.0f, KeyframeInterpolation.LINEAR),
                        PropertyKeyframe("t2", 2_000L, 2.0f, KeyframeInterpolation.LINEAR),
                    ),
                ),
            ),
        ).copy(
            clipKind = ClipKind.TEXT,
            textSpec = TextOverlaySpec(text = "TITLE", fontScale = 1.0f),
        )

        val midTransform = engine.evaluateTransform(clip, 1_000L)
        assertEquals(1.5f, midTransform.scaleX, 0.01f)
        assertEquals(45f, midTransform.rotationDeg, 0.5f)
        assertEquals(0.5f, midTransform.opacity, 0.01f)

        assertEquals(6f, engine.evaluateAdjustments(clip, 1_000L).blurRadius, 0.05f)
        assertEquals(0.5f, engine.evaluateShader(clip, 1_000L).intensity, 0.01f)
        assertEquals(0.15f, engine.evaluateMask(clip, 1_000L).feather, 0.01f)
        assertEquals(0.5f, engine.evaluateVolume(clip, 1_000L), 0.01f)
        assertEquals(1.5f, engine.evaluateTextSpec(clip, 1_000L)!!.fontScale, 0.01f)

        val samples = engine.sampleCurvePoints(clip, KeyframeProperty.SCALE_X, sampleCount = 20)
        assertEquals(21, samples.size)
        assertEquals(1.0f, samples.first().second, 0.01f)
        assertEquals(2.0f, samples.last().second, 0.01f)
    }

    @Test
    fun `all MotionPreset entries generate sorted in-bounds keyframe tracks`() {
        val clip = baseClip(durationMs = 2_400L)
        MotionPreset.entries.forEach { preset ->
            val tracks = engine.generatePresetKeyframeTracks(clip, preset)
            assertTrue("Expected tracks for ${preset.name}", tracks.isNotEmpty())
            tracks.forEach { track ->
                assertTrue("Expected keyframes in ${track.property}", track.keyframes.isNotEmpty())
                val times = track.keyframes.map { it.timeMs }
                assertEquals(times.sorted(), times)
                assertTrue(times.first() >= 0L)
                assertTrue(times.last() <= clip.durationMs)
            }
        }
    }

    @Test
    fun `SafePropertyExpression evaluates deterministic offsets cleanly`() {
        assertEquals(0f, SafePropertyExpression.None.evaluateOffset(1.5f), 0.001f)
        assertEquals(0f, SafePropertyExpression.SineWave(10f, 1f).evaluateOffset(0.5f), 0.01f)
        assertEquals(10f, SafePropertyExpression.SineWave(10f, 1f).evaluateOffset(0.25f), 0.01f)
        assertEquals(6f, SafePropertyExpression.LinearDrift(3f).evaluateOffset(2.0f), 0.001f)
    }
}
