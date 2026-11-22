package com.example.engine.compositor

import com.example.engine.animation.AnimationEngine
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ChromaKeySpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.ClipTransition
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MediaAsset
import com.example.engine.project.ShaderEffectType
import com.example.engine.timeline.ActiveClipLayer
import com.example.engine.timeline.ActiveTimelineFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class ResolvedVisualLayer(
    val sourceLayer: ActiveClipLayer,
    val asset: MediaAsset?,
    val zOrder: Int,
    val transform: LayerTransform,
    val blendMode: BlendModePreset,
    val mask: LayerMask,
    val effectiveAdjustments: ClipAdjustments,
    val effectiveShader: ClipShaderEffect,
    val effectiveChromaKey: ChromaKeySpec = ChromaKeySpec(),
    val effectiveLut: ClipLutSpec = ClipLutSpec(),
    val effectiveOpacity: Float,
    val transitionProgress: Float,
    val activeTransition: ClipTransition?,
)

data class ResolvedCompositionFrame(
    val playheadMs: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val layers: List<ResolvedVisualLayer>,
    val outgoingLayer: ResolvedVisualLayer?,
    val textLayers: List<ActiveClipLayer>,
    val hasActiveAdjustmentLayer: Boolean,
)

class CompositorEngine(
    private val animationEngine: AnimationEngine = AnimationEngine(),
) {

    fun resolveFrame(
        activeFrame: ActiveTimelineFrame,
        assetsById: Map<String, MediaAsset>,
        canvasWidth: Int,
        canvasHeight: Int,
    ): ResolvedCompositionFrame {
        val sortedAdjustments = activeFrame.adjustmentLayers.sortedBy { it.track.orderIndex }

        val resolvedVisuals = activeFrame.visualLayers
            .sortedBy { it.track.orderIndex }
            .mapIndexed { idx, layer ->
                resolveSingleLayer(
                    layer = layer,
                    asset = assetsById[layer.clip.assetId],
                    zOrder = idx,
                    adjustmentLayers = sortedAdjustments,
                )
            }

        val resolvedOutgoing = activeFrame.outgoingVisualLayer?.let { out ->
            resolveSingleLayer(
                layer = out,
                asset = assetsById[out.clip.assetId],
                zOrder = -1,
                adjustmentLayers = sortedAdjustments,
            )
        }

        return ResolvedCompositionFrame(
            playheadMs = activeFrame.playheadMs,
            canvasWidth = canvasWidth.coerceAtLeast(1),
            canvasHeight = canvasHeight.coerceAtLeast(1),
            layers = resolvedVisuals,
            outgoingLayer = resolvedOutgoing,
            textLayers = activeFrame.textLayers,
            hasActiveAdjustmentLayer = sortedAdjustments.isNotEmpty(),
        )
    }

    private fun resolveSingleLayer(
        layer: ActiveClipLayer,
        asset: MediaAsset?,
        zOrder: Int,
        adjustmentLayers: List<ActiveClipLayer>,
    ): ResolvedVisualLayer {
        val relMs = layer.clipRelativeMs
        val animatedTransform = animationEngine.evaluateTransform(layer.clip, relMs)
        val animatedAdjustments = animationEngine.evaluateAdjustments(layer.clip, relMs)
        var mergedAdjustments = EffectsEngine.resolveEffectiveAdjustments(animatedAdjustments)
        var mergedShader = animationEngine.evaluateShader(layer.clip, relMs)
        var mergedLut = layer.clip.lutSpec
        val animatedMask = animationEngine.evaluateMask(layer.clip, relMs)

        // Apply every adjustment clip placed above this visual track in stack order
        for (adj in adjustmentLayers) {
            if (adj.track.orderIndex > layer.track.orderIndex) {
                val adjAdjustments = animationEngine.evaluateAdjustments(adj.clip, adj.clipRelativeMs)
                mergedAdjustments = EffectsEngine.mergeAdjustments(
                    mergedAdjustments,
                    adjAdjustments,
                )
                val adjShader = animationEngine.evaluateShader(adj.clip, adj.clipRelativeMs)
                if (adjShader.isActive) {
                    mergedShader = adjShader
                }
                if (adj.clip.lutSpec.isActive) {
                    mergedLut = adj.clip.lutSpec
                }
            }
        }

        val baseOpacity = animatedTransform.opacity.coerceIn(0f, 1f)
        return ResolvedVisualLayer(
            sourceLayer = layer,
            asset = asset,
            zOrder = zOrder,
            transform = animatedTransform,
            blendMode = layer.clip.blendMode,
            mask = animatedMask,
            effectiveAdjustments = mergedAdjustments,
            effectiveShader = mergedShader,
            effectiveChromaKey = layer.clip.chromaKey,
            effectiveLut = mergedLut,
            effectiveOpacity = baseOpacity,
            transitionProgress = layer.transitionProgress,
            activeTransition = layer.activeTransition,
        )
    }

    /**
     * Evaluates whether a normalized canvas UV coordinate (0..1, 0..1) falls inside
     * the layer's configured mask (pure-Kotlin reference matching GLSL mask SDF).
     */
    fun evaluateMaskAlpha(mask: LayerMask, u: Float, v: Float): Float {
        if (!mask.isActive) return 1f
        val dx = u - mask.centerX
        val dy = v - mask.centerY
        val rad = (-mask.rotationDeg * PI / 180.0).toFloat()
        val cosR = cos(rad)
        val sinR = sin(rad)
        val rx = dx * cosR - dy * sinR
        val ry = dx * sinR + dy * cosR

        val halfW = (mask.width * 0.5f).coerceAtLeast(0.01f)
        val halfH = (mask.height * 0.5f).coerceAtLeast(0.01f)
        val feather = mask.feather.coerceIn(0.002f, 0.5f)
        val radius = mask.cornerRadius.coerceIn(0f, minOf(halfW, halfH))

        val dist = when (mask.shape) {
            com.example.engine.project.MaskShape.NONE -> -1f
            com.example.engine.project.MaskShape.RECTANGLE -> {
                val qx = kotlin.math.abs(rx) - (halfW - radius)
                val qy = kotlin.math.abs(ry) - (halfH - radius)
                val outX = maxOf(qx, 0f)
                val outY = maxOf(qy, 0f)
                kotlin.math.sqrt(outX * outX + outY * outY) + minOf(maxOf(qx, qy), 0f) - radius
            }
            com.example.engine.project.MaskShape.ELLIPSE -> {
                val nx = rx / halfW
                val ny = ry / halfH
                (kotlin.math.sqrt(nx * nx + ny * ny) - 1f) * minOf(halfW, halfH)
            }
            com.example.engine.project.MaskShape.LINEAR_SPLIT -> rx
        }

        val alpha = 1f - smoothstep(-feather, feather, dist)
        return if (mask.inverted) 1f - alpha else alpha
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun selectActiveShader(
        layerShader: ClipShaderEffect,
        adjustmentShader: ClipShaderEffect?,
    ): ClipShaderEffect {
        return if (adjustmentShader != null && adjustmentShader.type != ShaderEffectType.NONE) {
            adjustmentShader
        } else {
            layerShader
        }
    }
}
