package com.example.engine.audio

import kotlinx.serialization.Serializable

@Serializable
data class WaveformEnvelope(
    val assetId: String,
    val durationMs: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val peaks: List<Float>,
    val beatTimestampsMs: List<Long> = emptyList(),
) {
    /**
     * Slices normalized amplitude peaks for a trimmed/sped-up sub-range of the asset.
     */
    fun slicePeaks(
        sourceStartMs: Long,
        sourceSpanMs: Long,
        targetBars: Int,
    ): List<Float> {
        if (peaks.isEmpty() || durationMs <= 0L || targetBars <= 0) return emptyList()
        val total = peaks.size
        val startFrac = (sourceStartMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val endFrac = ((sourceStartMs + sourceSpanMs.coerceAtLeast(1L)).toFloat() / durationMs.toFloat())
            .coerceIn(startFrac, 1f)
        val spanFrac = (endFrac - startFrac).coerceAtLeast(1f / total.toFloat())
        return List(targetBars) { idx ->
            val f = startFrac + (idx.toFloat() / targetBars.coerceAtLeast(1).toFloat()) * spanFrac
            val bin = (f * (total - 1)).toInt().coerceIn(0, total - 1)
            peaks[bin]
        }
    }
}

class DecodedPcmBuffer(
    val sampleRateHz: Int,
    val channelCount: Int,
    val interleavedSamples: FloatArray,
) {
    val frameCount: Int
        get() = if (channelCount <= 0) 0 else interleavedSamples.size / channelCount

    val durationMs: Long
        get() = if (sampleRateHz <= 0) 0L else (frameCount * 1_000L) / sampleRateHz
}

data class TrackAudioMeter(
    val trackId: String,
    val trackName: String,
    val isMuted: Boolean,
    val isAudioTrack: Boolean,
    val peakLevel: Float,
    val activeClipName: String?,
)

data class MasterAudioMixState(
    val leftLevel: Float = 0f,
    val rightLevel: Float = 0f,
    val activeSourceCount: Int = 0,
    val trackMeters: List<TrackAudioMeter> = emptyList(),
)
