package com.example.engine.asr

/**
 * Pure-Kotlin stateful streaming PCM-to-16kHz-mono converter.
 * Maintains fractional resampling phase across arbitrary MediaCodec decoded buffer boundaries
 * so long audio files are transcribed chunk-by-chunk without loading full PCM into memory or
 * introducing timestamp drift.
 */
class StreamingMono16kResampler(
    private val inputSampleRateHz: Int,
    private val inputChannels: Int,
) {
    private val safeInRate = inputSampleRateHz.coerceAtLeast(8_000).toDouble()
    private val safeChannels = inputChannels.coerceAtLeast(1)
    private val step = safeInRate / TARGET_SAMPLE_RATE_HZ.toDouble()
    private var fracPos = 0.0
    private var prevMonoSample = 0f
    private var hasPrevSample = false

    fun processInterleavedFloats(interleaved: FloatArray, frameCount: Int = interleaved.size / safeChannels): ShortArray {
        if (frameCount <= 0) return ShortArray(0)
        val mono = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            val base = f * safeChannels
            var sum = 0f
            for (c in 0 until safeChannels) {
                sum += interleaved[base + c]
            }
            mono[f] = (sum / safeChannels).coerceIn(-1f, 1f)
        }
        return resampleMonoBuffer(mono)
    }

    fun processInterleavedShorts(interleaved: ShortArray, frameCount: Int = interleaved.size / safeChannels): ShortArray {
        if (frameCount <= 0) return ShortArray(0)
        val mono = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            val base = f * safeChannels
            var sum = 0f
            for (c in 0 until safeChannels) {
                sum += interleaved[base + c].toFloat() / 32768f
            }
            mono[f] = (sum / safeChannels).coerceIn(-1f, 1f)
        }
        return resampleMonoBuffer(mono)
    }

    private fun resampleMonoBuffer(mono: FloatArray): ShortArray {
        if (mono.isEmpty()) return ShortArray(0)
        if (safeInRate.toInt() == TARGET_SAMPLE_RATE_HZ && !hasPrevSample) {
            hasPrevSample = true
            prevMonoSample = mono.last()
            return ShortArray(mono.size) { i ->
                (mono[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        val estimatedOut = ((mono.size.toDouble() / step) + 4).toInt().coerceAtLeast(1)
        val out = ArrayList<Short>(estimatedOut)
        var pos = fracPos
        while (pos < mono.size.toDouble()) {
            val idx0 = kotlin.math.floor(pos).toInt()
            val t = (pos - idx0).toFloat()
            val s0 = if (idx0 < 0) {
                if (hasPrevSample) prevMonoSample else mono[0]
            } else {
                mono[idx0.coerceIn(0, mono.lastIndex)]
            }
            val s1 = mono[(idx0 + 1).coerceIn(0, mono.lastIndex)]
            val interpolated = (s0 + (s1 - s0) * t).coerceIn(-1f, 1f)
            out.add((interpolated * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            pos += step
        }
        fracPos = pos - mono.size.toDouble()
        prevMonoSample = mono.last()
        hasPrevSample = true
        return out.toShortArray()
    }

    companion object {
        const val TARGET_SAMPLE_RATE_HZ = 16_000
    }
}
