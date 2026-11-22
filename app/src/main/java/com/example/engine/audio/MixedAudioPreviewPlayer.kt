package com.example.engine.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays rendered stereo PCM-16 audio directly via Android AudioTrack so users can
 * audition the true multi-track mixed + EQ/Echo/Pan-processed PCM buffer.
 */
class MixedAudioPreviewPlayer {
    @Volatile
    private var activeTrack: AudioTrack? = null

    suspend fun playPcm16(
        samples: ShortArray,
        sampleRateHz: Int = 44_100,
    ) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext
        stop()

        val byteSize = samples.size * 2
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(byteSize, minBuf))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        activeTrack = track
        track.write(samples, 0, samples.size)
        track.play()
    }

    fun stop() {
        val current = activeTrack ?: return
        activeTrack = null
        runCatching { current.stop() }
        runCatching { current.release() }
    }
}
