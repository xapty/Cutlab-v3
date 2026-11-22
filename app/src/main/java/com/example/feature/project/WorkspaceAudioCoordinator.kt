package com.example.feature.project

import com.example.engine.audio.DecodedPcmBuffer
import com.example.engine.audio.MixedAudioPreviewPlayer
import com.example.engine.audio.MultiTrackAudioMixer
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.project.ClipKind
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TrackType
import com.example.engine.timeline.SpeedRampEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Coordinates PCM multi-track audio mix audition windows via [PcmAudioExtractor],
 * [MultiTrackAudioMixer], and [MixedAudioPreviewPlayer] for workspace previewing.
 */
class WorkspaceAudioCoordinator(
    private val scope: CoroutineScope,
    private val pcmAudioExtractor: PcmAudioExtractor? = null,
    private val mixedAudioPreviewPlayer: MixedAudioPreviewPlayer? = null,
    private val audioMixer: MultiTrackAudioMixer = MultiTrackAudioMixer(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val emitMessage: suspend (String) -> Unit = {},
) {
    private val _isAuditioningMix = MutableStateFlow(false)
    val isAuditioningMix: StateFlow<Boolean> = _isAuditioningMix.asStateFlow()

    private var auditionJob: Job? = null

    fun toggleAuditionPcmMix(
        document: ProjectDocument?,
        assetsById: Map<String, MediaAsset>,
        startMs: Long,
        onBeforeStartAudition: () -> Unit,
    ) {
        if (_isAuditioningMix.value) {
            stopAudition()
            return
        }
        val doc = document ?: return
        val extractor = pcmAudioExtractor
        val player = mixedAudioPreviewPlayer
        if (extractor == null || player == null) {
            scope.launch { emitMessage("Audio audition player unavailable") }
            return
        }
        onBeforeStartAudition()
        val windowMs = 2_500L
        auditionJob?.cancel()
        _isAuditioningMix.value = true
        auditionJob = scope.launch(ioDispatcher) {
            try {
                val windowEnd = startMs + windowMs
                val candidateClips = doc.tracks
                    .filter { !it.isMuted && (it.type == TrackType.AUDIO || it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY) }
                    .flatMap { it.clips }
                    .filter { clip ->
                        clip.clipKind == ClipKind.MEDIA &&
                            !clip.isMuted &&
                            (clip.timelineStartMs + clip.durationMs) > startMs &&
                            clip.timelineStartMs < windowEnd
                    }

                if (candidateClips.isEmpty()) {
                    emitMessage("No active unmuted audio clips at playhead")
                    _isAuditioningMix.value = false
                    return@launch
                }

                val decodedCache = HashMap<String, DecodedPcmBuffer?>()
                for (clip in candidateClips) {
                    val asset = assetsById[clip.assetId] ?: continue
                    if (asset.type == MediaType.IMAGE) continue
                    val overlapStart = max(startMs, clip.timelineStartMs)
                    val overlapEnd = min(windowEnd, clip.timelineStartMs + clip.durationMs)
                    val overlapDur = overlapEnd - overlapStart
                    if (overlapDur <= 0L) continue
                    val relStart = overlapStart - clip.timelineStartMs
                    val relEnd = overlapEnd - clip.timelineStartMs
                    val sA = SpeedRampEngine.clipRelativeToSourceMs(clip, relStart, asset.durationMs)
                    val sB = SpeedRampEngine.clipRelativeToSourceMs(clip, relEnd, asset.durationMs)
                    val srcStart = min(sA, sB).coerceAtLeast(0L)
                    val srcDur = abs(sB - sA).coerceAtLeast(100L)
                    val key = "${clip.assetId}:$srcStart:$srcDur"
                    if (!decodedCache.containsKey(key)) {
                        decodedCache[key] = extractor.decodePcmSegment(asset, srcStart, srcDur)
                    }
                }

                val pcm16 = audioMixer.mixWindowToPcm16(
                    document = doc,
                    startMs = startMs,
                    windowDurationMs = windowMs,
                    targetSampleRate = 44_100,
                ) { assetId, srcStart, srcDur ->
                    decodedCache["$assetId:$srcStart:$srcDur"]
                }

                if (pcm16.all { it == 0.toShort() }) {
                    emitMessage("Decoded mix window is silent")
                    _isAuditioningMix.value = false
                    return@launch
                }

                player.playPcm16(pcm16, sampleRateHz = 44_100)
                delay(windowMs)
            } finally {
                player.stop()
                _isAuditioningMix.value = false
            }
        }
    }

    fun stopAudition() {
        auditionJob?.cancel()
        auditionJob = null
        mixedAudioPreviewPlayer?.stop()
        _isAuditioningMix.value = false
    }
}
