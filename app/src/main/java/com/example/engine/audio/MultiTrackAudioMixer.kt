package com.example.engine.audio

import com.example.core.common.TimelineTimebase
import com.example.engine.animation.AnimationEngine
import com.example.engine.project.ClipKind
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TrackType
import com.example.engine.timeline.ActiveTimelineFrame
import com.example.engine.timeline.SpeedRampEngine
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synchronizes and mixes multiple unmuted Audio and Video tracks onto the shared
 * project timeline clock with keyframed volume automation, fade-in/out envelopes,
 * sample-rate conversion, stereo pan, and EQ/echo DSP.
 */
class MultiTrackAudioMixer(
    private val animationEngine: AnimationEngine = AnimationEngine(),
) {

    /**
     * Renders an interleaved stereo float PCM window for timeline interval `[startMs, startMs + windowDurationMs)`.
     */
    fun mixWindow(
        document: ProjectDocument,
        startMs: Long,
        windowDurationMs: Long,
        targetSampleRate: Int = 44_100,
        targetFrameCountOverride: Int? = null,
        pcmProvider: (assetId: String, sourceStartMs: Long, sourceDurationMs: Long) -> DecodedPcmBuffer?,
    ): FloatArray {
        val totalFrames = (targetFrameCountOverride
            ?: TimelineTimebase.audioFramesForDurationMs(windowDurationMs, targetSampleRate))
            .coerceAtLeast(1)
        val mixBuffer = FloatArray(totalFrames * 2)
        val windowEndMs = startMs + windowDurationMs

        val activeTracks = document.tracks
            .filter { !it.isMuted && (it.type == TrackType.AUDIO || it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY) }

        for (track in activeTracks) {
            val trackClips = track.clips
                .sortedBy { it.timelineStartMs }
                .filter { clip ->
                    clip.clipKind == ClipKind.MEDIA &&
                        !clip.isMuted &&
                        (clip.timelineStartMs + clip.durationMs) > startMs &&
                        clip.timelineStartMs < windowEndMs
                }

            for (clip in trackClips) {
                val overlapStartMs = max(startMs, clip.timelineStartMs)
                val overlapEndMs = min(windowEndMs, clip.timelineStartMs + clip.durationMs)
                val overlapDurationMs = overlapEndMs - overlapStartMs
                if (overlapDurationMs <= 0L) continue

                val relStartMs = overlapStartMs - clip.timelineStartMs
                val relEndMs = overlapEndMs - clip.timelineStartMs
                val srcA = SpeedRampEngine.clipRelativeToSourceMs(clip, relStartMs, clip.sourceTotalDurationMs)
                val srcB = SpeedRampEngine.clipRelativeToSourceMs(clip, relEndMs, clip.sourceTotalDurationMs)
                val sourceStart = min(srcA, srcB).coerceAtLeast(0L)
                val sourceDur = abs(srcB - srcA).coerceAtLeast(1L)
                val hasRamp = SpeedRampEngine.effectivePoints(clip).isNotEmpty()
                val effectiveWindowSpeed = if (!hasRamp) {
                    clip.speed.coerceIn(SpeedRampEngine.MIN_SPEED, SpeedRampEngine.MAX_SPEED)
                } else {
                    (sourceDur.toFloat() / overlapDurationMs.toFloat().coerceAtLeast(1f))
                        .coerceIn(SpeedRampEngine.MIN_SPEED, SpeedRampEngine.MAX_SPEED)
                }

                val dstOffsetFrames = (((overlapStartMs - startMs).toDouble() * targetSampleRate.toDouble()) / 1_000.0)
                    .roundToInt()
                    .coerceIn(0, totalFrames)
                val dstEndFrames = (((overlapEndMs - startMs).toDouble() * targetSampleRate.toDouble()) / 1_000.0)
                    .roundToInt()
                    .coerceIn(dstOffsetFrames, totalFrames)
                val expectedSliceFrames = (dstEndFrames - dstOffsetFrames).coerceAtLeast(1)

                val rawPcm = pcmProvider(clip.assetId, sourceStart, sourceDur) ?: continue
                val stereoResampled = AudioDsp.resampleToStereoInterleaved(
                    samples = rawPcm.interleavedSamples,
                    srcRate = rawPcm.sampleRateHz,
                    srcChannels = rawPcm.channelCount,
                    dstRate = targetSampleRate,
                    speed = effectiveWindowSpeed,
                    targetFrameCount = expectedSliceFrames,
                )
                val orientedStereo = if (clip.isReversed) {
                    AudioDsp.reverseStereoFrames(stereoResampled)
                } else {
                    stereoResampled
                }
                val processed = AudioDsp.applyClipAudioDsp(
                    stereoInterleaved = orientedStereo,
                    sampleRate = targetSampleRate,
                    effect = clip.audioEffect,
                )

                val availableFrames = processed.size / 2
                val copyFrames = min(availableFrames, totalFrames - dstOffsetFrames)

                for (f in 0 until copyFrames) {
                    val frameTimeMs = overlapStartMs + ((f.toDouble() * 1_000.0) / targetSampleRate.toDouble()).roundToLong()
                    val clipRelMs = (frameTimeMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
                    val gain = computeClipGainAt(clip, clipRelMs)
                    if (gain <= 0.0001f) continue

                    val srcIdx = f * 2
                    val dstIdx = (dstOffsetFrames + f) * 2
                    mixBuffer[dstIdx] += processed[srcIdx] * gain
                    mixBuffer[dstIdx + 1] += processed[srcIdx + 1] * gain
                }
            }
        }

        for (i in mixBuffer.indices) {
            mixBuffer[i] = AudioDsp.applySoftLimiter(mixBuffer[i])
        }
        return mixBuffer
    }

    fun mixWindowToPcm16(
        document: ProjectDocument,
        startMs: Long,
        windowDurationMs: Long,
        targetSampleRate: Int = 44_100,
        targetFrameCountOverride: Int? = null,
        pcmProvider: (assetId: String, sourceStartMs: Long, sourceDurationMs: Long) -> DecodedPcmBuffer?,
    ): ShortArray {
        val mixed = mixWindow(
            document = document,
            startMs = startMs,
            windowDurationMs = windowDurationMs,
            targetSampleRate = targetSampleRate,
            targetFrameCountOverride = targetFrameCountOverride,
            pcmProvider = pcmProvider,
        )
        return AudioDsp.floatStereoToPcm16(mixed)
    }

    fun computeClipGainAt(clip: TimelineClipRef, clipRelativeMs: Long): Float {
        if (clip.isMuted) return 0f
        val keyframed = animationEngine.evaluateVolume(clip, clipRelativeMs)
        if (keyframed <= 0f) return 0f
        var env = 1f
        if (clip.fadeInMs > 0L && clipRelativeMs < clip.fadeInMs) {
            env *= (clipRelativeMs.toFloat() / clip.fadeInMs.toFloat()).coerceIn(0f, 1f)
        }
        val rem = (clip.durationMs - clipRelativeMs).coerceAtLeast(0L)
        if (clip.fadeOutMs > 0L && rem < clip.fadeOutMs) {
            env *= (rem.toFloat() / clip.fadeOutMs.toFloat()).coerceIn(0f, 1f)
        }
        return (keyframed * env).coerceIn(0f, 2f)
    }

    /**
     * Computes real-time L/R master levels & per-track meter bars at the current playhead
     * from extracted waveform envelopes and clip gains/panning.
     */
    fun computeLiveMeters(
        document: ProjectDocument,
        activeFrame: ActiveTimelineFrame,
        waveformsByAssetId: Map<String, WaveformEnvelope>,
        assetsById: Map<String, MediaAsset>,
    ): MasterAudioMixState {
        val activeLayersByTrack = (activeFrame.audioLayers + activeFrame.visualLayers)
            .associateBy { it.track.id }
        val audioCapableTracks = document.tracks
            .filter { it.type == TrackType.AUDIO || it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY }
            .sortedBy { it.orderIndex }

        var sumL = 0f
        var sumR = 0f
        var activeCount = 0

        val meters = audioCapableTracks.map { track ->
            val layer = activeLayersByTrack[track.id]
            val asset = layer?.let { assetsById[it.clip.assetId] }
            val hasAudio = asset != null && asset.type != MediaType.IMAGE && !track.isMuted && !layer.clip.isMuted
            val level = if (hasAudio && layer != null) {
                val wf = waveformsByAssetId[asset!!.id]
                val rawPeak = if (wf != null && wf.peaks.isNotEmpty() && wf.durationMs > 0L) {
                    val srcPos = SpeedRampEngine.clipRelativeToSourceMs(
                        layer.clip,
                        layer.clipRelativeMs,
                        asset.durationMs,
                    )
                    val bin = ((srcPos.toFloat() / wf.durationMs.toFloat()) * (wf.peaks.size - 1))
                        .toInt().coerceIn(0, wf.peaks.lastIndex)
                    wf.peaks[bin]
                } else {
                    0.55f
                }
                val weighted = (rawPeak * layer.effectiveVolume).coerceIn(0f, 1.25f)
                if (weighted > 0.005f) {
                    activeCount++
                    val panAngle = ((layer.clip.audioEffect.pan.coerceIn(-1f, 1f) + 1f) * 0.5f) * (Math.PI / 2.0)
                    val lGain = if (layer.clip.audioEffect.pan == 0f) 1f else (cos(panAngle) * sqrt(2.0)).toFloat()
                    val rGain = if (layer.clip.audioEffect.pan == 0f) 1f else (sin(panAngle) * sqrt(2.0)).toFloat()
                    sumL += weighted * lGain
                    sumR += weighted * rGain
                }
                weighted.coerceIn(0f, 1f)
            } else {
                0f
            }

            TrackAudioMeter(
                trackId = track.id,
                trackName = track.name,
                isMuted = track.isMuted,
                isAudioTrack = track.type == TrackType.AUDIO,
                peakLevel = level,
                activeClipName = asset?.displayName,
            )
        }

        return MasterAudioMixState(
            leftLevel = AudioDsp.applySoftLimiter(sumL).coerceIn(0f, 1f),
            rightLevel = AudioDsp.applySoftLimiter(sumR).coerceIn(0f, 1f),
            activeSourceCount = activeCount,
            trackMeters = meters,
        )
    }
}
