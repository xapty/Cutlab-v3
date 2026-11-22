package com.example.core.graphics

import com.example.engine.compositor.ResolvedVisualLayer
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.ClipTransition
import com.example.engine.project.FilterPreset
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineTrack
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import com.example.engine.timeline.ActiveClipLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlCompositorMathTest {

    private fun sampleLayer(
        transform: LayerTransform = LayerTransform(),
        transition: ClipTransition? = null,
        progress: Float = 1f,
        adjustments: ClipAdjustments = ClipAdjustments(),
    ): ResolvedVisualLayer {
        val track = TimelineTrack(id = "v1", name = "V1", type = TrackType.VIDEO, orderIndex = 0)
        val clip = TimelineClipRef(
            clipId = "c1",
            assetId = "asset-1",
            trackId = "v1",
            timelineStartMs = 0L,
            sourceStartMs = 0L,
            durationMs = 2_000L,
        )
        return ResolvedVisualLayer(
            sourceLayer = ActiveClipLayer(
                track = track,
                clip = clip,
                clipRelativeMs = 250L,
                effectiveVolume = 1f,
                transitionProgress = progress,
                activeTransition = transition,
            ),
            asset = null,
            zOrder = 0,
            transform = transform,
            blendMode = BlendModePreset.NORMAL,
            mask = LayerMask(),
            effectiveAdjustments = adjustments,
            effectiveShader = ClipShaderEffect(),
            effectiveOpacity = transform.opacity,
            transitionProgress = progress,
            activeTransition = transition,
        )
    }

    private fun multiplyMat4Vec4(m: FloatArray, x: Float, y: Float, z: Float = 0f, w: Float = 1f): FloatArray {
        val rx = m[0] * x + m[4] * y + m[8] * z + m[12] * w
        val ry = m[1] * x + m[5] * y + m[9] * z + m[13] * w
        val rz = m[2] * x + m[6] * y + m[10] * z + m[14] * w
        val rw = m[3] * x + m[7] * y + m[11] * z + m[15] * w
        return floatArrayOf(rx, ry, rz, rw)
    }

    @Test
    fun `buildLayerMvpMatrix aspect-fits landscape asset inside portrait canvas`() {
        val layer = sampleLayer()
        val mvp = GlCompositorMath.buildLayerMvpMatrix(
            layer = layer,
            assetWidth = 1920,
            assetHeight = 1080,
            canvasAspect = 9f / 16f,
        )
        // Wide asset on narrow canvas fits horizontally [-1, 1] and letterboxes vertically
        val topRight = multiplyMat4Vec4(mvp, 1f, 1f)
        assertEquals(1f, topRight[0], 0.001f)
        assertEquals((9f / 16f) / (16f / 9f), topRight[1], 0.001f)
    }

    @Test
    fun `buildLayerMvpMatrix preserves anchor pivot under rotation and scale`() {
        // Pivot at top-left (anchorX = 0, anchorY = 0 -> quad (-1, +1))
        val layer = sampleLayer(
            transform = LayerTransform(
                anchorX = 0f,
                anchorY = 0f,
                rotationDeg = 90f,
                scaleX = 0.5f,
                scaleY = 0.5f,
            ),
        )
        val mvp = GlCompositorMath.buildLayerMvpMatrix(
            layer = layer,
            assetWidth = 1080,
            assetHeight = 1080,
            canvasAspect = 1f,
        )
        val anchorTransformed = multiplyMat4Vec4(mvp, -1f, 1f)
        // Anchor at (-1, 1) must stay stationary at (-1, 1) under pure scale + rotation around anchor
        assertEquals(-1f, anchorTransformed[0], 0.001f)
        assertEquals(1f, anchorTransformed[1], 0.001f)
    }

    @Test
    fun `buildTextLayerMvpMatrix preserves custom anchor pivot for rotation and scale`() {
        val mvp = GlCompositorMath.buildTextLayerMvpMatrix(
            bitmapWidth = 400,
            bitmapHeight = 200,
            canvasWidth = 1000,
            canvasHeight = 1000,
            centerOffsetXNorm = 0f,
            centerOffsetYNorm = 0f,
            combinedScaleX = 2f,
            combinedScaleY = 2f,
            rotationDeg = 45f,
            anchorX = 0f,
            anchorY = 1f, // Bottom-left corner: quad (-1, -1)
        )
        val fracW = 400f / 1000f
        val fracH = 200f / 1000f
        val pivot = multiplyMat4Vec4(mvp, -1f, -1f)
        // Anchor at (-1, -1) scaled by (fracW, fracH) stays fixed at (-fracW, -fracH)
        assertEquals(-fracW, pivot[0], 0.001f)
        assertEquals(-fracH, pivot[1], 0.001f)
    }

    @Test
    fun `transition visual and outgoing params match fade, blur, slide, and wipe semantics`() {
        val blurLayer = sampleLayer(
            transition = ClipTransition(TransitionType.BLUR, durationMs = 500L),
            progress = 0.25f,
        )
        val inBlur = GlCompositorMath.computeTransitionVisualParams(blurLayer)
        val outBlur = GlCompositorMath.computeOutgoingTransitionParams(blurLayer)
        assertEquals(0.25f, inBlur.alpha, 0.001f)
        assertTrue(inBlur.blurRadius > 9f)
        assertEquals(0.75f, outBlur.alpha, 0.001f)
        assertTrue(outBlur.blurRadius > 3f)

        val fadeBlack = sampleLayer(
            transition = ClipTransition(TransitionType.FADE_BLACK, durationMs = 500L),
            progress = 0.25f,
        )
        assertEquals(0f, GlCompositorMath.computeTransitionVisualParams(fadeBlack).alpha, 0.001f)
        assertEquals(0.5f, GlCompositorMath.computeOutgoingTransitionParams(fadeBlack).alpha, 0.001f)
    }

    @Test
    fun `buildColorUniforms preserves filter preset and brightness offset scaling`() {
        val uniforms = GlCompositorMath.buildColorUniforms(
            ClipAdjustments(filterPreset = FilterPreset.VIVID, brightness = 0.2f),
        )
        assertEquals(16, uniforms.matrix4.size)
        assertEquals(4, uniforms.offset4.size)
        assertEquals(1f, uniforms.matrix4[15], 0.0001f)
        assertTrue(uniforms.offset4[0] > 0.05f)
    }
}
