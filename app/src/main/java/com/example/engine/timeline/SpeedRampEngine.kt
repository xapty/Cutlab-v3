package com.example.engine.timeline

import com.example.engine.project.SpeedPoint
import com.example.engine.project.SpeedRampPreset
import com.example.engine.project.TimelineClipRef
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Evaluates piecewise-linear speed ramp curves, exact drift-free integral
 * source-time mapping, and reverse playback traversal.
 */
object SpeedRampEngine {
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4.0f

    fun presetPoints(preset: SpeedRampPreset): List<SpeedPoint> = when (preset) {
        SpeedRampPreset.NONE -> emptyList()
        SpeedRampPreset.MONTAGE -> listOf(
            SpeedPoint(0.00f, 1.00f),
            SpeedPoint(0.22f, 2.80f),
            SpeedPoint(0.50f, 0.45f),
            SpeedPoint(0.78f, 2.60f),
            SpeedPoint(1.00f, 1.00f),
        )
        SpeedRampPreset.HERO_MOMENT -> listOf(
            SpeedPoint(0.00f, 2.40f),
            SpeedPoint(0.32f, 0.35f),
            SpeedPoint(0.68f, 0.35f),
            SpeedPoint(1.00f, 2.40f),
        )
        SpeedRampPreset.BULLET_TIME -> listOf(
            SpeedPoint(0.00f, 1.80f),
            SpeedPoint(0.25f, 1.80f),
            SpeedPoint(0.42f, 0.25f),
            SpeedPoint(0.62f, 0.25f),
            SpeedPoint(0.80f, 1.80f),
            SpeedPoint(1.00f, 1.80f),
        )
        SpeedRampPreset.JUMP_CUT -> listOf(
            SpeedPoint(0.00f, 1.00f),
            SpeedPoint(0.20f, 2.60f),
            SpeedPoint(0.40f, 0.90f),
            SpeedPoint(0.60f, 2.60f),
            SpeedPoint(0.80f, 0.90f),
            SpeedPoint(1.00f, 1.00f),
        )
        SpeedRampPreset.FLASH_IN -> listOf(
            SpeedPoint(0.00f, 3.50f),
            SpeedPoint(0.28f, 1.00f),
            SpeedPoint(1.00f, 1.00f),
        )
        SpeedRampPreset.FLASH_OUT -> listOf(
            SpeedPoint(0.00f, 1.00f),
            SpeedPoint(0.72f, 1.00f),
            SpeedPoint(1.00f, 3.50f),
        )
    }

    fun normalizePoints(
        points: List<SpeedPoint>,
        fallbackSpeed: Float = 1.0f,
    ): List<SpeedPoint> {
        if (points.isEmpty()) return emptyList()
        val sorted = points
            .map {
                SpeedPoint(
                    normalizedTime = it.normalizedTime.coerceIn(0f, 1f),
                    speed = it.speed.coerceIn(MIN_SPEED, MAX_SPEED),
                )
            }
            .sortedBy { it.normalizedTime }
        val deduped = mutableListOf<SpeedPoint>()
        for (pt in sorted) {
            if (deduped.isNotEmpty() && abs(deduped.last().normalizedTime - pt.normalizedTime) < 0.002f) {
                deduped[deduped.lastIndex] = pt
            } else {
                deduped += pt
            }
        }
        if (deduped.isEmpty()) {
            val safe = fallbackSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
            return listOf(SpeedPoint(0f, safe), SpeedPoint(1f, safe))
        }
        if (deduped.first().normalizedTime > 0.001f) {
            deduped.add(0, SpeedPoint(0f, deduped.first().speed))
        } else {
            deduped[0] = deduped[0].copy(normalizedTime = 0f)
        }
        if (deduped.last().normalizedTime < 0.999f) {
            deduped.add(SpeedPoint(1f, deduped.last().speed))
        } else {
            deduped[deduped.lastIndex] = deduped.last().copy(normalizedTime = 1f)
        }
        return deduped
    }

    fun effectivePoints(clip: TimelineClipRef): List<SpeedPoint> {
        if (clip.speedRampPoints.isNotEmpty()) {
            return normalizePoints(clip.speedRampPoints, clip.speed)
        }
        if (clip.speedRampPreset != SpeedRampPreset.NONE) {
            return normalizePoints(presetPoints(clip.speedRampPreset), clip.speed)
        }
        return emptyList()
    }

    fun evaluateSpeedAtNormalized(
        points: List<SpeedPoint>,
        normalizedTime: Float,
        fallbackSpeed: Float = 1.0f,
    ): Float {
        val t = normalizedTime.coerceIn(0f, 1f)
        val norm = normalizePoints(points, fallbackSpeed)
        if (norm.isEmpty()) return fallbackSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (norm.size == 1) return norm.first().speed
        for (i in 0 until norm.lastIndex) {
            val a = norm[i]
            val b = norm[i + 1]
            if (t <= b.normalizedTime || i == norm.lastIndex - 1) {
                val span = (b.normalizedTime - a.normalizedTime).coerceAtLeast(1e-5f)
                val frac = ((t - a.normalizedTime) / span).coerceIn(0f, 1f)
                return (a.speed + (b.speed - a.speed) * frac).coerceIn(MIN_SPEED, MAX_SPEED)
            }
        }
        return norm.last().speed
    }

    /**
     * Exact integral of s(tau) over [0, normalizedTime].
     * Total integral at normalizedTime == 1f equals the average playback speed.
     */
    fun integrateNormalizedSourceUnits(
        points: List<SpeedPoint>,
        normalizedTime: Float,
        fallbackSpeed: Float = 1.0f,
    ): Float {
        val t = normalizedTime.coerceIn(0f, 1f)
        if (t <= 0f) return 0f
        val norm = normalizePoints(points, fallbackSpeed)
        if (norm.isEmpty()) {
            return fallbackSpeed.coerceIn(MIN_SPEED, MAX_SPEED) * t
        }
        if (norm.size == 1) {
            return norm.first().speed * t
        }

        var sum = 0.0
        for (i in 0 until norm.lastIndex) {
            val a = norm[i]
            val b = norm[i + 1]
            if (t <= a.normalizedTime) break
            val segEndT = minOf(t, b.normalizedTime)
            val dt = (segEndT - a.normalizedTime).toDouble()
            if (dt > 0.0) {
                val fullSpan = (b.normalizedTime - a.normalizedTime).coerceAtLeast(1e-5f)
                val frac = ((segEndT - a.normalizedTime) / fullSpan).coerceIn(0f, 1f)
                val endSpeed = a.speed + (b.speed - a.speed) * frac
                sum += 0.5 * (a.speed.toDouble() + endSpeed.toDouble()) * dt
            }
        }
        return sum.toFloat().coerceAtLeast(0f)
    }

    fun averageSpeed(
        points: List<SpeedPoint>,
        fallbackSpeed: Float = 1.0f,
    ): Float {
        val norm = normalizePoints(points, fallbackSpeed)
        if (norm.isEmpty()) return fallbackSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        return integrateNormalizedSourceUnits(norm, 1f, fallbackSpeed).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    fun averageSpeed(clip: TimelineClipRef): Float {
        val pts = effectivePoints(clip)
        return averageSpeed(pts, clip.speed)
    }

    fun instantaneousSpeedAt(clip: TimelineClipRef, clipRelativeMs: Long): Float {
        val dur = clip.durationMs.coerceAtLeast(1L).toFloat()
        val normT = (clipRelativeMs.toFloat() / dur).coerceIn(0f, 1f)
        return evaluateSpeedAtNormalized(effectivePoints(clip), normT, clip.speed)
    }

    fun sourceSpanMs(clip: TimelineClipRef): Long {
        val pts = effectivePoints(clip)
        val raw = if (pts.isEmpty()) {
            (clip.durationMs.coerceAtLeast(1L) * clip.speed.coerceIn(MIN_SPEED, MAX_SPEED)).roundToLong()
        } else {
            (clip.durationMs.coerceAtLeast(1L) * averageSpeed(pts, clip.speed)).roundToLong()
        }
        return raw.coerceAtLeast(1L)
    }

    fun computeRampedDurationMs(
        sourceSpanMs: Long,
        points: List<SpeedPoint>,
        fallbackSpeed: Float = 1.0f,
    ): Long {
        val avg = averageSpeed(points, fallbackSpeed)
        return (sourceSpanMs.coerceAtLeast(100L).toDouble() / avg.toDouble())
            .roundToLong()
            .coerceIn(250L, 600_000L)
    }

    /**
     * Maps a clip-relative timeline offset [clipRelativeMs] into an absolute source media
     * timestamp in milliseconds, honoring speed ramps and reverse playback.
     */
    fun clipRelativeToSourceMs(
        clip: TimelineClipRef,
        clipRelativeMs: Long,
        assetDurationMs: Long = clip.sourceTotalDurationMs,
    ): Long {
        val clipDur = clip.durationMs.coerceAtLeast(1L)
        val clampedRel = clipRelativeMs.coerceIn(0L, clipDur)
        val pts = effectivePoints(clip)
        val traversedSourceMs: Long
        val totalSpanMs: Long

        if (pts.isEmpty()) {
            val safeSpeed = clip.speed.coerceIn(MIN_SPEED, MAX_SPEED)
            traversedSourceMs = (clampedRel * safeSpeed).roundToLong()
            totalSpanMs = (clipDur * safeSpeed).roundToLong().coerceAtLeast(1L)
        } else {
            val normT = (clampedRel.toFloat() / clipDur.toFloat()).coerceIn(0f, 1f)
            traversedSourceMs = (clipDur * integrateNormalizedSourceUnits(pts, normT, clip.speed)).roundToLong()
            totalSpanMs = (clipDur * averageSpeed(pts, clip.speed)).roundToLong().coerceAtLeast(1L)
        }

        val mapped = if (clip.isReversed) {
            clip.sourceStartMs + (totalSpanMs - traversedSourceMs).coerceAtLeast(0L)
        } else {
            clip.sourceStartMs + traversedSourceMs
        }

        val maxLimit = assetDurationMs.takeIf { it > 0L }?.let { (it - 16L).coerceAtLeast(0L) }
        return if (maxLimit != null) mapped.coerceIn(0L, maxLimit) else mapped.coerceAtLeast(0L)
    }
}
