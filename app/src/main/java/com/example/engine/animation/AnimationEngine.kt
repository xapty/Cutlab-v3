package com.example.engine.animation

import com.example.engine.project.BezierCurveSpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.KeyframeTrack
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MotionPreset
import com.example.engine.project.PropertyKeyframe
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TimelineClipRef
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Safe declarative expression descriptor for future motion-graphics extensions.
 * Never executes arbitrary untrusted script strings.
 */
sealed interface SafePropertyExpression {
    data object None : SafePropertyExpression
    data class SineWave(val amplitude: Float, val frequencyHz: Float, val phaseRad: Float = 0f) : SafePropertyExpression
    data class LinearDrift(val ratePerSec: Float) : SafePropertyExpression

    fun evaluateOffset(timeSec: Float): Float = when (this) {
        None -> 0f
        is SineWave -> amplitude * sin(2.0 * PI * frequencyHz * timeSec + phaseRad).toFloat()
        is LinearDrift -> ratePerSec * timeSec
    }
}

class AnimationEngine {

    fun evaluateProperty(
        clip: TimelineClipRef,
        property: KeyframeProperty,
        clipRelativeMs: Long,
    ): Float {
        val track = clip.keyframeTracks.firstOrNull { it.property == property }
        val animated = track?.let { evaluateTrack(it, clipRelativeMs) }
        return animated ?: basePropertyValue(clip, property)
    }

    fun evaluateTrack(
        track: KeyframeTrack,
        clipRelativeMs: Long,
    ): Float? {
        if (track.keyframes.isEmpty()) return null
        val sorted = track.keyframes.sortedBy { it.timeMs }
        if (sorted.size == 1) return sorted.first().value

        if (clipRelativeMs <= sorted.first().timeMs) return sorted.first().value
        if (clipRelativeMs >= sorted.last().timeMs) return sorted.last().value

        for (i in 0 until sorted.lastIndex) {
            val k0 = sorted[i]
            val k1 = sorted[i + 1]
            if (clipRelativeMs in k0.timeMs..k1.timeMs) {
                val span = (k1.timeMs - k0.timeMs).coerceAtLeast(1L)
                val rawT = ((clipRelativeMs - k0.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                val easedT = evaluateSegmentT(rawT, k0.interpolation, k0.bezier)
                return k0.value + (k1.value - k0.value) * easedT
            }
        }
        return sorted.last().value
    }

    fun evaluateSegmentT(
        rawT: Float,
        interpolation: KeyframeInterpolation,
        bezier: BezierCurveSpec = BezierCurveSpec(),
    ): Float {
        val t = rawT.coerceIn(0f, 1f)
        return when (interpolation) {
            KeyframeInterpolation.LINEAR -> t
            KeyframeInterpolation.EASE_IN -> t * t
            KeyframeInterpolation.EASE_OUT -> 1f - (1f - t) * (1f - t)
            KeyframeInterpolation.EASE_IN_OUT -> if (t < 0.5f) {
                2f * t * t
            } else {
                1f - (-2f * t + 2f).pow(2) / 2f
            }
            KeyframeInterpolation.CUBIC_BEZIER -> evaluateCubicBezier(t, bezier)
            KeyframeInterpolation.HOLD -> if (t >= 1f) 1f else 0f
        }
    }

    /**
     * Solves parametric cubic Bezier B_x(u) = targetX for u in [0, 1] using
     * Newton-Raphson + binary search fallback, then returns B_y(u).
     * Allows y1/y2 outside [0, 1] for overshoot & anticipation motion curves.
     */
    fun evaluateCubicBezier(targetX: Float, spec: BezierCurveSpec): Float {
        val x = targetX.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f

        val x1 = spec.x1.coerceIn(0.0f, 1.0f)
        val x2 = spec.x2.coerceIn(0.0f, 1.0f)

        var u = x
        // Newton-Raphson iterations
        for (iter in 0 until 8) {
            val currentX = sampleBezierAxis(u, x1, x2) - x
            if (abs(currentX) < 1e-5f) {
                return sampleBezierAxis(u, spec.y1, spec.y2)
            }
            val dX = sampleBezierDerivative(u, x1, x2)
            if (abs(dX) < 1e-5f) break
            u = (u - currentX / dX).coerceIn(0f, 1f)
        }

        // Binary search fallback to guarantee monotonicity convergence
        var lo = 0f
        var hi = 1f
        var mid = u
        for (iter in 0 until 16) {
            val valX = sampleBezierAxis(mid, x1, x2)
            if (abs(valX - x) < 1e-5f) break
            if (valX < x) lo = mid else hi = mid
            mid = 0.5f * (lo + hi)
        }
        return sampleBezierAxis(mid, spec.y1, spec.y2)
    }

    private fun sampleBezierAxis(u: Float, p1: Float, p2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * u * p1 +
            3f * inv * u * u * p2 +
            u * u * u
    }

    private fun sampleBezierDerivative(u: Float, p1: Float, p2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * p1 +
            6f * inv * u * (p2 - p1) +
            3f * u * u * (1f - p2)
    }

    fun evaluateTransform(
        clip: TimelineClipRef,
        clipRelativeMs: Long,
    ): LayerTransform {
        if (clip.keyframeTracks.isEmpty()) return clip.transform
        val base = clip.transform
        return base.copy(
            positionX = evaluateProperty(clip, KeyframeProperty.POSITION_X, clipRelativeMs).coerceIn(-1f, 1f),
            positionY = evaluateProperty(clip, KeyframeProperty.POSITION_Y, clipRelativeMs).coerceIn(-1f, 1f),
            scaleX = evaluateProperty(clip, KeyframeProperty.SCALE_X, clipRelativeMs).coerceIn(0.1f, 4.0f),
            scaleY = evaluateProperty(clip, KeyframeProperty.SCALE_Y, clipRelativeMs).coerceIn(0.1f, 4.0f),
            rotationDeg = evaluateProperty(clip, KeyframeProperty.ROTATION, clipRelativeMs).coerceIn(-360f, 360f),
            opacity = evaluateProperty(clip, KeyframeProperty.OPACITY, clipRelativeMs).coerceIn(0f, 1f),
            anchorX = evaluateProperty(clip, KeyframeProperty.ANCHOR_X, clipRelativeMs).coerceIn(0f, 1f),
            anchorY = evaluateProperty(clip, KeyframeProperty.ANCHOR_Y, clipRelativeMs).coerceIn(0f, 1f),
        )
    }

    fun evaluateAdjustments(
        clip: TimelineClipRef,
        clipRelativeMs: Long,
    ): ClipAdjustments {
        if (clip.keyframeTracks.none { it.property == KeyframeProperty.BLUR }) {
            return clip.adjustments
        }
        val blur = evaluateProperty(clip, KeyframeProperty.BLUR, clipRelativeMs).coerceIn(0f, 24f)
        return clip.adjustments.copy(blurRadius = blur)
    }

    fun evaluateShader(
        clip: TimelineClipRef,
        clipRelativeMs: Long,
    ): ClipShaderEffect {
        if (clip.keyframeTracks.none { it.property == KeyframeProperty.SHADER_INTENSITY }) {
            return clip.shaderEffect
        }
        val intensity = evaluateProperty(clip, KeyframeProperty.SHADER_INTENSITY, clipRelativeMs).coerceIn(0f, 1f)
        return clip.shaderEffect.copy(intensity = intensity)
    }

    fun evaluateMask(
        clip: TimelineClipRef,
        clipRelativeMs: Long,
    ): LayerMask {
        if (clip.keyframeTracks.none { it.property == KeyframeProperty.MASK_FEATHER }) {
            return clip.mask
        }
        val feather = evaluateProperty(clip, KeyframeProperty.MASK_FEATHER, clipRelativeMs).coerceIn(0f, 0.5f)
        return clip.mask.copy(feather = feather)
    }

    fun evaluateVolume(
        clip: TimelineClipRef,
        clipRelativeMs: Long,
    ): Float {
        if (clip.keyframeTracks.none { it.property == KeyframeProperty.VOLUME }) {
            return clip.volume
        }
        return evaluateProperty(clip, KeyframeProperty.VOLUME, clipRelativeMs).coerceIn(0f, 2f)
    }

    fun evaluateTextSpec(
        clip: TimelineClipRef,
        clipRelativeMs: Long,
    ): TextOverlaySpec? {
        val spec = clip.textSpec ?: return null
        if (clip.keyframeTracks.none { it.property == KeyframeProperty.TEXT_FONT_SCALE }) {
            return spec
        }
        val scale = evaluateProperty(clip, KeyframeProperty.TEXT_FONT_SCALE, clipRelativeMs).coerceIn(0.5f, 2.5f)
        return spec.copy(fontScale = scale)
    }

    fun sampleCurvePoints(
        clip: TimelineClipRef,
        property: KeyframeProperty,
        sampleCount: Int = 64,
    ): List<Pair<Long, Float>> {
        val dur = clip.durationMs.coerceAtLeast(1L)
        val count = sampleCount.coerceIn(8, 256)
        val track = clip.keyframeTracks.firstOrNull { it.property == property }
        val fallback = basePropertyValue(clip, property)
        return (0..count).map { step ->
            val tMs = (dur * step) / count
            val v = track?.let { evaluateTrack(it, tMs) } ?: fallback
            tMs to v
        }
    }

    fun basePropertyValue(clip: TimelineClipRef, property: KeyframeProperty): Float = when (property) {
        KeyframeProperty.POSITION_X -> clip.transform.positionX
        KeyframeProperty.POSITION_Y -> clip.transform.positionY
        KeyframeProperty.SCALE_X -> clip.transform.scaleX
        KeyframeProperty.SCALE_Y -> clip.transform.scaleY
        KeyframeProperty.ROTATION -> clip.transform.rotationDeg
        KeyframeProperty.OPACITY -> clip.transform.opacity
        KeyframeProperty.ANCHOR_X -> clip.transform.anchorX
        KeyframeProperty.ANCHOR_Y -> clip.transform.anchorY
        KeyframeProperty.VOLUME -> clip.volume
        KeyframeProperty.BLUR -> clip.adjustments.blurRadius
        KeyframeProperty.SHADER_INTENSITY -> clip.shaderEffect.intensity
        KeyframeProperty.MASK_FEATHER -> clip.mask.feather
        KeyframeProperty.TEXT_FONT_SCALE -> clip.textSpec?.fontScale ?: 1f
    }

    fun generatePresetKeyframeTracks(
        clip: TimelineClipRef,
        preset: MotionPreset,
    ): List<KeyframeTrack> {
        val dur = clip.durationMs.coerceAtLeast(300L)
        val overshootBezier = BezierCurveSpec(0.34f, 1.48f, 0.64f, 1.0f)
        val snapEaseOut = BezierCurveSpec(0.16f, 1.0f, 0.3f, 1.0f)

        val generated: List<KeyframeTrack> = when (preset) {
            MotionPreset.OVERSHOOT -> {
                val endT = (dur * 0.45f).toLong().coerceIn(150L, dur)
                listOf(
                    KeyframeTrack(
                        KeyframeProperty.SCALE_X,
                        listOf(
                            kf(0L, 0.55f, KeyframeInterpolation.CUBIC_BEZIER, overshootBezier),
                            kf(endT, 1.0f, KeyframeInterpolation.EASE_OUT),
                        ),
                    ),
                    KeyframeTrack(
                        KeyframeProperty.SCALE_Y,
                        listOf(
                            kf(0L, 0.55f, KeyframeInterpolation.CUBIC_BEZIER, overshootBezier),
                            kf(endT, 1.0f, KeyframeInterpolation.EASE_OUT),
                        ),
                    ),
                )
            }
            MotionPreset.BOUNCE -> {
                val t1 = (dur * 0.22f).toLong()
                val t2 = (dur * 0.40f).toLong()
                val t3 = (dur * 0.55f).toLong()
                val t4 = (dur * 0.68f).toLong()
                listOf(
                    KeyframeTrack(
                        KeyframeProperty.POSITION_Y,
                        listOf(
                            kf(0L, -0.45f, KeyframeInterpolation.EASE_IN),
                            kf(t1, 0.0f, KeyframeInterpolation.EASE_OUT),
                            kf(t2, -0.18f, KeyframeInterpolation.EASE_IN),
                            kf(t3, 0.0f, KeyframeInterpolation.EASE_OUT),
                            kf(t4, -0.05f, KeyframeInterpolation.EASE_IN_OUT),
                            kf((dur * 0.78f).toLong(), 0.0f, KeyframeInterpolation.LINEAR),
                        ),
                    ),
                )
            }
            MotionPreset.ELASTIC -> {
                val t1 = (dur * 0.18f).toLong()
                val t2 = (dur * 0.36f).toLong()
                val t3 = (dur * 0.52f).toLong()
                val t4 = (dur * 0.68f).toLong()
                listOf(
                    KeyframeTrack(
                        KeyframeProperty.ROTATION,
                        listOf(
                            kf(0L, -18f, KeyframeInterpolation.EASE_IN_OUT),
                            kf(t1, 12f, KeyframeInterpolation.EASE_IN_OUT),
                            kf(t2, -7f, KeyframeInterpolation.EASE_IN_OUT),
                            kf(t3, 3.5f, KeyframeInterpolation.EASE_IN_OUT),
                            kf(t4, 0f, KeyframeInterpolation.LINEAR),
                        ),
                    ),
                    KeyframeTrack(
                        KeyframeProperty.SCALE_X,
                        listOf(
                            kf(0L, 0.7f, KeyframeInterpolation.CUBIC_BEZIER, overshootBezier),
                            kf(t2, 1.0f, KeyframeInterpolation.EASE_OUT),
                        ),
                    ),
                    KeyframeTrack(
                        KeyframeProperty.SCALE_Y,
                        listOf(
                            kf(0L, 0.7f, KeyframeInterpolation.CUBIC_BEZIER, overshootBezier),
                            kf(t2, 1.0f, KeyframeInterpolation.EASE_OUT),
                        ),
                    ),
                )
            }
            MotionPreset.SHAKE -> {
                val steps = 8
                val span = (dur * 0.55f).toLong().coerceAtLeast(320L)
                val xKeys = (0..steps).map { i ->
                    val t = (span * i) / steps
                    val decay = 1f - (i.toFloat() / steps)
                    val sign = if (i == 0 || i == steps) 0f else if (i % 2 == 0) 1f else -1f
                    kf(t, sign * 0.055f * decay, KeyframeInterpolation.EASE_IN_OUT)
                }
                val rotKeys = (0..steps).map { i ->
                    val t = (span * i) / steps
                    val decay = 1f - (i.toFloat() / steps)
                    val sign = if (i == 0 || i == steps) 0f else if (i % 2 == 0) -1f else 1f
                    kf(t, sign * 4.5f * decay, KeyframeInterpolation.EASE_IN_OUT)
                }
                listOf(
                    KeyframeTrack(KeyframeProperty.POSITION_X, xKeys),
                    KeyframeTrack(KeyframeProperty.ROTATION, rotKeys),
                )
            }
            MotionPreset.SMOOTH_ZOOM -> listOf(
                KeyframeTrack(
                    KeyframeProperty.SCALE_X,
                    listOf(
                        kf(0L, 1.0f, KeyframeInterpolation.CUBIC_BEZIER, BezierCurveSpec(0.25f, 0.1f, 0.25f, 1.0f)),
                        kf(dur, 1.24f, KeyframeInterpolation.LINEAR),
                    ),
                ),
                KeyframeTrack(
                    KeyframeProperty.SCALE_Y,
                    listOf(
                        kf(0L, 1.0f, KeyframeInterpolation.CUBIC_BEZIER, BezierCurveSpec(0.25f, 0.1f, 0.25f, 1.0f)),
                        kf(dur, 1.24f, KeyframeInterpolation.LINEAR),
                    ),
                ),
            )
            MotionPreset.CAMERA_PAN -> listOf(
                KeyframeTrack(
                    KeyframeProperty.SCALE_X,
                    listOf(kf(0L, 1.15f, KeyframeInterpolation.LINEAR)),
                ),
                KeyframeTrack(
                    KeyframeProperty.SCALE_Y,
                    listOf(kf(0L, 1.15f, KeyframeInterpolation.LINEAR)),
                ),
                KeyframeTrack(
                    KeyframeProperty.POSITION_X,
                    listOf(
                        kf(0L, -0.07f, KeyframeInterpolation.EASE_IN_OUT),
                        kf(dur, 0.07f, KeyframeInterpolation.LINEAR),
                    ),
                ),
            )
            MotionPreset.FADE_IN_OUT -> {
                val fadeIn = (dur * 0.25f).toLong().coerceIn(120L, 600L)
                val fadeOutStart = (dur - fadeIn).coerceAtLeast(fadeIn)
                listOf(
                    KeyframeTrack(
                        KeyframeProperty.OPACITY,
                        listOf(
                            kf(0L, 0f, KeyframeInterpolation.EASE_OUT),
                            kf(fadeIn, 1f, KeyframeInterpolation.LINEAR),
                            kf(fadeOutStart, 1f, KeyframeInterpolation.EASE_IN),
                            kf(dur, 0f, KeyframeInterpolation.LINEAR),
                        ),
                    ),
                )
            }
            MotionPreset.SLIDE_IN -> {
                val endT = (dur * 0.35f).toLong().coerceIn(150L, 700L)
                listOf(
                    KeyframeTrack(
                        KeyframeProperty.POSITION_X,
                        listOf(
                            kf(0L, -0.55f, KeyframeInterpolation.CUBIC_BEZIER, snapEaseOut),
                            kf(endT, 0f, KeyframeInterpolation.LINEAR),
                        ),
                    ),
                    KeyframeTrack(
                        KeyframeProperty.OPACITY,
                        listOf(
                            kf(0L, 0f, KeyframeInterpolation.EASE_OUT),
                            kf((endT * 0.7f).toLong(), 1f, KeyframeInterpolation.LINEAR),
                        ),
                    ),
                )
            }
        }

        // Merge generated tracks onto existing unrelated tracks on the clip
        val replacedProps = generated.map { it.property }.toSet()
        val preserved = clip.keyframeTracks.filterNot { it.property in replacedProps }
        return (preserved + generated).sortedBy { it.property.ordinal }
    }

    private fun kf(
        timeMs: Long,
        value: Float,
        interpolation: KeyframeInterpolation,
        bezier: BezierCurveSpec = BezierCurveSpec(),
    ) = PropertyKeyframe(
        id = UUID.randomUUID().toString(),
        timeMs = timeMs,
        value = value,
        interpolation = interpolation,
        bezier = bezier,
    )
}
