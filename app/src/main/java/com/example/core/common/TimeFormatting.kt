package com.example.core.common

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Rational time utilities to avoid floating-point accumulation errors across timeline edits.
 */
data class RationalTime(
    val numerator: Long,
    val denominator: Long,
) {
    init {
        require(denominator > 0L) { "Denominator must be positive" }
    }

    fun toMillis(): Long = ((numerator * 1_000.0) / denominator.toDouble()).roundToLong()

    fun toTruncatedMillis(): Long = (numerator * 1_000L) / denominator

    fun toNanos(): Long = ((numerator.toDouble() * 1_000_000_000.0) / denominator.toDouble()).roundToLong()

    fun toFrames(fps: Int): Long {
        val safeFps = fps.coerceAtLeast(1)
        return ((numerator.toDouble() * safeFps.toDouble()) / denominator.toDouble()).roundToLong()
    }

    companion object {
        fun fromMillis(millis: Long): RationalTime = RationalTime(millis, 1_000L)

        fun fromFrames(frames: Long, fps: Int): RationalTime =
            RationalTime(frames, fps.coerceAtLeast(1).toLong())
    }
}

/**
 * Backward-compatible rational timebase utilities layered over millisecond clip storage.
 * Prevents cumulative truncation drift across frame stepping, playback ticks, speed scaling,
 * audio chunk resampling, and export frame timestamping.
 */
object TimelineTimebase {
    fun frameToMillis(frameIndex: Long, fps: Int): Long {
        val safeFps = fps.coerceIn(1, 240).toLong()
        val safeFrame = frameIndex.coerceAtLeast(0L)
        return ((safeFrame * 1_000L) + (safeFps / 2L)) / safeFps
    }

    fun frameToPtsNs(frameIndex: Long, fps: Int): Long {
        val safeFps = fps.coerceIn(1, 240).toLong()
        val safeFrame = frameIndex.coerceAtLeast(0L)
        return (safeFrame * 1_000_000_000L) / safeFps
    }

    fun millisToNearestFrame(ms: Long, fps: Int): Long {
        val safeFps = fps.coerceIn(1, 240).toLong()
        val safeMs = ms.coerceAtLeast(0L)
        return ((safeMs * safeFps) + 500L) / 1_000L
    }

    fun frameCountForDurationMs(durationMs: Long, fps: Int): Int {
        val safeFps = fps.coerceIn(1, 240)
        val safeMs = durationMs.coerceAtLeast(0L)
        if (safeMs == 0L) return 0
        return (((safeMs * safeFps) + 500L) / 1_000L).toInt().coerceAtLeast(1)
    }

    fun stepFrameMillis(
        currentMs: Long,
        deltaFrames: Int,
        fps: Int,
        maxMs: Long? = null,
    ): Long {
        val curFrame = millisToNearestFrame(currentMs, fps)
        val targetFrame = (curFrame + deltaFrames.toLong()).coerceAtLeast(0L)
        val targetMs = frameToMillis(targetFrame, fps)
        return if (maxMs != null) targetMs.coerceIn(0L, maxMs.coerceAtLeast(0L)) else targetMs
    }

    fun audioFramesForDurationMs(durationMs: Long, sampleRate: Int): Int {
        val safeRate = sampleRate.coerceAtLeast(1)
        val safeMs = durationMs.coerceAtLeast(0L)
        return (((safeMs * safeRate.toLong()) + 500L) / 1_000L).toInt().coerceAtLeast(1)
    }

    fun audioFramesDelta(
        windowEndMs: Long,
        alreadyWrittenFrames: Long,
        sampleRate: Int,
    ): Int {
        val safeRate = sampleRate.coerceAtLeast(1).toLong()
        val expectedTotalFrames = ((windowEndMs.coerceAtLeast(0L) * safeRate) + 500L) / 1_000L
        return (expectedTotalFrames - alreadyWrittenFrames).toInt().coerceAtLeast(0)
    }

    fun scaleSourceToTimelineMs(sourceSpanMs: Long, speed: Float): Long {
        val safeSpeed = speed.coerceIn(0.25f, 4.0f).toDouble()
        return (sourceSpanMs.coerceAtLeast(0L).toDouble() / safeSpeed).roundToLong()
    }

    fun scaleTimelineToSourceMs(timelineSpanMs: Long, speed: Float): Long {
        val safeSpeed = speed.coerceIn(0.25f, 4.0f).toDouble()
        return (timelineSpanMs.coerceAtLeast(0L).toDouble() * safeSpeed).roundToLong()
    }
}

object TimeFormatting {
    fun formatTimecode(durationMs: Long, fps: Int = 30): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        val safeFps = fps.coerceAtLeast(1)
        val frames = ((safeMs % 1_000L) * safeFps / 1_000L).coerceIn(0L, (safeFps - 1).toLong())
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
        } else {
            String.format(Locale.US, "%02d:%02d:%02d", minutes, seconds, frames)
        }
    }

    fun formatDurationLabel(durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val tenths = (safeMs % 1_000L) / 100L
        return if (minutes >= 60L) {
            val hours = minutes / 60L
            val remMinutes = minutes % 60L
            String.format(Locale.US, "%d:%02d:%02d", hours, remMinutes, seconds)
        } else if (minutes > 0L) {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        } else {
            String.format(Locale.US, "%d.%ds", seconds, tenths)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%d KB", kb.roundToLong().coerceAtLeast(1L))
        }
    }
}
