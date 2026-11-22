package com.example.core.graphics

import com.example.engine.compositor.ResolvedVisualLayer
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.TransitionType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class TransitionVisualParams(
    val alpha: Float = 1f,
    val shiftX: Float = 0f,
    val blurRadius: Float = 0f,
    val scaleMultiplier: Float = 1f,
    val rotationOffsetDeg: Float = 0f,
    val shiftY: Float = 0f,
)

data class OutgoingTransitionParams(
    val alpha: Float = 1f,
    val shiftX: Float = 0f,
    val blurRadius: Float = 0f,
)

data class GlColorUniforms(
    val matrix4: FloatArray,
    val offset4: FloatArray,
)

/**
 * Shared pure-Kotlin OpenGL ES composition math used identically by the live preview
 * renderer ([GlCompositorRenderer]) and Path B offline exporter ([CustomGlVideoExporter]),
 * keeping layer framing, anchor pivots, transitions, and color matrix uniforms in lockstep
 * and deterministically testable on the JVM.
 */
object GlCompositorMath {

    fun computeTransitionVisualParams(layer: ResolvedVisualLayer): TransitionVisualParams {
        val transition = layer.activeTransition ?: return TransitionVisualParams()
        val p = layer.transitionProgress.coerceIn(0f, 1f)
        if (p >= 1f) return TransitionVisualParams()
        return when (transition.type) {
            TransitionType.NONE, TransitionType.WIPE_RIGHT -> TransitionVisualParams()
            TransitionType.CROSS_DISSOLVE -> TransitionVisualParams(alpha = p)
            TransitionType.FADE_BLACK -> TransitionVisualParams(
                alpha = if (p < 0.5f) 0f else (p - 0.5f) * 2f,
            )
            TransitionType.BLUR -> TransitionVisualParams(
                alpha = p,
                blurRadius = (1f - p) * 14f,
            )
            TransitionType.SLIDE_LEFT -> TransitionVisualParams(
                alpha = 1f,
                shiftX = (1f - p) * 2f,
            )
            TransitionType.SLIDE_UP -> TransitionVisualParams(
                alpha = 1f,
                shiftY = -(1f - p) * 2f,
            )
            TransitionType.ZOOM_IN -> TransitionVisualParams(
                alpha = p,
                scaleMultiplier = 0.72f + 0.28f * p,
            )
            TransitionType.SPIN -> TransitionVisualParams(
                alpha = p,
                scaleMultiplier = 0.6f + 0.4f * p,
                rotationOffsetDeg = -48f * (1f - p),
            )
            TransitionType.GLITCH -> TransitionVisualParams(
                alpha = 0.4f + 0.6f * p,
                shiftX = sin(layer.sourceLayer.clipRelativeMs * 0.14f) * (1f - p) * 0.06f,
            )
        }
    }

    fun computeOutgoingTransitionParams(primaryIncoming: ResolvedVisualLayer?): OutgoingTransitionParams {
        val transition = primaryIncoming?.activeTransition ?: return OutgoingTransitionParams()
        val p = primaryIncoming.transitionProgress.coerceIn(0f, 1f)
        if (p >= 1f) return OutgoingTransitionParams()
        return when (transition.type) {
            TransitionType.CROSS_DISSOLVE -> OutgoingTransitionParams(
                alpha = (1f - p).coerceIn(0f, 1f),
            )
            TransitionType.FADE_BLACK -> OutgoingTransitionParams(
                alpha = if (p < 0.5f) (1f - p * 2f).coerceIn(0f, 1f) else 0f,
            )
            TransitionType.BLUR -> OutgoingTransitionParams(
                alpha = (1f - p).coerceIn(0f, 1f),
                blurRadius = p * 14f,
            )
            TransitionType.SLIDE_LEFT -> OutgoingTransitionParams(
                alpha = 1f,
                shiftX = -0.3f * p,
            )
            else -> OutgoingTransitionParams(alpha = 1f)
        }
    }

    fun buildColorUniforms(adjustments: ClipAdjustments): GlColorUniforms {
        val cm20 = EffectsEngine.buildColorMatrixValues(adjustments)
        val matrix4 = floatArrayOf(
            cm20[0], cm20[5], cm20[10], 0f,
            cm20[1], cm20[6], cm20[11], 0f,
            cm20[2], cm20[7], cm20[12], 0f,
            0f, 0f, 0f, 1f,
        )
        val offset4 = floatArrayOf(cm20[4] / 255f, cm20[9] / 255f, cm20[14] / 255f, 0f)
        return GlColorUniforms(matrix4 = matrix4, offset4 = offset4)
    }

    fun buildLayerMvpMatrix(
        layer: ResolvedVisualLayer,
        assetWidth: Int,
        assetHeight: Int,
        canvasAspect: Float,
        transitionParams: TransitionVisualParams = computeTransitionVisualParams(layer),
        overrideShiftX: Float? = null,
    ): FloatArray {
        val safeCanvasAspect = canvasAspect.coerceAtLeast(0.05f)
        val assetAspect = (assetWidth.toFloat() / assetHeight.toFloat().coerceAtLeast(1f)).coerceIn(0.2f, 5f)
        val fitScaleX: Float
        val fitScaleY: Float
        if (assetAspect > safeCanvasAspect) {
            fitScaleX = 1f
            fitScaleY = safeCanvasAspect / assetAspect
        } else {
            fitScaleX = assetAspect / safeCanvasAspect
            fitScaleY = 1f
        }

        val t = layer.transform
        val shiftX = overrideShiftX ?: transitionParams.shiftX
        val extraScale = transitionParams.scaleMultiplier
        val extraRot = transitionParams.rotationOffsetDeg
        val extraY = transitionParams.shiftY

        val localAnchorX = (t.anchorX - 0.5f) * 2f
        val localAnchorY = (0.5f - t.anchorY) * 2f

        val m = identityMatrix4()
        translateInPlace(
            m,
            t.positionX * 2f + shiftX + localAnchorX * fitScaleX,
            -t.positionY * 2f + extraY + localAnchorY * fitScaleY,
            0f,
        )
        scaleInPlace(m, 1f / safeCanvasAspect, 1f, 1f)
        rotateZInPlace(m, -(t.rotationDeg + extraRot))
        scaleInPlace(m, safeCanvasAspect, 1f, 1f)
        scaleInPlace(
            m,
            fitScaleX * t.scaleX * extraScale,
            fitScaleY * t.scaleY * extraScale,
            1f,
        )
        translateInPlace(m, -localAnchorX, -localAnchorY, 0f)
        return m
    }

    fun buildTextLayerMvpMatrix(
        bitmapWidth: Int,
        bitmapHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        centerOffsetXNorm: Float,
        centerOffsetYNorm: Float,
        combinedScaleX: Float,
        combinedScaleY: Float,
        rotationDeg: Float,
        anchorX: Float = 0.5f,
        anchorY: Float = 0.5f,
    ): FloatArray {
        val safeCanvasW = canvasWidth.coerceAtLeast(1).toFloat()
        val safeCanvasH = canvasHeight.coerceAtLeast(1).toFloat()
        val canvasAspect = (safeCanvasW / safeCanvasH).coerceAtLeast(0.05f)
        val fracW = (bitmapWidth.toFloat() / safeCanvasW).coerceIn(0.01f, 2f)
        val fracH = (bitmapHeight.toFloat() / safeCanvasH).coerceIn(0.01f, 2f)
        val localAnchorX = (anchorX - 0.5f) * 2f
        val localAnchorY = (0.5f - anchorY) * 2f

        val m = identityMatrix4()
        translateInPlace(
            m,
            centerOffsetXNorm * 2f + localAnchorX * fracW,
            -centerOffsetYNorm * 2f + localAnchorY * fracH,
            0f,
        )
        scaleInPlace(m, 1f / canvasAspect, 1f, 1f)
        rotateZInPlace(m, -rotationDeg)
        scaleInPlace(m, canvasAspect, 1f, 1f)
        scaleInPlace(
            m,
            fracW * combinedScaleX,
            fracH * combinedScaleY,
            1f,
        )
        translateInPlace(m, -localAnchorX, -localAnchorY, 0f)
        return m
    }

    fun identityMatrix4(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private fun translateInPlace(m: FloatArray, tx: Float, ty: Float, tz: Float) {
        for (row in 0..3) {
            m[12 + row] += m[row] * tx + m[4 + row] * ty + m[8 + row] * tz
        }
    }

    private fun scaleInPlace(m: FloatArray, sx: Float, sy: Float, sz: Float) {
        for (row in 0..3) {
            m[row] *= sx
            m[4 + row] *= sy
            m[8 + row] *= sz
        }
    }

    private fun rotateZInPlace(m: FloatArray, angleDeg: Float) {
        if (angleDeg == 0f) return
        val rad = angleDeg * (PI / 180.0)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        for (row in 0..3) {
            val m0 = m[row]
            val m4 = m[4 + row]
            m[row] = m0 * c + m4 * s
            m[4 + row] = m0 * -s + m4 * c
        }
    }
}
