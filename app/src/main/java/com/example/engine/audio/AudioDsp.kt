package com.example.engine.audio

import com.example.engine.project.AudioEffectPreset
import com.example.engine.project.ClipAudioEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Pure-Kotlin PCM resampling, channel conversion, biquad EQ / echo / pan DSP,
 * waveform envelope compression, and rhythmic transient beat detection.
 */
object AudioDsp {

    /**
     * Converts mono or stereo float PCM at [srcRate] into interleaved stereo float PCM
     * at [dstRate], accounting for playback [speed].
     */
    fun resampleToStereoInterleaved(
        samples: FloatArray,
        srcRate: Int,
        srcChannels: Int,
        dstRate: Int,
        speed: Float = 1.0f,
        targetFrameCount: Int? = null,
    ): FloatArray {
        if (samples.isEmpty() || srcRate <= 0 || dstRate <= 0 || srcChannels <= 0) {
            return FloatArray(0)
        }
        val srcFrames = samples.size / srcChannels
        if (srcFrames <= 0) return FloatArray(0)

        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        val step = (srcRate.toDouble() / dstRate.toDouble()) * safeSpeed.toDouble()
        val computedFrames = kotlin.math.round(srcFrames.toDouble() / step).toInt().coerceAtLeast(1)
        val dstFrames = targetFrameCount?.coerceAtLeast(1) ?: computedFrames
        val out = FloatArray(dstFrames * 2)

        for (i in 0 until dstFrames) {
            val srcPos = i * step
            val i0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val i1 = (i0 + 1).coerceIn(0, srcFrames - 1)
            val frac = (srcPos - i0).toFloat().coerceIn(0f, 1f)

            val l0 = samples[i0 * srcChannels]
            val l1 = samples[i1 * srcChannels]
            val l = l0 + (l1 - l0) * frac

            val r0 = if (srcChannels >= 2) samples[i0 * srcChannels + 1] else l0
            val r1 = if (srcChannels >= 2) samples[i1 * srcChannels + 1] else l1
            val r = r0 + (r1 - r0) * frac

            out[i * 2] = l
            out[i * 2 + 1] = r
        }
        return out
    }

    /**
     * Reverses an interleaved stereo PCM buffer frame-by-frame `[L, R]` for reverse playback.
     */
    fun reverseStereoFrames(stereoInterleaved: FloatArray): FloatArray {
        val frameCount = stereoInterleaved.size / 2
        if (frameCount <= 1) return stereoInterleaved
        val out = FloatArray(stereoInterleaved.size)
        for (i in 0 until frameCount) {
            val revIdx = (frameCount - 1 - i) * 2
            val dstIdx = i * 2
            out[dstIdx] = stereoInterleaved[revIdx]
            out[dstIdx + 1] = stereoInterleaved[revIdx + 1]
        }
        return out
    }

    /**
     * Applies preset + custom Bass/Treble shelf EQ, Echo delay, and equal-power Stereo Pan
     * in-place on an interleaved stereo float buffer.
     */
    fun applyClipAudioDsp(
        stereoInterleaved: FloatArray,
        sampleRate: Int,
        effect: ClipAudioEffect,
    ): FloatArray {
        if (stereoInterleaved.isEmpty() || effect.isDefault || sampleRate <= 0) {
            return stereoInterleaved
        }
        val frameCount = stereoInterleaved.size / 2
        val out = stereoInterleaved.copyOf()

        val effectiveBassDb = (effect.bassDb + presetBassBoostDb(effect.preset)).coerceIn(-12f, 12f)
        val effectiveTrebleDb = (effect.trebleDb + presetTrebleBoostDb(effect.preset)).coerceIn(-12f, 12f)
        val effectiveEcho = max(effect.echoMix, presetEchoMix(effect.preset)).coerceIn(0f, 0.75f)

        val lowShelf = if (abs(effectiveBassDb) > 0.1f) {
            BiquadFilter.lowShelf(sampleRate.toDouble(), freqHz = 140.0, gainDb = effectiveBassDb.toDouble())
        } else null
        val highShelf = if (abs(effectiveTrebleDb) > 0.1f) {
            BiquadFilter.highShelf(sampleRate.toDouble(), freqHz = 4_200.0, gainDb = effectiveTrebleDb.toDouble())
        } else null
        val bandShape = when (effect.preset) {
            AudioEffectPreset.PHONE_RADIO -> BiquadFilter.bandPass(sampleRate.toDouble(), freqHz = 1_500.0, q = 1.1)
            AudioEffectPreset.VOCAL_CLEAR -> BiquadFilter.peakingEq(sampleRate.toDouble(), freqHz = 2_800.0, q = 1.0, gainDb = 4.5)
            AudioEffectPreset.STUDIO_PUNCH -> BiquadFilter.peakingEq(sampleRate.toDouble(), freqHz = 95.0, q = 0.9, gainDb = 4.0)
            else -> null
        }

        val delaySamples = (sampleRate * 0.22f).toInt().coerceIn(256, 22_050)
        val delayL = FloatArray(delaySamples)
        val delayR = FloatArray(delaySamples)
        var delayIdx = 0

        // Equal-power stereo pan law (-1 = Left, 0 = Center, +1 = Right)
        val panNorm = ((effect.pan.coerceIn(-1f, 1f) + 1f) * 0.5f) * (PI / 2.0)
        val gainL = if (effect.pan == 0f) 1f else (cos(panNorm) * 1.4142135).toFloat().coerceAtMost(1.25f)
        val gainR = if (effect.pan == 0f) 1f else (sin(panNorm) * 1.4142135).toFloat().coerceAtMost(1.25f)

        for (i in 0 until frameCount) {
            var l = out[i * 2]
            var r = out[i * 2 + 1]

            if (bandShape != null) {
                l = bandShape.processL(l)
                r = bandShape.processR(r)
            }
            if (lowShelf != null) {
                l = lowShelf.processL(l)
                r = lowShelf.processR(r)
            }
            if (highShelf != null) {
                l = highShelf.processL(l)
                r = highShelf.processR(r)
            }

            if (effectiveEcho > 0.01f) {
                val wetL = delayL[delayIdx]
                val wetR = delayR[delayIdx]
                delayL[delayIdx] = l + wetL * 0.38f
                delayR[delayIdx] = r + wetR * 0.38f
                delayIdx = (delayIdx + 1) % delaySamples
                l = l * (1f - effectiveEcho * 0.3f) + wetL * effectiveEcho
                r = r * (1f - effectiveEcho * 0.3f) + wetR * effectiveEcho
            }

            if (effect.preset == AudioEffectPreset.STUDIO_PUNCH) {
                l = applySoftCompressor(l)
                r = applySoftCompressor(r)
            }

            out[i * 2] = l * gainL
            out[i * 2 + 1] = r * gainR
        }
        return out
    }

    private fun presetBassBoostDb(preset: AudioEffectPreset): Float = when (preset) {
        AudioEffectPreset.WARM_BASS -> 6.5f
        AudioEffectPreset.STUDIO_PUNCH -> 3.5f
        AudioEffectPreset.PHONE_RADIO -> -6.0f
        else -> 0f
    }

    private fun presetTrebleBoostDb(preset: AudioEffectPreset): Float = when (preset) {
        AudioEffectPreset.VOCAL_CLEAR -> 3.5f
        AudioEffectPreset.STUDIO_PUNCH -> 2.5f
        AudioEffectPreset.PHONE_RADIO -> -4.0f
        else -> 0f
    }

    private fun presetEchoMix(preset: AudioEffectPreset): Float = when (preset) {
        AudioEffectPreset.ECHO_ROOM -> 0.42f
        else -> 0f
    }

    private fun applySoftCompressor(sample: Float): Float {
        val threshold = 0.45f
        val magnitude = abs(sample)
        if (magnitude <= threshold) return sample * 1.15f
        val sign = if (sample >= 0f) 1f else -1f
        val excess = magnitude - threshold
        val compressed = threshold + excess / (1f + excess * 2.5f)
        return sign * (compressed * 1.25f)
    }

    fun applySoftLimiter(sample: Float): Float {
        return if (abs(sample) <= 0.88f) sample else tanh(sample.toDouble()).toFloat()
    }

    fun floatStereoToPcm16(stereo: FloatArray): ShortArray {
        val out = ShortArray(stereo.size)
        for (i in stereo.indices) {
            val limited = applySoftLimiter(stereo[i]).coerceIn(-1f, 1f)
            out[i] = (limited * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun computePeakEnvelope(
        samples: FloatArray,
        channelCount: Int,
        binCount: Int = 120,
    ): List<Float> {
        if (samples.isEmpty() || channelCount <= 0 || binCount <= 0) return emptyList()
        val totalFrames = samples.size / channelCount
        if (totalFrames <= 0) return emptyList()

        val peaks = MutableList(binCount) { 0f }
        for (bin in 0 until binCount) {
            val startFrame = (bin * totalFrames) / binCount
            val endFrame = (((bin + 1) * totalFrames) / binCount).coerceAtLeast(startFrame + 1).coerceAtMost(totalFrames)
            var maxAmp = 0f
            val step = max(1, (endFrame - startFrame) / 48)
            var f = startFrame
            while (f < endFrame) {
                var frameAbs = 0f
                for (c in 0 until channelCount) {
                    frameAbs = max(frameAbs, abs(samples[f * channelCount + c]))
                }
                if (frameAbs > maxAmp) maxAmp = frameAbs
                f += step
            }
            peaks[bin] = maxAmp.coerceIn(0f, 1f)
        }
        return peaks
    }

    /**
     * Detects transient energy spikes (beats/onsets) across waveform amplitude bins.
     */
    fun detectBeatTimestampsMs(
        peaks: List<Float>,
        durationMs: Long,
        minSpacingMs: Long = 280L,
    ): List<Long> {
        if (peaks.size < 6 || durationMs <= 0L) return emptyList()
        val mean = peaks.average().toFloat()
        val threshold = (mean * 1.32f).coerceIn(0.18f, 0.92f)
        val detected = mutableListOf<Long>()
        var lastBeatMs = -minSpacingMs

        for (i in 1 until peaks.lastIndex) {
            val cur = peaks[i]
            val prev = peaks[i - 1]
            val next = peaks[i + 1]
            val isLocalPeak = cur >= prev && cur >= next
            val onsetDelta = cur - prev
            if (isLocalPeak && cur >= threshold && onsetDelta >= 0.04f) {
                val timeMs = (i.toLong() * durationMs) / peaks.size.toLong()
                if (timeMs - lastBeatMs >= minSpacingMs) {
                    detected += timeMs
                    lastBeatMs = timeMs
                }
            }
        }
        return detected
    }

    private class BiquadFilter(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var lx1 = 0.0
        private var lx2 = 0.0
        private var ly1 = 0.0
        private var ly2 = 0.0

        private var rx1 = 0.0
        private var rx2 = 0.0
        private var ry1 = 0.0
        private var ry2 = 0.0

        fun processL(x: Float): Float {
            val xd = x.toDouble()
            val y = b0 * xd + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
            lx2 = lx1
            lx1 = xd
            ly2 = ly1
            ly1 = y
            return y.toFloat()
        }

        fun processR(x: Float): Float {
            val xd = x.toDouble()
            val y = b0 * xd + b1 * rx1 + b2 * rx2 - a1 * ry1 - a2 * ry2
            rx2 = rx1
            rx1 = xd
            ry2 = ry1
            ry1 = y
            return y.toFloat()
        }

        companion object {
            fun lowShelf(sampleRate: Double, freqHz: Double, gainDb: Double): BiquadFilter {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * freqHz / sampleRate
                val alpha = sin(w0) / 2.0 * sqrt(2.0)
                val cosW0 = cos(w0)
                val sqrtA2Alpha = 2.0 * sqrt(a) * alpha

                val a0 = (a + 1.0) + (a - 1.0) * cosW0 + sqrtA2Alpha
                val b0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + sqrtA2Alpha)) / a0
                val b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
                val b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - sqrtA2Alpha)) / a0
                val a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
                val a2 = ((a + 1.0) + (a - 1.0) * cosW0 - sqrtA2Alpha) / a0
                return BiquadFilter(b0, b1, b2, a1, a2)
            }

            fun highShelf(sampleRate: Double, freqHz: Double, gainDb: Double): BiquadFilter {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * freqHz / sampleRate
                val alpha = sin(w0) / 2.0 * sqrt(2.0)
                val cosW0 = cos(w0)
                val sqrtA2Alpha = 2.0 * sqrt(a) * alpha

                val a0 = (a + 1.0) - (a - 1.0) * cosW0 + sqrtA2Alpha
                val b0 = (a * ((a + 1.0) + (a - 1.0) * cosW0 + sqrtA2Alpha)) / a0
                val b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
                val b2 = (a * ((a + 1.0) + (a - 1.0) * cosW0 - sqrtA2Alpha)) / a0
                val a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
                val a2 = ((a + 1.0) - (a - 1.0) * cosW0 - sqrtA2Alpha) / a0
                return BiquadFilter(b0, b1, b2, a1, a2)
            }

            fun bandPass(sampleRate: Double, freqHz: Double, q: Double): BiquadFilter {
                val w0 = 2.0 * PI * freqHz / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha
                return BiquadFilter(
                    b0 = alpha / a0,
                    b1 = 0.0,
                    b2 = -alpha / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }

            fun peakingEq(sampleRate: Double, freqHz: Double, q: Double, gainDb: Double): BiquadFilter {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * freqHz / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW0 = cos(w0)
                val a0 = 1.0 + alpha / a
                return BiquadFilter(
                    b0 = (1.0 + alpha * a) / a0,
                    b1 = (-2.0 * cosW0) / a0,
                    b2 = (1.0 - alpha * a) / a0,
                    a1 = (-2.0 * cosW0) / a0,
                    a2 = (1.0 - alpha / a) / a0,
                )
            }
        }
    }
}
