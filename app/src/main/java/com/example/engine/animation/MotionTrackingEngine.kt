package com.example.engine.animation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.KeyframeTrack
import com.example.engine.project.MediaAsset
import com.example.engine.project.PropertyKeyframe
import com.example.engine.project.TimelineClipRef
import com.example.engine.timeline.SpeedRampEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Downsampled grayscale frame for optical patch matching & stabilization.
 */
data class LumaFrame(
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val luma: FloatArray,
) {
    fun sample(x: Int, y: Int): Float {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return luma[cy * width + cx]
    }
}

data class TrackedMotionPoint(
    val clipRelativeMs: Long,
    val normX: Float, // 0..1 inside frame
    val normY: Float, // 0..1 inside frame
    val confidence: Float,
)

data class StabilizedTransformSample(
    val clipRelativeMs: Long,
    val offsetX: Float, // normalized canvas offset (-0.15..+0.15)
    val offsetY: Float,
)

data class StabilizationAnalysisResult(
    val cropScale: Float,
    val samples: List<StabilizedTransformSample>,
    val keyframeTracks: List<KeyframeTrack>,
)

data class MotionTrackAnalysisResult(
    val points: List<TrackedMotionPoint>,
    val keyframeTracks: List<KeyframeTrack>,
)

/**
 * Optical SAD block-matching motion tracker and median-consensus Gaussian-smoothed
 * camera stabilization analyzer.
 */
object MotionTrackingEngine {

    /**
     * Tracks a region starting at normalized coordinates ([startNormX], [startNormY])
     * across sequential [frames] using Sum-of-Absolute-Differences (SAD) block matching
     * plus parabolic sub-pixel refinement.
     */
    fun trackPatchSequence(
        frames: List<LumaFrame>,
        startNormX: Float,
        startNormY: Float,
        patchRadiusPx: Int = 5,
        searchRadiusPx: Int = 14,
    ): List<TrackedMotionPoint> {
        if (frames.isEmpty()) return emptyList()
        val first = frames.first()
        var curX = (startNormX.coerceIn(0.08f, 0.92f) * (first.width - 1)).toFloat()
        var curY = (startNormY.coerceIn(0.08f, 0.92f) * (first.height - 1)).toFloat()

        val initialPatch = extractPatch(first, curX.roundToInt(), curY.roundToInt(), patchRadiusPx)
        var rollingPatch = initialPatch.copyOf()
        val patchArea = ((patchRadiusPx * 2 + 1) * (patchRadiusPx * 2 + 1)).toFloat()

        val results = mutableListOf(
            TrackedMotionPoint(
                clipRelativeMs = first.timestampMs,
                normX = (curX / (first.width - 1).coerceAtLeast(1)).coerceIn(0f, 1f),
                normY = (curY / (first.height - 1).coerceAtLeast(1)).coerceIn(0f, 1f),
                confidence = 1f,
            ),
        )

        for (i in 1 until frames.size) {
            val frame = frames[i]
            val centerX = curX.roundToInt().coerceIn(patchRadiusPx, frame.width - 1 - patchRadiusPx)
            val centerY = curY.roundToInt().coerceIn(patchRadiusPx, frame.height - 1 - patchRadiusPx)

            val minSearchX = (centerX - searchRadiusPx).coerceAtLeast(patchRadiusPx)
            val maxSearchX = (centerX + searchRadiusPx).coerceAtMost(frame.width - 1 - patchRadiusPx)
            val minSearchY = (centerY - searchRadiusPx).coerceAtLeast(patchRadiusPx)
            val maxSearchY = (centerY + searchRadiusPx).coerceAtMost(frame.height - 1 - patchRadiusPx)

            var bestX = centerX
            var bestY = centerY
            var bestSad = Float.MAX_VALUE

            val sadGridW = (maxSearchX - minSearchX + 1).coerceAtLeast(1)
            val sadGridH = (maxSearchY - minSearchY + 1).coerceAtLeast(1)
            val sadGrid = FloatArray(sadGridW * sadGridH) { Float.MAX_VALUE }

            for (cy in minSearchY..maxSearchY) {
                for (cx in minSearchX..maxSearchX) {
                    val sad = computePatchSad(frame, cx, cy, patchRadiusPx, rollingPatch, initialPatch)
                    sadGrid[(cy - minSearchY) * sadGridW + (cx - minSearchX)] = sad
                    if (sad < bestSad) {
                        bestSad = sad
                        bestX = cx
                        bestY = cy
                    }
                }
            }

            // Parabolic sub-pixel refinement around best match
            val gx = bestX - minSearchX
            val gy = bestY - minSearchY
            val subDx = if (gx in 1 until sadGridW - 1) {
                parabolicPeakOffset(
                    sadGrid[gy * sadGridW + (gx - 1)],
                    sadGrid[gy * sadGridW + gx],
                    sadGrid[gy * sadGridW + (gx + 1)],
                )
            } else 0f
            val subDy = if (gy in 1 until sadGridH - 1) {
                parabolicPeakOffset(
                    sadGrid[(gy - 1) * sadGridW + gx],
                    sadGrid[gy * sadGridW + gx],
                    sadGrid[(gy + 1) * sadGridW + gx],
                )
            } else 0f

            curX = (bestX + subDx).coerceIn(patchRadiusPx.toFloat(), (frame.width - 1 - patchRadiusPx).toFloat())
            curY = (bestY + subDy).coerceIn(patchRadiusPx.toFloat(), (frame.height - 1 - patchRadiusPx).toFloat())

            // Update rolling template slowly so gradual lighting changes don't break lock
            val latestPatch = extractPatch(frame, bestX, bestY, patchRadiusPx)
            for (k in rollingPatch.indices) {
                rollingPatch[k] = rollingPatch[k] * 0.72f + latestPatch[k] * 0.28f
            }

            val meanAbsErr = (bestSad / patchArea).coerceIn(0f, 1f)
            val confidence = (1f - meanAbsErr * 3.5f).coerceIn(0.05f, 1f)
            results += TrackedMotionPoint(
                clipRelativeMs = frame.timestampMs,
                normX = (curX / (frame.width - 1).coerceAtLeast(1)).coerceIn(0f, 1f),
                normY = (curY / (frame.height - 1).coerceAtLeast(1)).coerceIn(0f, 1f),
                confidence = confidence,
            )
        }
        return results
    }

    /**
     * Estimates global camera translation per frame via median consensus over a 3x3 patch grid,
     * applies temporal Gaussian smoothing to derive the intended smooth camera path, and outputs
     * inverse stabilization offsets + minimum overscan scale.
     */
    fun computeStabilizationTransforms(
        frames: List<LumaFrame>,
        strength: Float = 0.65f,
    ): Pair<Float, List<StabilizedTransformSample>> {
        if (frames.isEmpty()) return 1.0f to emptyList()
        if (frames.size == 1) {
            return 1.04f to listOf(StabilizedTransformSample(frames.first().timestampMs, 0f, 0f))
        }

        val clampedStrength = strength.coerceIn(0f, 1f)
        val gridCoords = listOf(0.25f, 0.50f, 0.75f)
        val n = frames.size

        // Raw cumulative translation per frame in normalized [0..1] canvas units
        val cumX = FloatArray(n)
        val cumY = FloatArray(n)

        for (i in 1 until n) {
            val pair = listOf(frames[i - 1], frames[i])
            val dxs = mutableListOf<Float>()
            val dys = mutableListOf<Float>()
            for (gy in gridCoords) {
                for (gx in gridCoords) {
                    val tracked = trackPatchSequence(pair, gx, gy, patchRadiusPx = 4, searchRadiusPx = 10)
                    if (tracked.size == 2 && tracked[1].confidence > 0.25f) {
                        dxs += (tracked[1].normX - tracked[0].normX)
                        dys += (tracked[1].normY - tracked[0].normY)
                    }
                }
            }
            val medDx = if (dxs.isNotEmpty()) median(dxs) else 0f
            val medDy = if (dys.isNotEmpty()) median(dys) else 0f
            cumX[i] = cumX[i - 1] + medDx
            cumY[i] = cumY[i - 1] + medDy
        }

        // Temporal Gaussian smoothing of cumulative trajectory
        val kernelRadius = 3
        val sigma = 1.6f
        val maxOffset = 0.09f
        var peakAbsShift = 0f
        val samples = ArrayList<StabilizedTransformSample>(n)

        for (i in 0 until n) {
            var smoothX = 0f
            var smoothY = 0f
            var weightSum = 0f
            for (k in -kernelRadius..kernelRadius) {
                val idx = (i + k).coerceIn(0, n - 1)
                val w = kotlin.math.exp(-(k * k) / (2f * sigma * sigma))
                smoothX += cumX[idx] * w
                smoothY += cumY[idx] * w
                weightSum += w
            }
            smoothX /= weightSum
            smoothY /= weightSum

            // Inverse high-frequency jitter offset multiplied by stabilization strength
            val corrX = (-(cumX[i] - smoothX) * clampedStrength).coerceIn(-maxOffset, maxOffset)
            val corrY = (-(cumY[i] - smoothY) * clampedStrength).coerceIn(-maxOffset, maxOffset)
            peakAbsShift = max(peakAbsShift, max(abs(corrX), abs(corrY)))
            samples += StabilizedTransformSample(
                clipRelativeMs = frames[i].timestampMs,
                offsetX = corrX,
                offsetY = corrY,
            )
        }

        val cropScale = (1f + peakAbsShift * 2.15f).coerceIn(1.02f, 1.24f)
        return cropScale to samples
    }

    /**
     * Converts tracked normalized points into [KeyframeTrack]s for POSITION_X / POSITION_Y
     * relative to canvas center `(offset from 0.5f)`.
     */
    fun trackingPointsToKeyframeTracks(
        points: List<TrackedMotionPoint>,
        baseTransformOffsetX: Float = 0f,
        baseTransformOffsetY: Float = 0f,
    ): List<KeyframeTrack> {
        if (points.isEmpty()) return emptyList()
        val xFrames = points.map {
            PropertyKeyframe(
                id = UUID.randomUUID().toString(),
                timeMs = it.clipRelativeMs,
                value = ((it.normX - 0.5f) + baseTransformOffsetX).coerceIn(-0.9f, 0.9f),
                interpolation = KeyframeInterpolation.LINEAR,
            )
        }
        val yFrames = points.map {
            PropertyKeyframe(
                id = UUID.randomUUID().toString(),
                timeMs = it.clipRelativeMs,
                value = ((it.normY - 0.5f) + baseTransformOffsetY).coerceIn(-0.9f, 0.9f),
                interpolation = KeyframeInterpolation.LINEAR,
            )
        }
        return listOf(
            KeyframeTrack(KeyframeProperty.POSITION_X, xFrames),
            KeyframeTrack(KeyframeProperty.POSITION_Y, yFrames),
        )
    }

    fun stabilizationToKeyframeTracks(
        samples: List<StabilizedTransformSample>,
        cropScale: Float,
    ): List<KeyframeTrack> {
        if (samples.isEmpty()) return emptyList()
        val firstMs = samples.first().clipRelativeMs
        val lastMs = samples.last().clipRelativeMs
        val xFrames = samples.map {
            PropertyKeyframe(
                id = UUID.randomUUID().toString(),
                timeMs = it.clipRelativeMs,
                value = it.offsetX,
                interpolation = KeyframeInterpolation.LINEAR,
            )
        }
        val yFrames = samples.map {
            PropertyKeyframe(
                id = UUID.randomUUID().toString(),
                timeMs = it.clipRelativeMs,
                value = it.offsetY,
                interpolation = KeyframeInterpolation.LINEAR,
            )
        }
        val scaleFrames = listOf(
            PropertyKeyframe(UUID.randomUUID().toString(), firstMs, cropScale, KeyframeInterpolation.LINEAR),
            PropertyKeyframe(UUID.randomUUID().toString(), lastMs, cropScale, KeyframeInterpolation.LINEAR),
        )
        return listOf(
            KeyframeTrack(KeyframeProperty.POSITION_X, xFrames),
            KeyframeTrack(KeyframeProperty.POSITION_Y, yFrames),
            KeyframeTrack(KeyframeProperty.SCALE_X, scaleFrames),
            KeyframeTrack(KeyframeProperty.SCALE_Y, scaleFrames),
        )
    }

    suspend fun analyzeTrackTarget(
        context: Context,
        asset: MediaAsset,
        clip: TimelineClipRef,
        startNormX: Float,
        startNormY: Float,
        sampleCount: Int = 20,
    ): MotionTrackAnalysisResult = withContext(Dispatchers.IO) {
        val frames = extractSampledLumaFrames(context, asset, clip, sampleCount)
        if (frames.isEmpty()) {
            return@withContext MotionTrackAnalysisResult(emptyList(), emptyList())
        }
        val tracked = trackPatchSequence(frames, startNormX, startNormY)
        val tracks = trackingPointsToKeyframeTracks(tracked)
        MotionTrackAnalysisResult(points = tracked, keyframeTracks = tracks)
    }

    suspend fun analyzeStabilization(
        context: Context,
        asset: MediaAsset,
        clip: TimelineClipRef,
        strength: Float,
        sampleCount: Int = 22,
    ): StabilizationAnalysisResult = withContext(Dispatchers.IO) {
        val frames = extractSampledLumaFrames(context, asset, clip, sampleCount)
        if (frames.isEmpty()) {
            return@withContext StabilizationAnalysisResult(1.06f, emptyList(), emptyList())
        }
        val (cropScale, samples) = computeStabilizationTransforms(frames, strength)
        val tracks = stabilizationToKeyframeTracks(samples, cropScale)
        StabilizationAnalysisResult(cropScale = cropScale, samples = samples, keyframeTracks = tracks)
    }

    private fun extractSampledLumaFrames(
        context: Context,
        asset: MediaAsset,
        clip: TimelineClipRef,
        sampleCount: Int,
    ): List<LumaFrame> {
        val retriever = MediaMetadataRetriever()
        return try {
            val filePath = asset.managedFilePath
            if (!filePath.isNullOrBlank()) {
                retriever.setDataSource(filePath)
            } else {
                retriever.setDataSource(context, Uri.parse(asset.playbackUri))
            }

            val count = sampleCount.coerceIn(4, 36)
            val clipDur = clip.durationMs.coerceAtLeast(100L)
            val out = ArrayList<LumaFrame>(count)
            val targetSize = 96

            for (i in 0 until count) {
                val clipRelMs = ((i.toLong() * clipDur) / (count - 1).coerceAtLeast(1)).coerceIn(0L, clipDur)
                val sourceMs = SpeedRampEngine.clipRelativeToSourceMs(clip, clipRelMs, asset.durationMs)
                val rawBmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        sourceMs * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        targetSize,
                        targetSize,
                    )
                } else {
                    retriever.getFrameAtTime(sourceMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?.let { bmp ->
                            Bitmap.createScaledBitmap(bmp, targetSize, targetSize, true)
                                .also { if (it !== bmp) bmp.recycle() }
                        }
                }
                if (rawBmp != null) {
                    out += bitmapToLumaFrame(clipRelMs, rawBmp)
                    rawBmp.recycle()
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun bitmapToLumaFrame(timestampMs: Long, bitmap: Bitmap): LumaFrame {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val luma = FloatArray(w * h)
        for (idx in argb.indices) {
            val px = argb[idx]
            val r = Color.red(px) / 255f
            val g = Color.green(px) / 255f
            val b = Color.blue(px) / 255f
            luma[idx] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return LumaFrame(timestampMs = timestampMs, width = w, height = h, luma = luma)
    }

    private fun extractPatch(frame: LumaFrame, cx: Int, cy: Int, radius: Int): FloatArray {
        val dim = radius * 2 + 1
        val patch = FloatArray(dim * dim)
        var k = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                patch[k++] = frame.sample(cx + dx, cy + dy)
            }
        }
        return patch
    }

    private fun computePatchSad(
        frame: LumaFrame,
        cx: Int,
        cy: Int,
        radius: Int,
        rollingTemplate: FloatArray,
        anchorTemplate: FloatArray,
    ): Float {
        var sad = 0f
        var k = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val sample = frame.sample(cx + dx, cy + dy)
                val target = rollingTemplate[k] * 0.65f + anchorTemplate[k] * 0.35f
                sad += abs(sample - target)
                k++
            }
        }
        return sad
    }

    private fun parabolicPeakOffset(left: Float, center: Float, right: Float): Float {
        val denom = 2f * (left - 2f * center + right)
        if (abs(denom) < 1e-5f) return 0f
        return ((left - right) / denom).coerceIn(-0.5f, 0.5f)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) * 0.5f
    }
}
