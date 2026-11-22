package com.example.engine.timeline

import com.example.core.common.TimelineTimebase
import com.example.engine.animation.AnimationEngine
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.BezierCurveSpec
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ChromaKeySpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipAudioEffect
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.ClipTransition
import com.example.engine.project.FilterPreset
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.KeyframeTrack
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.MotionPreset
import com.example.engine.project.ProjectDocument
import com.example.engine.project.PropertyKeyframe
import com.example.engine.project.SpeedPoint
import com.example.engine.project.SpeedRampPreset
import com.example.engine.project.StabilizationSpec
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineMarker
import com.example.engine.project.TimelineTrack
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

const val MIN_CLIP_DURATION_MS = 100L
const val MAX_IMAGE_CLIP_DURATION_MS = 600_000L
const val TEXT_ASSET_PLACEHOLDER_ID = "cutlab_text_layer"
const val ADJUSTMENT_ASSET_PLACEHOLDER_ID = "cutlab_adjustment_layer"

data class ActiveClipLayer(
    val track: TimelineTrack,
    val clip: TimelineClipRef,
    val clipRelativeMs: Long,
    val effectiveVolume: Float = clip.volume,
    val transitionProgress: Float = 1f,
    val activeTransition: ClipTransition? = null,
)

data class ActiveTimelineFrame(
    val playheadMs: Long,
    val visualLayers: List<ActiveClipLayer>,
    val audioLayers: List<ActiveClipLayer>,
    val textLayers: List<ActiveClipLayer> = emptyList(),
    val adjustmentLayers: List<ActiveClipLayer> = emptyList(),
    val outgoingVisualLayer: ActiveClipLayer? = null,
) {
    val primaryVisualLayer: ActiveClipLayer?
        get() = visualLayers.firstOrNull()
}

data class SnapResult(
    val positionMs: Long,
    val snappedTargetMs: Long?,
)

data class ClipTransformResult(
    val document: ProjectDocument,
    val snappedTargetMs: Long? = null,
)

enum class TimelineOverlapRepairMode {
    /**
     * Timing-preserving repair (recommended): keeps every clip's original timelineStartMs and
     * durationMs by moving conflicting overlapping clips onto compatible overflow tracks.
     */
    MOVE_TO_OVERFLOW_TRACK,

    /**
     * Single-track shift repair: keeps clips on their original track and shifts overlapping
     * clips forward in time. Changes clip timelineStartMs.
     */
    SHIFT_FORWARD_ON_TRACK,
}

enum class OverlapRepairAction {
    KEEP_IN_PLACE,
    MOVE_TO_OVERFLOW_TRACK,
    SHIFT_FORWARD,
    FIX_TRACK_ID,
    CLAMP_MIN_DURATION,
}

data class ClipOverlapRepairEntry(
    val clipId: String,
    val assetId: String,
    val clipKind: ClipKind,
    val sourceTrackId: String,
    val sourceTrackName: String,
    val overlappingWithClipIds: List<String>,
    val overlapDurationMs: Long,
    val originalStartMs: Long,
    val originalDurationMs: Long,
    val proposedTrackId: String,
    val proposedTrackName: String,
    val proposedStartMs: Long,
    val proposedDurationMs: Long,
    val action: OverlapRepairAction,
) {
    val originalEndMs: Long get() = originalStartMs + originalDurationMs
    val proposedEndMs: Long get() = proposedStartMs + proposedDurationMs
    val timingChanged: Boolean
        get() = proposedStartMs != originalStartMs || proposedDurationMs != originalDurationMs
}

data class TrackOverlapRepairEntry(
    val trackId: String,
    val trackName: String,
    val trackType: TrackType,
    val affectedClips: List<ClipOverlapRepairEntry>,
)

data class TimelineOverlapRepairReport(
    val mode: TimelineOverlapRepairMode,
    val hasOverlaps: Boolean,
    val affectedTracks: List<TrackOverlapRepairEntry>,
    val affectedClips: List<ClipOverlapRepairEntry>,
    val timingPreserved: Boolean,
) {
    val affectedTrackCount: Int get() = affectedTracks.size
    val affectedClipCount: Int get() = affectedClips.size
}

data class TimelineOverlapRepairOutcome(
    val document: ProjectDocument,
    val report: TimelineOverlapRepairReport,
)

class TimelineEngine(
    private val animationEngine: AnimationEngine = AnimationEngine(),
) {

    fun calculateTimelineDurationMs(document: ProjectDocument): Long {
        return document.tracks
            .flatMap { it.clips }
            .maxOfOrNull { it.timelineStartMs + it.durationMs }
            ?: 0L
    }

    fun evaluateFrame(document: ProjectDocument, playheadMs: Long): ActiveTimelineFrame {
        val safePlayhead = playheadMs.coerceAtLeast(0L)
        val sortedTracks = document.tracks.sortedBy { it.orderIndex }

        val visualLayers = mutableListOf<ActiveClipLayer>()
        val textLayers = mutableListOf<ActiveClipLayer>()
        val audioLayers = mutableListOf<ActiveClipLayer>()
        val adjustmentLayers = mutableListOf<ActiveClipLayer>()
        var outgoingVisualLayer: ActiveClipLayer? = null

        for (track in sortedTracks) {
            if (track.isMuted) continue
            val sortedClips = track.clips.sortedBy { it.timelineStartMs }
            for (activeIndex in sortedClips.indices) {
                val activeClip = sortedClips[activeIndex]
                if (safePlayhead < activeClip.timelineStartMs ||
                    safePlayhead >= (activeClip.timelineStartMs + activeClip.durationMs)
                ) {
                    continue
                }

                val relativeMs = (safePlayhead - activeClip.timelineStartMs)
                    .coerceIn(0L, activeClip.durationMs)

                val effectiveVol = computeEffectiveVolume(track, activeClip, relativeMs)
                val transition = activeClip.transitionIn?.takeIf { it.type != TransitionType.NONE && it.durationMs > 0L }
                val (transitionProgress, activeTransition) = if (transition != null && relativeMs < transition.durationMs) {
                    val rawT = (relativeMs.toFloat() / transition.durationMs.toFloat()).coerceIn(0f, 1f)
                    EffectsEngine.applyEasing(rawT, transition.easing) to transition
                } else {
                    1f to null
                }

                if (activeTransition != null &&
                    (track.type == TrackType.VIDEO || track.type == TrackType.OVERLAY) &&
                    outgoingVisualLayer == null &&
                    activeIndex > 0
                ) {
                    val prevClip = sortedClips[activeIndex - 1]
                    val prevEnd = prevClip.timelineStartMs + prevClip.durationMs
                    val gapMs = activeClip.timelineStartMs - prevEnd
                    if (gapMs in 0L..60L) {
                        outgoingVisualLayer = ActiveClipLayer(
                            track = track,
                            clip = prevClip,
                            clipRelativeMs = (prevClip.durationMs - 16L).coerceAtLeast(0L),
                            effectiveVolume = 0f,
                            transitionProgress = 1f,
                        )
                    }
                }

                val layer = ActiveClipLayer(
                    track = track,
                    clip = activeClip,
                    clipRelativeMs = relativeMs,
                    effectiveVolume = effectiveVol,
                    transitionProgress = transitionProgress,
                    activeTransition = activeTransition,
                )

                when (track.type) {
                    TrackType.VIDEO, TrackType.OVERLAY -> visualLayers += layer
                    TrackType.TEXT -> textLayers += layer
                    TrackType.AUDIO -> audioLayers += layer
                    TrackType.ADJUSTMENT -> adjustmentLayers += layer
                }
            }
        }

        return ActiveTimelineFrame(
            playheadMs = safePlayhead,
            visualLayers = visualLayers,
            audioLayers = audioLayers,
            textLayers = textLayers,
            adjustmentLayers = adjustmentLayers,
            outgoingVisualLayer = outgoingVisualLayer,
        )
    }

    private fun computeEffectiveVolume(
        track: TimelineTrack,
        clip: TimelineClipRef,
        clipRelativeMs: Long,
    ): Float {
        if (track.isMuted || clip.isMuted) return 0f
        val keyframedVolume = animationEngine.evaluateVolume(clip, clipRelativeMs)
        if (keyframedVolume <= 0f) return 0f
        var envelope = 1f
        if (clip.fadeInMs > 0L && clipRelativeMs < clip.fadeInMs) {
            envelope *= (clipRelativeMs.toFloat() / clip.fadeInMs.toFloat()).coerceIn(0f, 1f)
        }
        val remainingMs = (clip.durationMs - clipRelativeMs).coerceAtLeast(0L)
        if (clip.fadeOutMs > 0L && remainingMs < clip.fadeOutMs) {
            envelope *= (remainingMs.toFloat() / clip.fadeOutMs.toFloat()).coerceIn(0f, 1f)
        }
        return (keyframedVolume * envelope).coerceIn(0f, 2.0f)
    }

    fun collectSnapTargets(
        document: ProjectDocument,
        excludeClipId: String? = null,
        playheadMs: Long? = null,
    ): List<Long> {
        val targets = mutableSetOf(0L)
        if (playheadMs != null && playheadMs >= 0L) {
            targets += playheadMs
        }
        for (track in document.tracks) {
            for (clip in track.clips) {
                if (clip.clipId == excludeClipId) continue
                targets += clip.timelineStartMs
                targets += clip.timelineStartMs + clip.durationMs
            }
        }
        for (marker in document.markers) {
            if (marker.timeMs >= 0L) targets += marker.timeMs
        }
        return targets.sorted()
    }

    fun snapPosition(
        desiredMs: Long,
        snapTargets: List<Long>,
        snappingEnabled: Boolean,
        thresholdMs: Long = 160L,
    ): SnapResult {
        if (!snappingEnabled || snapTargets.isEmpty()) {
            return SnapResult(desiredMs, null)
        }
        var bestTarget: Long? = null
        var bestDelta = thresholdMs + 1L
        for (target in snapTargets) {
            val delta = abs(desiredMs - target)
            if (delta < bestDelta) {
                bestDelta = delta
                bestTarget = target
            }
        }
        return if (bestTarget != null && bestDelta <= thresholdMs) {
            SnapResult(bestTarget, bestTarget)
        } else {
            SnapResult(desiredMs, null)
        }
    }

    fun addTrack(
        document: ProjectDocument,
        type: TrackType,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, TimelineTrack> {
        val existingOfType = document.tracks.count { it.type == type }
        val number = existingOfType + 1
        val newTrack = TimelineTrack(
            id = "track_${type.name.lowercase()}_${UUID.randomUUID().toString().take(6)}",
            name = "${type.shortPrefix}$number ${type.label}",
            type = type,
            orderIndex = document.tracks.size,
        )
        val updated = document.copy(
            tracks = document.tracks + newTrack,
            updatedAt = nowMs,
        )
        return updated to newTrack
    }

    fun reorderTrack(
        document: ProjectDocument,
        trackId: String,
        delta: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val sorted = document.tracks.sortedBy { it.orderIndex }.toMutableList()
        val currentIdx = sorted.indexOfFirst { it.id == trackId }
        if (currentIdx == -1) return document
        val targetIdx = (currentIdx + delta).coerceIn(0, sorted.lastIndex)
        if (targetIdx == currentIdx) return document
        val moving = sorted.removeAt(currentIdx)
        sorted.add(targetIdx, moving)
        val reindexed = sorted.mapIndexed { index, track -> track.copy(orderIndex = index) }
        return document.copy(tracks = reindexed, updatedAt = nowMs)
    }

    fun toggleTrackMute(
        document: ProjectDocument,
        trackId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        return document.copy(
            tracks = document.tracks.map { track ->
                if (track.id == trackId) track.copy(isMuted = !track.isMuted) else track
            },
            updatedAt = nowMs,
        )
    }

    fun toggleTrackLock(
        document: ProjectDocument,
        trackId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        return document.copy(
            tracks = document.tracks.map { track ->
                if (track.id == trackId) track.copy(isLocked = !track.isLocked) else track
            },
            updatedAt = nowMs,
        )
    }

    fun removeTrack(
        document: ProjectDocument,
        trackId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        if (document.tracks.size <= 1) return document
        val target = document.tracks.firstOrNull { it.id == trackId } ?: return document
        if (target.isLocked) return document
        val remaining = document.tracks
            .filterNot { it.id == trackId }
            .sortedBy { it.orderIndex }
            .mapIndexed { index, track -> track.copy(orderIndex = index) }
        return document.copy(tracks = remaining, updatedAt = nowMs)
    }

    fun insertAssetAsClip(
        document: ProjectDocument,
        asset: MediaAsset,
        targetTrackId: String? = null,
        atTimelineMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, TimelineClipRef?> {
        val compatibleTypes = compatibleTrackTypes(asset.type)
        val targetTrack = if (targetTrackId != null) {
            document.tracks.firstOrNull {
                it.id == targetTrackId && it.type in compatibleTypes && !it.isLocked
            }
        } else {
            document.tracks.firstOrNull {
                it.type in compatibleTypes && !it.isLocked
            }
        } ?: return document to null

        val duration = when (asset.type) {
            MediaType.IMAGE -> 3_000L
            MediaType.VIDEO, MediaType.AUDIO -> asset.durationMs.coerceAtLeast(1_000L)
        }
        val sourceTotal = when (asset.type) {
            MediaType.IMAGE -> MAX_IMAGE_CLIP_DURATION_MS
            MediaType.VIDEO, MediaType.AUDIO -> asset.durationMs.coerceAtLeast(duration)
        }

        val desiredStart = atTimelineMs ?: (
            targetTrack.clips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L
        )
        val nonOverlappingStart = resolveNonOverlappingStart(
            existingClips = targetTrack.clips,
            desiredStartMs = desiredStart.coerceAtLeast(0L),
            durationMs = duration,
        )

        val newClip = TimelineClipRef(
            clipId = UUID.randomUUID().toString(),
            assetId = asset.id,
            trackId = targetTrack.id,
            timelineStartMs = nonOverlappingStart,
            sourceStartMs = 0L,
            durationMs = duration,
            sourceTotalDurationMs = sourceTotal,
        )

        val updatedTracks = document.tracks.map { track ->
            if (track.id == targetTrack.id) {
                track.copy(clips = (track.clips + newClip).sortedBy { it.timelineStartMs })
            } else {
                track
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs) to newClip
    }

    fun insertTextClip(
        document: ProjectDocument,
        spec: TextOverlaySpec,
        targetTrackId: String? = null,
        atTimelineMs: Long? = null,
        durationMs: Long = 3_000L,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, TimelineClipRef?> {
        var workingDoc = document
        var targetTrack = if (targetTrackId != null) {
            workingDoc.tracks.firstOrNull {
                it.id == targetTrackId && it.type == TrackType.TEXT && !it.isLocked
            }
        } else {
            workingDoc.tracks.firstOrNull { it.type == TrackType.TEXT && !it.isLocked }
        }
        if (targetTrack == null && targetTrackId == null) {
            val hasLockedOnly = workingDoc.tracks.any { it.type == TrackType.TEXT && it.isLocked }
            if (hasLockedOnly) return document to null
            val (docWithTrack, newTrack) = addTrack(workingDoc, TrackType.TEXT, nowMs)
            workingDoc = docWithTrack
            targetTrack = newTrack
        }
        val resolvedTrack = targetTrack ?: return document to null

        val safeDuration = durationMs.coerceIn(MIN_CLIP_DURATION_MS, MAX_IMAGE_CLIP_DURATION_MS)
        val desiredStart = (atTimelineMs ?: (
            resolvedTrack.clips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L
        )).coerceAtLeast(0L)

        val nonOverlappingStart = resolveNonOverlappingStart(
            existingClips = resolvedTrack.clips,
            desiredStartMs = desiredStart,
            durationMs = safeDuration,
        )

        val newClip = TimelineClipRef(
            clipId = UUID.randomUUID().toString(),
            assetId = TEXT_ASSET_PLACEHOLDER_ID,
            trackId = resolvedTrack.id,
            timelineStartMs = nonOverlappingStart,
            sourceStartMs = 0L,
            durationMs = safeDuration,
            sourceTotalDurationMs = MAX_IMAGE_CLIP_DURATION_MS,
            clipKind = ClipKind.TEXT,
            textSpec = spec,
        )

        val updatedTracks = workingDoc.tracks.map { track ->
            if (track.id == resolvedTrack.id) {
                track.copy(clips = (track.clips + newClip).sortedBy { it.timelineStartMs })
            } else {
                track
            }
        }
        return workingDoc.copy(tracks = updatedTracks, updatedAt = nowMs) to newClip
    }

    fun insertAdjustmentClip(
        document: ProjectDocument,
        adjustments: ClipAdjustments = ClipAdjustments(filterPreset = FilterPreset.CINEMATIC),
        shaderEffect: ClipShaderEffect = ClipShaderEffect(),
        targetTrackId: String? = null,
        atTimelineMs: Long? = null,
        durationMs: Long = 3_000L,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, TimelineClipRef?> {
        var workingDoc = document
        var targetTrack = if (targetTrackId != null) {
            workingDoc.tracks.firstOrNull {
                it.id == targetTrackId && it.type == TrackType.ADJUSTMENT && !it.isLocked
            }
        } else {
            workingDoc.tracks.firstOrNull { it.type == TrackType.ADJUSTMENT && !it.isLocked }
        }
        if (targetTrack == null && targetTrackId == null) {
            val hasLockedOnly = workingDoc.tracks.any { it.type == TrackType.ADJUSTMENT && it.isLocked }
            if (hasLockedOnly) return document to null
            val (docWithTrack, newTrack) = addTrack(workingDoc, TrackType.ADJUSTMENT, nowMs)
            workingDoc = docWithTrack
            targetTrack = newTrack
        }
        val resolvedTrack = targetTrack ?: return document to null

        val safeDuration = durationMs.coerceIn(MIN_CLIP_DURATION_MS, MAX_IMAGE_CLIP_DURATION_MS)
        val desiredStart = (atTimelineMs ?: (
            resolvedTrack.clips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L
        )).coerceAtLeast(0L)

        val nonOverlappingStart = resolveNonOverlappingStart(
            existingClips = resolvedTrack.clips,
            desiredStartMs = desiredStart,
            durationMs = safeDuration,
        )

        val newClip = TimelineClipRef(
            clipId = UUID.randomUUID().toString(),
            assetId = ADJUSTMENT_ASSET_PLACEHOLDER_ID,
            trackId = resolvedTrack.id,
            timelineStartMs = nonOverlappingStart,
            sourceStartMs = 0L,
            durationMs = safeDuration,
            sourceTotalDurationMs = MAX_IMAGE_CLIP_DURATION_MS,
            clipKind = ClipKind.ADJUSTMENT,
            adjustments = adjustments,
            shaderEffect = shaderEffect,
        )

        val updatedTracks = workingDoc.tracks.map { track ->
            if (track.id == resolvedTrack.id) {
                track.copy(clips = (track.clips + newClip).sortedBy { it.timelineStartMs })
            } else {
                track
            }
        }
        return workingDoc.copy(tracks = updatedTracks, updatedAt = nowMs) to newClip
    }

    fun updateClipText(
        document: ProjectDocument,
        clipId: String,
        spec: TextOverlaySpec,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind != ClipKind.TEXT) return@updateClipById clip
        clip.copy(textSpec = spec)
    }

    fun updateClipSpeed(
        document: ProjectDocument,
        clipId: String,
        newSpeed: Float,
        isImageClip: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document
        if (ownerTrack.isLocked) return document

        val clip = ownerTrack.clips.first { it.clipId == clipId }
        if (clip.clipKind != ClipKind.MEDIA || isImageClip) return document

        val clampedSpeed = newSpeed.coerceIn(0.25f, 4.0f)
        val hasExistingRamp = clip.speedRampPreset != SpeedRampPreset.NONE || clip.speedRampPoints.isNotEmpty()
        if (!hasExistingRamp && abs(clampedSpeed - clip.speed) < 0.001f) return document

        val currentSourceSpanMs = SpeedRampEngine.sourceSpanMs(clip).coerceAtLeast(MIN_CLIP_DURATION_MS)
        val newDurationMs = TimelineTimebase.scaleSourceToTimelineMs(currentSourceSpanMs, clampedSpeed)
            .coerceAtLeast(MIN_CLIP_DURATION_MS)
        return applyUpdatedClipDuration(
            document = document,
            ownerTrack = ownerTrack,
            clip = clip,
            newDurationMs = newDurationMs,
            nowMs = nowMs,
        ) { target, rescaledKeyframes ->
            target.copy(
                speed = clampedSpeed,
                speedRampPreset = SpeedRampPreset.NONE,
                speedRampPoints = emptyList(),
                durationMs = newDurationMs,
                fadeInMs = target.fadeInMs.coerceAtMost(newDurationMs),
                fadeOutMs = target.fadeOutMs.coerceAtMost(newDurationMs),
                transitionIn = target.transitionIn?.let {
                    it.copy(durationMs = it.durationMs.coerceAtMost(newDurationMs))
                },
                keyframeTracks = rescaledKeyframes,
            )
        }
    }

    fun updateClipSpeedRamp(
        document: ProjectDocument,
        clipId: String,
        preset: SpeedRampPreset,
        customPoints: List<SpeedPoint> = emptyList(),
        isImageClip: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document
        if (ownerTrack.isLocked) return document

        val clip = ownerTrack.clips.first { it.clipId == clipId }
        if (clip.clipKind != ClipKind.MEDIA || isImageClip) return document

        val normalizedPoints = when {
            preset == SpeedRampPreset.NONE && customPoints.isEmpty() -> emptyList()
            customPoints.isNotEmpty() -> SpeedRampEngine.normalizePoints(customPoints, clip.speed)
            else -> SpeedRampEngine.normalizePoints(SpeedRampEngine.presetPoints(preset), clip.speed)
        }

        val currentSourceSpanMs = SpeedRampEngine.sourceSpanMs(clip).coerceAtLeast(MIN_CLIP_DURATION_MS)
        val nextAvgSpeed = SpeedRampEngine.averageSpeed(normalizedPoints, clip.speed)
        val newDurationMs = if (normalizedPoints.isEmpty()) {
            TimelineTimebase.scaleSourceToTimelineMs(currentSourceSpanMs, clip.speed)
                .coerceAtLeast(MIN_CLIP_DURATION_MS)
        } else {
            SpeedRampEngine.computeRampedDurationMs(currentSourceSpanMs, normalizedPoints, clip.speed)
                .coerceAtLeast(MIN_CLIP_DURATION_MS)
        }

        return applyUpdatedClipDuration(
            document = document,
            ownerTrack = ownerTrack,
            clip = clip,
            newDurationMs = newDurationMs,
            nowMs = nowMs,
        ) { target, rescaledKeyframes ->
            target.copy(
                speed = if (normalizedPoints.isEmpty()) target.speed else nextAvgSpeed,
                speedRampPreset = preset,
                speedRampPoints = normalizedPoints,
                durationMs = newDurationMs,
                fadeInMs = target.fadeInMs.coerceAtMost(newDurationMs),
                fadeOutMs = target.fadeOutMs.coerceAtMost(newDurationMs),
                transitionIn = target.transitionIn?.let {
                    it.copy(durationMs = it.durationMs.coerceAtMost(newDurationMs))
                },
                keyframeTracks = rescaledKeyframes,
            )
        }
    }

    private fun applyUpdatedClipDuration(
        document: ProjectDocument,
        ownerTrack: TimelineTrack,
        clip: TimelineClipRef,
        newDurationMs: Long,
        nowMs: Long,
        mutateClip: (TimelineClipRef, List<KeyframeTrack>) -> TimelineClipRef,
    ): ProjectDocument {
        val durationDelta = newDurationMs - clip.durationMs
        val oldEndMs = clip.timelineStartMs + clip.durationMs

        val sortedClips = ownerTrack.clips.sortedBy { it.timelineStartMs }
        val clipIndex = sortedClips.indexOfFirst { it.clipId == clip.clipId }
        val nextClipStart = if (clipIndex < sortedClips.lastIndex) {
            sortedClips[clipIndex + 1].timelineStartMs
        } else Long.MAX_VALUE
        val overlapShift = if (clip.timelineStartMs + newDurationMs > nextClipStart) {
            (clip.timelineStartMs + newDurationMs) - nextClipStart
        } else 0L

        val durationRatio = newDurationMs.toDouble() / clip.durationMs.coerceAtLeast(1L).toDouble()
        val rescaledKeyframes = clip.keyframeTracks.map { kTrack ->
            kTrack.copy(
                keyframes = kTrack.keyframes.map { kf ->
                    kf.copy(timeMs = (kf.timeMs * durationRatio).roundToLong().coerceIn(0L, newDurationMs))
                }.sortedBy { it.timeMs },
            )
        }

        val updatedClip = mutateClip(clip, rescaledKeyframes)
        val updatedClips = sortedClips.map { c ->
            when {
                c.clipId == clip.clipId -> updatedClip
                overlapShift > 0L && c.timelineStartMs >= oldEndMs -> {
                    c.copy(timelineStartMs = c.timelineStartMs + overlapShift)
                }
                durationDelta < 0L -> c
                else -> c
            }
        }

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) track.copy(clips = updatedClips) else track
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    fun updateClipReversed(
        document: ProjectDocument,
        clipId: String,
        isReversed: Boolean,
        isImageClip: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind != ClipKind.MEDIA || isImageClip) clip
        else clip.copy(isReversed = isReversed)
    }

    fun updateClipChromaKey(
        document: ProjectDocument,
        clipId: String,
        chromaKey: ChromaKeySpec,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        clip.copy(
            chromaKey = chromaKey.copy(
                similarity = chromaKey.similarity.coerceIn(0.05f, 0.85f),
                smoothness = chromaKey.smoothness.coerceIn(0.01f, 0.50f),
                spillSuppression = chromaKey.spillSuppression.coerceIn(0f, 1f),
            ),
        )
    }

    fun updateClipLut(
        document: ProjectDocument,
        clipId: String,
        lutSpec: ClipLutSpec,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        clip.copy(
            lutSpec = lutSpec.copy(intensity = lutSpec.intensity.coerceIn(0f, 1f)),
        )
    }

    fun updateClipStabilization(
        document: ProjectDocument,
        clipId: String,
        stabilization: StabilizationSpec,
        generatedTracks: List<KeyframeTrack> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        val safeSpec = stabilization.copy(
            strength = stabilization.strength.coerceIn(0f, 1f),
            cropScale = stabilization.cropScale.coerceIn(1f, 1.35f),
        )
        val mergedTracks = if (!safeSpec.enabled) {
            val resetProps = setOf(
                KeyframeProperty.POSITION_X,
                KeyframeProperty.POSITION_Y,
                KeyframeProperty.SCALE_X,
                KeyframeProperty.SCALE_Y,
            )
            clip.keyframeTracks.filterNot { it.property in resetProps }
        } else if (generatedTracks.isNotEmpty()) {
            val replacedProps = generatedTracks.map { it.property }.toSet()
            clip.keyframeTracks.filterNot { it.property in replacedProps } + generatedTracks
        } else {
            clip.keyframeTracks
        }
        clip.copy(stabilization = safeSpec, keyframeTracks = mergedTracks)
    }

    fun mergeKeyframeTracks(
        document: ProjectDocument,
        clipId: String,
        tracks: List<KeyframeTrack>,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (tracks.isEmpty()) return@updateClipById clip
        val replacedProps = tracks.map { it.property }.toSet()
        clip.copy(keyframeTracks = clip.keyframeTracks.filterNot { it.property in replacedProps } + tracks)
    }

    fun updateClipAudio(
        document: ProjectDocument,
        clipId: String,
        volume: Float,
        isMuted: Boolean,
        fadeInMs: Long,
        fadeOutMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        val safeVol = volume.coerceIn(0f, 2.0f)
        val safeFadeIn = fadeInMs.coerceIn(0L, clip.durationMs)
        val safeFadeOut = fadeOutMs.coerceIn(0L, clip.durationMs)
        clip.copy(
            volume = safeVol,
            isMuted = isMuted,
            fadeInMs = safeFadeIn,
            fadeOutMs = safeFadeOut,
        )
    }

    fun updateClipAudioEffect(
        document: ProjectDocument,
        clipId: String,
        audioEffect: ClipAudioEffect,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        clip.copy(
            audioEffect = audioEffect.copy(
                pan = audioEffect.pan.coerceIn(-1f, 1f),
                bassDb = audioEffect.bassDb.coerceIn(-12f, 12f),
                trebleDb = audioEffect.trebleDb.coerceIn(-12f, 12f),
                echoMix = audioEffect.echoMix.coerceIn(0f, 0.75f),
            ),
        )
    }

    fun addTimelineMarker(
        document: ProjectDocument,
        timeMs: Long,
        label: String = "Beat",
        isBeatMarker: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, TimelineMarker> {
        val safeTime = timeMs.coerceAtLeast(0L)
        val existing = document.markers.firstOrNull { abs(it.timeMs - safeTime) <= 60L }
        if (existing != null) return document to existing

        val marker = TimelineMarker(
            id = UUID.randomUUID().toString(),
            timeMs = safeTime,
            label = label.ifBlank { "Marker" },
            isBeatMarker = isBeatMarker,
        )
        val next = (document.markers + marker).sortedBy { it.timeMs }
        return document.copy(markers = next, updatedAt = nowMs) to marker
    }

    fun addBeatMarkersFromClip(
        document: ProjectDocument,
        clip: TimelineClipRef,
        assetBeatTimestampsMs: List<Long>,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        if (assetBeatTimestampsMs.isEmpty()) return document
        val effectiveSpeed = SpeedRampEngine.averageSpeed(clip)
        val clipEndSourceMs = clip.sourceStartMs + SpeedRampEngine.sourceSpanMs(clip)
        val mapped = assetBeatTimestampsMs
            .filter { it in clip.sourceStartMs..clipEndSourceMs }
            .map { srcBeat ->
                val relSourceMs = if (clip.isReversed) {
                    (clipEndSourceMs - srcBeat).coerceAtLeast(0L)
                } else {
                    (srcBeat - clip.sourceStartMs).coerceAtLeast(0L)
                }
                val relTimelineMs = TimelineTimebase.scaleSourceToTimelineMs(relSourceMs, effectiveSpeed)
                clip.timelineStartMs + relTimelineMs
            }
        var working = document
        var added = 0
        for (tMs in mapped) {
            if (working.markers.none { abs(it.timeMs - tMs) <= 120L }) {
                added++
                val marker = TimelineMarker(
                    id = UUID.randomUUID().toString(),
                    timeMs = tMs,
                    label = "Beat ${working.markers.size + 1}",
                    isBeatMarker = true,
                )
                working = working.copy(markers = (working.markers + marker).sortedBy { it.timeMs }, updatedAt = nowMs)
            }
            if (added >= 32) break
        }
        return working
    }

    fun removeTimelineMarker(
        document: ProjectDocument,
        markerId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val next = document.markers.filterNot { it.id == markerId }
        return if (next.size == document.markers.size) document else document.copy(markers = next, updatedAt = nowMs)
    }

    fun clearTimelineMarkers(
        document: ProjectDocument,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        if (document.markers.isEmpty()) return document
        return document.copy(markers = emptyList(), updatedAt = nowMs)
    }

    fun updateClipTransition(
        document: ProjectDocument,
        clipId: String,
        transition: ClipTransition?,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        val sanitized = transition?.takeIf { it.type != TransitionType.NONE }?.let {
            it.copy(durationMs = it.durationMs.coerceIn(100L, clip.durationMs.coerceAtLeast(100L)))
        }
        clip.copy(transitionIn = sanitized)
    }

    fun updateClipAdjustments(
        document: ProjectDocument,
        clipId: String,
        adjustments: ClipAdjustments,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind == ClipKind.TEXT) return@updateClipById clip
        clip.copy(adjustments = adjustments)
    }

    fun updateClipTransform(
        document: ProjectDocument,
        clipId: String,
        transform: LayerTransform,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind == ClipKind.ADJUSTMENT) return@updateClipById clip
        clip.copy(
            transform = transform.copy(
                positionX = transform.positionX.coerceIn(-1f, 1f),
                positionY = transform.positionY.coerceIn(-1f, 1f),
                scaleX = transform.scaleX.coerceIn(0.1f, 4.0f),
                scaleY = transform.scaleY.coerceIn(0.1f, 4.0f),
                rotationDeg = transform.rotationDeg.coerceIn(-360f, 360f),
                opacity = transform.opacity.coerceIn(0f, 1f),
                anchorX = transform.anchorX.coerceIn(0f, 1f),
                anchorY = transform.anchorY.coerceIn(0f, 1f),
            ),
        )
    }

    fun updateClipBlendMode(
        document: ProjectDocument,
        clipId: String,
        blendMode: BlendModePreset,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind == ClipKind.ADJUSTMENT) return@updateClipById clip
        clip.copy(blendMode = blendMode)
    }

    fun updateClipMask(
        document: ProjectDocument,
        clipId: String,
        mask: LayerMask,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind != ClipKind.MEDIA) return@updateClipById clip
        clip.copy(
            mask = mask.copy(
                centerX = mask.centerX.coerceIn(0f, 1f),
                centerY = mask.centerY.coerceIn(0f, 1f),
                width = mask.width.coerceIn(0.05f, 1.5f),
                height = mask.height.coerceIn(0.05f, 1.5f),
                feather = mask.feather.coerceIn(0f, 0.5f),
                cornerRadius = mask.cornerRadius.coerceIn(0f, 0.5f),
            ),
        )
    }

    fun updateClipShaderEffect(
        document: ProjectDocument,
        clipId: String,
        shaderEffect: ClipShaderEffect,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind == ClipKind.TEXT) return@updateClipById clip
        clip.copy(
            shaderEffect = shaderEffect.copy(intensity = shaderEffect.intensity.coerceIn(0f, 1f)),
        )
    }

    // --- Phase 6: Keyframe & Animation Operations ---

    fun upsertKeyframe(
        document: ProjectDocument,
        clipId: String,
        property: KeyframeProperty,
        timeMs: Long,
        value: Float,
        interpolation: KeyframeInterpolation = KeyframeInterpolation.EASE_IN_OUT,
        bezier: BezierCurveSpec = BezierCurveSpec(),
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, PropertyKeyframe?> {
        var createdOrUpdated: PropertyKeyframe? = null
        val updated = updateClipById(document, clipId, nowMs) { clip ->
            val clampedTime = timeMs.coerceIn(0L, clip.durationMs)
            val clampedValue = value.coerceIn(property.minValue, property.maxValue)
            val existingTrack = clip.keyframeTracks.firstOrNull { it.property == property }
            val existingList = existingTrack?.keyframes.orEmpty()
            val nearby = existingList.firstOrNull { abs(it.timeMs - clampedTime) <= 30L }

            val nextKf = nearby?.copy(
                timeMs = clampedTime,
                value = clampedValue,
                interpolation = interpolation,
                bezier = bezier,
            ) ?: PropertyKeyframe(
                id = UUID.randomUUID().toString(),
                timeMs = clampedTime,
                value = clampedValue,
                interpolation = interpolation,
                bezier = bezier,
            )
            createdOrUpdated = nextKf

            val nextList = (existingList.filterNot { it.id == nextKf.id } + nextKf).sortedBy { it.timeMs }
            val nextTracks = clip.keyframeTracks
                .filterNot { it.property == property }
                .plus(KeyframeTrack(property = property, keyframes = nextList))
                .sortedBy { it.property.ordinal }
            clip.copy(keyframeTracks = nextTracks)
        }
        return updated to createdOrUpdated
    }

    fun updateKeyframeById(
        document: ProjectDocument,
        clipId: String,
        property: KeyframeProperty,
        keyframeId: String,
        newTimeMs: Long? = null,
        newValue: Float? = null,
        interpolation: KeyframeInterpolation? = null,
        bezier: BezierCurveSpec? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        val nextTracks = clip.keyframeTracks.map { kTrack ->
            if (kTrack.property != property) {
                kTrack
            } else {
                val updatedList = kTrack.keyframes.map { kf ->
                    if (kf.id == keyframeId) {
                        kf.copy(
                            timeMs = (newTimeMs ?: kf.timeMs).coerceIn(0L, clip.durationMs),
                            value = (newValue ?: kf.value).coerceIn(property.minValue, property.maxValue),
                            interpolation = interpolation ?: kf.interpolation,
                            bezier = bezier ?: kf.bezier,
                        )
                    } else {
                        kf
                    }
                }.sortedBy { it.timeMs }
                kTrack.copy(keyframes = updatedList)
            }
        }
        clip.copy(keyframeTracks = nextTracks)
    }

    fun updateMultipleKeyframesInterpolation(
        document: ProjectDocument,
        clipId: String,
        property: KeyframeProperty,
        keyframeIds: Set<String>,
        interpolation: KeyframeInterpolation,
        bezier: BezierCurveSpec? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        val nextTracks = clip.keyframeTracks.map { kTrack ->
            if (kTrack.property != property) {
                kTrack
            } else {
                kTrack.copy(
                    keyframes = kTrack.keyframes.map { kf ->
                        if (kf.id in keyframeIds) {
                            kf.copy(
                                interpolation = interpolation,
                                bezier = bezier ?: kf.bezier,
                            )
                        } else {
                            kf
                        }
                    },
                )
            }
        }
        clip.copy(keyframeTracks = nextTracks)
    }

    fun removeKeyframes(
        document: ProjectDocument,
        clipId: String,
        property: KeyframeProperty,
        keyframeIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (keyframeIds.isEmpty()) return@updateClipById clip
        val nextTracks = clip.keyframeTracks.mapNotNull { kTrack ->
            if (kTrack.property != property) {
                kTrack
            } else {
                val filtered = kTrack.keyframes.filterNot { it.id in keyframeIds }
                if (filtered.isEmpty()) null else kTrack.copy(keyframes = filtered)
            }
        }
        clip.copy(keyframeTracks = nextTracks)
    }

    fun clearKeyframeTrack(
        document: ProjectDocument,
        clipId: String,
        property: KeyframeProperty,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        clip.copy(keyframeTracks = clip.keyframeTracks.filterNot { it.property == property })
    }

    fun applyMotionPresetToClip(
        document: ProjectDocument,
        clipId: String,
        preset: MotionPreset,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        val nextTracks = animationEngine.generatePresetKeyframeTracks(clip, preset)
        clip.copy(keyframeTracks = nextTracks)
    }

    fun applyAdjustmentsToAllVisualClips(
        document: ProjectDocument,
        adjustments: ClipAdjustments,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val updatedTracks = document.tracks.map { track ->
            if (track.isLocked || (track.type != TrackType.VIDEO && track.type != TrackType.OVERLAY)) {
                track
            } else {
                track.copy(
                    clips = track.clips.map { clip ->
                        if (clip.clipKind == ClipKind.MEDIA) {
                            clip.copy(adjustments = adjustments)
                        } else {
                            clip
                        }
                    },
                )
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    private fun updateClipById(
        document: ProjectDocument,
        clipId: String,
        nowMs: Long,
        transform: (TimelineClipRef) -> TimelineClipRef,
    ): ProjectDocument {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document
        if (ownerTrack.isLocked) return document

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                track.copy(
                    clips = track.clips.map { if (it.clipId == clipId) transform(it) else it },
                )
            } else {
                track
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    fun splitClipAt(
        document: ProjectDocument,
        clipId: String,
        splitTimelineMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, String?> {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document to null
        if (ownerTrack.isLocked) return document to null

        val targetClip = ownerTrack.clips.first { it.clipId == clipId }
        val clipEnd = targetClip.timelineStartMs + targetClip.durationMs
        if (splitTimelineMs < targetClip.timelineStartMs + MIN_CLIP_DURATION_MS ||
            splitTimelineMs > clipEnd - MIN_CLIP_DURATION_MS
        ) {
            return document to null
        }

        val leftDuration = splitTimelineMs - targetClip.timelineStartMs
        val rightDuration = targetClip.durationMs - leftDuration

        val (leftTracks, rightTracks) = splitKeyframeTracks(targetClip, leftDuration, rightDuration)
        val splitSourcePos = SpeedRampEngine.clipRelativeToSourceMs(
            targetClip,
            leftDuration,
            targetClip.sourceTotalDurationMs,
        )
        val leftSourceStart = if (targetClip.isReversed) splitSourcePos else targetClip.sourceStartMs
        val rightSourceStart = if (targetClip.isReversed) targetClip.sourceStartMs else splitSourcePos

        val splitNorm = (leftDuration.toFloat() / targetClip.durationMs.coerceAtLeast(1L).toFloat())
            .coerceIn(0.01f, 0.99f)
        val existingRamp = SpeedRampEngine.effectivePoints(targetClip)
        val leftRamp: List<SpeedPoint>
        val rightRamp: List<SpeedPoint>
        if (existingRamp.isEmpty()) {
            leftRamp = emptyList()
            rightRamp = emptyList()
        } else {
            val splitSpeed = SpeedRampEngine.evaluateSpeedAtNormalized(existingRamp, splitNorm, targetClip.speed)
            leftRamp = SpeedRampEngine.normalizePoints(
                existingRamp.filter { it.normalizedTime < splitNorm }
                    .map { it.copy(normalizedTime = it.normalizedTime / splitNorm) } +
                    SpeedPoint(1f, splitSpeed),
                targetClip.speed,
            )
            rightRamp = SpeedRampEngine.normalizePoints(
                listOf(SpeedPoint(0f, splitSpeed)) +
                    existingRamp.filter { it.normalizedTime > splitNorm }
                        .map { it.copy(normalizedTime = (it.normalizedTime - splitNorm) / (1f - splitNorm)) },
                targetClip.speed,
            )
        }

        val leftClip = targetClip.copy(
            sourceStartMs = leftSourceStart,
            durationMs = leftDuration,
            speed = if (leftRamp.isEmpty()) targetClip.speed else SpeedRampEngine.averageSpeed(leftRamp, targetClip.speed),
            speedRampPoints = leftRamp,
            fadeInMs = targetClip.fadeInMs.coerceAtMost(leftDuration),
            fadeOutMs = 0L,
            transitionIn = targetClip.transitionIn?.let {
                it.copy(durationMs = it.durationMs.coerceAtMost(leftDuration))
            },
            keyframeTracks = leftTracks,
        )
        val rightClipId = UUID.randomUUID().toString()
        val rightClip = targetClip.copy(
            clipId = rightClipId,
            timelineStartMs = splitTimelineMs,
            sourceStartMs = rightSourceStart,
            durationMs = rightDuration,
            speed = if (rightRamp.isEmpty()) targetClip.speed else SpeedRampEngine.averageSpeed(rightRamp, targetClip.speed),
            speedRampPoints = rightRamp,
            fadeInMs = 0L,
            fadeOutMs = targetClip.fadeOutMs.coerceAtMost(rightDuration),
            transitionIn = null,
            keyframeTracks = rightTracks,
        )

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                val nextClips = track.clips
                    .filterNot { it.clipId == clipId }
                    .plus(listOf(leftClip, rightClip))
                    .sortedBy { it.timelineStartMs }
                track.copy(clips = nextClips)
            } else {
                track
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs) to rightClipId
    }

    fun trimClipLeft(
        document: ProjectDocument,
        clipId: String,
        desiredStartMs: Long,
        snappingEnabled: Boolean = false,
        playheadMs: Long? = null,
        snapThresholdMs: Long = 160L,
        nowMs: Long = System.currentTimeMillis(),
    ): ClipTransformResult {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return ClipTransformResult(document)
        if (ownerTrack.isLocked) return ClipTransformResult(document)

        val sortedClips = ownerTrack.clips.sortedBy { it.timelineStartMs }
        val clipIndex = sortedClips.indexOfFirst { it.clipId == clipId }
        val clip = sortedClips[clipIndex]
        val clipEnd = clip.timelineStartMs + clip.durationMs

        val snapTargets = collectSnapTargets(document, excludeClipId = clipId, playheadMs = playheadMs)
        val snapped = snapPosition(desiredStartMs, snapTargets, snappingEnabled, snapThresholdMs)

        val avgSpeed = SpeedRampEngine.averageSpeed(clip)
        val prevClipEnd = if (clipIndex > 0) {
            sortedClips[clipIndex - 1].let { it.timelineStartMs + it.durationMs }
        } else 0L
        val earliestSourceLimit = when {
            clip.clipKind != ClipKind.MEDIA -> 0L
            !clip.isReversed -> {
                clip.timelineStartMs - TimelineTimebase.scaleSourceToTimelineMs(clip.sourceStartMs, avgSpeed)
            }
            clip.sourceTotalDurationMs > 0L -> {
                val curEndSrc = clip.sourceStartMs + SpeedRampEngine.sourceSpanMs(clip)
                val availHead = (clip.sourceTotalDurationMs - curEndSrc).coerceAtLeast(0L)
                clip.timelineStartMs - TimelineTimebase.scaleSourceToTimelineMs(availHead, avgSpeed)
            }
            else -> 0L
        }
        val minStart = maxOf(0L, prevClipEnd, earliestSourceLimit)
        val maxStart = clipEnd - MIN_CLIP_DURATION_MS

        val clampedStart = snapped.positionMs.coerceIn(minStart, maxStart)
        if (clampedStart == clip.timelineStartMs) {
            return ClipTransformResult(document, snapped.snappedTargetMs)
        }

        val deltaTimeline = clampedStart - clip.timelineStartMs
        val newSourceStart = when {
            clip.clipKind != ClipKind.MEDIA -> 0L
            clip.isReversed -> clip.sourceStartMs
            deltaTimeline > 0L -> {
                SpeedRampEngine.clipRelativeToSourceMs(clip, deltaTimeline, clip.sourceTotalDurationMs)
            }
            else -> {
                (clip.sourceStartMs + (deltaTimeline * avgSpeed.toDouble()).roundToLong()).coerceAtLeast(0L)
            }
        }
        val newDuration = clipEnd - clampedStart
        val trimmed = clip.copy(
            timelineStartMs = clampedStart,
            sourceStartMs = newSourceStart,
            durationMs = newDuration,
            fadeInMs = clip.fadeInMs.coerceAtMost(newDuration),
            fadeOutMs = clip.fadeOutMs.coerceAtMost(newDuration),
            transitionIn = clip.transitionIn?.let {
                it.copy(durationMs = it.durationMs.coerceAtMost(newDuration))
            },
            keyframeTracks = shiftAndClampKeyframes(clip.keyframeTracks, -deltaTimeline, newDuration),
        )

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                track.copy(
                    clips = track.clips.map { if (it.clipId == clipId) trimmed else it }
                        .sortedBy { it.timelineStartMs },
                )
            } else {
                track
            }
        }
        val activeSnap = if (clampedStart == snapped.snappedTargetMs) snapped.snappedTargetMs else null
        return ClipTransformResult(document.copy(tracks = updatedTracks, updatedAt = nowMs), activeSnap)
    }

    fun trimClipRight(
        document: ProjectDocument,
        clipId: String,
        desiredEndMs: Long,
        maxSourceDurationMs: Long = 0L,
        snappingEnabled: Boolean = false,
        playheadMs: Long? = null,
        snapThresholdMs: Long = 160L,
        nowMs: Long = System.currentTimeMillis(),
    ): ClipTransformResult {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return ClipTransformResult(document)
        if (ownerTrack.isLocked) return ClipTransformResult(document)

        val sortedClips = ownerTrack.clips.sortedBy { it.timelineStartMs }
        val clipIndex = sortedClips.indexOfFirst { it.clipId == clipId }
        val clip = sortedClips[clipIndex]

        val snapTargets = collectSnapTargets(document, excludeClipId = clipId, playheadMs = playheadMs)
        val snapped = snapPosition(desiredEndMs, snapTargets, snappingEnabled, snapThresholdMs)

        val minEnd = clip.timelineStartMs + MIN_CLIP_DURATION_MS
        val nextClipStart = if (clipIndex < sortedClips.lastIndex) {
            sortedClips[clipIndex + 1].timelineStartMs
        } else Long.MAX_VALUE

        val avgSpeed = SpeedRampEngine.averageSpeed(clip)
        val resolvedSourceCap = maxOf(maxSourceDurationMs, clip.sourceTotalDurationMs)
        val sourceEndLimit = if (resolvedSourceCap > 0L && clip.clipKind == ClipKind.MEDIA) {
            val remainingSource = if (clip.isReversed) {
                clip.durationMs + TimelineTimebase.scaleSourceToTimelineMs(clip.sourceStartMs, avgSpeed)
            } else {
                TimelineTimebase.scaleSourceToTimelineMs(
                    (resolvedSourceCap - clip.sourceStartMs).coerceAtLeast(0L),
                    avgSpeed,
                ).coerceAtLeast(MIN_CLIP_DURATION_MS)
            }
            clip.timelineStartMs + remainingSource
        } else {
            Long.MAX_VALUE
        }
        val maxEnd = minOf(nextClipStart, sourceEndLimit).coerceAtLeast(minEnd)

        val clampedEnd = snapped.positionMs.coerceIn(minEnd, maxEnd)
        val newDuration = clampedEnd - clip.timelineStartMs
        if (newDuration == clip.durationMs) {
            return ClipTransformResult(document, snapped.snappedTargetMs)
        }

        val newSourceStart = if (clip.clipKind == ClipKind.MEDIA && clip.isReversed) {
            val durDelta = newDuration - clip.durationMs
            val srcDelta = (durDelta.toDouble() * avgSpeed.toDouble()).roundToLong()
            (clip.sourceStartMs - srcDelta).coerceAtLeast(0L)
        } else {
            clip.sourceStartMs
        }

        val trimmed = clip.copy(
            sourceStartMs = newSourceStart,
            durationMs = newDuration,
            fadeInMs = clip.fadeInMs.coerceAtMost(newDuration),
            fadeOutMs = clip.fadeOutMs.coerceAtMost(newDuration),
            transitionIn = clip.transitionIn?.let {
                it.copy(durationMs = it.durationMs.coerceAtMost(newDuration))
            },
            keyframeTracks = shiftAndClampKeyframes(clip.keyframeTracks, 0L, newDuration),
        )
        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                track.copy(
                    clips = track.clips.map { if (it.clipId == clipId) trimmed else it },
                )
            } else {
                track
            }
        }
        val activeSnap = if (clampedEnd == snapped.snappedTargetMs) snapped.snappedTargetMs else null
        return ClipTransformResult(document.copy(tracks = updatedTracks, updatedAt = nowMs), activeSnap)
    }

    fun moveClip(
        document: ProjectDocument,
        clipId: String,
        desiredStartMs: Long,
        targetTrackId: String? = null,
        assetType: MediaType? = null,
        snappingEnabled: Boolean = true,
        playheadMs: Long? = null,
        snapThresholdMs: Long = 160L,
        nowMs: Long = System.currentTimeMillis(),
    ): ClipTransformResult {
        val sourceTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return ClipTransformResult(document)
        if (sourceTrack.isLocked) return ClipTransformResult(document)

        val clip = sourceTrack.clips.first { it.clipId == clipId }
        val resolvedTargetTrack = if (targetTrackId != null && targetTrackId != sourceTrack.id) {
            val candidate = document.tracks.firstOrNull { it.id == targetTrackId }
            val typeOk = when {
                clip.clipKind == ClipKind.TEXT -> candidate?.type == TrackType.TEXT
                clip.clipKind == ClipKind.ADJUSTMENT -> candidate?.type == TrackType.ADJUSTMENT
                assetType != null -> candidate != null && candidate.type in compatibleTrackTypes(assetType)
                else -> candidate?.type == sourceTrack.type
            }
            if (candidate != null && !candidate.isLocked && typeOk) candidate else sourceTrack
        } else {
            sourceTrack
        }

        val snapTargets = collectSnapTargets(document, excludeClipId = clipId, playheadMs = playheadMs)
        val startSnap = snapPosition(desiredStartMs.coerceAtLeast(0L), snapTargets, snappingEnabled, snapThresholdMs)
        val endSnap = snapPosition(
            (desiredStartMs + clip.durationMs).coerceAtLeast(clip.durationMs),
            snapTargets,
            snappingEnabled,
            snapThresholdMs,
        )

        val (snappedStart, activeSnapTarget) = when {
            startSnap.snappedTargetMs != null &&
                (endSnap.snappedTargetMs == null ||
                    abs(desiredStartMs - startSnap.snappedTargetMs) <= abs((desiredStartMs + clip.durationMs) - endSnap.snappedTargetMs)) ->
                startSnap.positionMs to startSnap.snappedTargetMs
            endSnap.snappedTargetMs != null ->
                (endSnap.positionMs - clip.durationMs).coerceAtLeast(0L) to endSnap.snappedTargetMs
            else -> desiredStartMs.coerceAtLeast(0L) to null
        }

        val otherClipsOnTarget = resolvedTargetTrack.clips.filterNot { it.clipId == clipId }
        val finalStart = resolveNearestValidStart(
            existingClips = otherClipsOnTarget,
            desiredStartMs = snappedStart,
            durationMs = clip.durationMs,
        )

        val movedClip = clip.copy(
            trackId = resolvedTargetTrack.id,
            timelineStartMs = finalStart,
        )

        val updatedTracks = document.tracks.map { track ->
            when {
                track.id == sourceTrack.id && resolvedTargetTrack.id == sourceTrack.id -> {
                    track.copy(
                        clips = (otherClipsOnTarget + movedClip).sortedBy { it.timelineStartMs },
                    )
                }
                track.id == sourceTrack.id -> {
                    track.copy(clips = track.clips.filterNot { it.clipId == clipId })
                }
                track.id == resolvedTargetTrack.id -> {
                    track.copy(
                        clips = (track.clips + movedClip).sortedBy { it.timelineStartMs },
                    )
                }
                else -> track
            }
        }
        val finalSnapGuide = if (finalStart == activeSnapTarget || finalStart + clip.durationMs == activeSnapTarget) {
            activeSnapTarget
        } else {
            null
        }
        return ClipTransformResult(document.copy(tracks = updatedTracks, updatedAt = nowMs), finalSnapGuide)
    }

    fun duplicateClip(
        document: ProjectDocument,
        clipId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, String?> {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document to null
        if (ownerTrack.isLocked) return document to null

        val sourceClip = ownerTrack.clips.first { it.clipId == clipId }
        val insertStart = resolveNonOverlappingStart(
            existingClips = ownerTrack.clips,
            desiredStartMs = sourceClip.timelineStartMs + sourceClip.durationMs,
            durationMs = sourceClip.durationMs,
        )
        val duplicatedId = UUID.randomUUID().toString()
        val duplicated = sourceClip.copy(
            clipId = duplicatedId,
            timelineStartMs = insertStart,
        )
        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                track.copy(clips = (track.clips + duplicated).sortedBy { it.timelineStartMs })
            } else {
                track
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs) to duplicatedId
    }

    fun removeClip(
        document: ProjectDocument,
        clipId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = removeClips(document, setOf(clipId), nowMs)

    fun removeClips(
        document: ProjectDocument,
        clipIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        if (clipIds.isEmpty()) return document
        val updatedTracks = document.tracks.map { track ->
            if (track.isLocked) {
                track
            } else {
                track.copy(clips = track.clips.filterNot { it.clipId in clipIds })
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    fun rippleDeleteClip(
        document: ProjectDocument,
        clipId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document
        if (ownerTrack.isLocked) return document

        val targetClip = ownerTrack.clips.first { it.clipId == clipId }
        val shiftAmount = targetClip.durationMs
        val targetEnd = targetClip.timelineStartMs + targetClip.durationMs

        val updatedClips = ownerTrack.clips
            .filterNot { it.clipId == clipId }
            .sortedBy { it.timelineStartMs }
            .map { clip ->
                if (clip.timelineStartMs >= targetEnd) {
                    clip.copy(timelineStartMs = (clip.timelineStartMs - shiftAmount).coerceAtLeast(0L))
                } else {
                    clip
                }
            }

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) track.copy(clips = updatedClips) else track
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    private fun compatibleTrackTypes(assetType: MediaType): Set<TrackType> = when (assetType) {
        MediaType.VIDEO, MediaType.IMAGE -> setOf(TrackType.VIDEO, TrackType.OVERLAY)
        MediaType.AUDIO -> setOf(TrackType.AUDIO)
    }

    fun hasOverlappingClips(document: ProjectDocument): Boolean {
        return document.tracks.any { track ->
            val sorted = track.clips.sortedWith(compareBy({ it.timelineStartMs }, { it.clipId }))
            var prevEnd = 0L
            for ((idx, clip) in sorted.withIndex()) {
                if (clip.timelineStartMs < 0L || clip.durationMs < 1L) return@any true
                if (idx > 0 && clip.timelineStartMs < prevEnd) return@any true
                prevEnd = clip.timelineStartMs + clip.durationMs
            }
            false
        }
    }

    /**
     * Read-only inspection that builds a [TimelineOverlapRepairReport] identifying every affected
     * track, clip, original position, and proposed repaired position without modifying [document].
     */
    fun inspectTrackOverlaps(
        document: ProjectDocument,
        mode: TimelineOverlapRepairMode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
    ): TimelineOverlapRepairReport {
        return computeOverlapRepairPlan(document, mode).report
    }

    /**
     * Applies an explicit non-silent overlap repair strategy to [document] and returns both the
     * repaired [ProjectDocument] and the full [TimelineOverlapRepairReport].
     */
    fun repairOverlappingTracks(
        document: ProjectDocument,
        mode: TimelineOverlapRepairMode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
        nowMs: Long = System.currentTimeMillis(),
    ): TimelineOverlapRepairOutcome {
        val outcome = computeOverlapRepairPlan(document, mode)
        val finalDoc = if (outcome.document != document) {
            outcome.document.copy(updatedAt = nowMs)
        } else {
            document
        }
        return TimelineOverlapRepairOutcome(finalDoc, outcome.report)
    }

    /**
     * Explicit shift-forward track normalization retained for callers requesting sequential compaction.
     * Load/save paths must inspect with [inspectTrackOverlaps] instead of calling this silently.
     */
    fun normalizeNonOverlappingTracks(document: ProjectDocument): ProjectDocument {
        return computeOverlapRepairPlan(document, TimelineOverlapRepairMode.SHIFT_FORWARD_ON_TRACK).document
    }

    private fun computeOverlapRepairPlan(
        document: ProjectDocument,
        mode: TimelineOverlapRepairMode,
    ): TimelineOverlapRepairOutcome {
        return when (mode) {
            TimelineOverlapRepairMode.SHIFT_FORWARD_ON_TRACK -> computeShiftForwardRepair(document)
            TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK -> computeOverflowTrackRepair(document)
        }
    }

    private fun computeShiftForwardRepair(document: ProjectDocument): TimelineOverlapRepairOutcome {
        val trackEntries = mutableListOf<TrackOverlapRepairEntry>()
        val allEntries = mutableListOf<ClipOverlapRepairEntry>()
        var changed = false

        val updatedTracks = document.tracks.sortedBy { it.orderIndex }.map { track ->
            if (track.clips.isEmpty()) return@map track
            val sorted = track.clips.sortedWith(compareBy({ it.timelineStartMs }, { it.clipId }))
            val keptClips = mutableListOf<TimelineClipRef>()
            val repairedClips = mutableListOf<TimelineClipRef>()
            val affectedOnTrack = mutableListOf<ClipOverlapRepairEntry>()
            var cursorMs = 0L
            var trackChanged = sorted != track.clips

            for (clip in sorted) {
                val safeDuration = clip.durationMs.coerceAtLeast(MIN_CLIP_DURATION_MS)
                val conflicting = keptClips.filter { prev ->
                    (prev.timelineStartMs + prev.durationMs) > clip.timelineStartMs
                }
                val maxOverlapMs = conflicting.maxOfOrNull { prev ->
                    val prevEnd = prev.timelineStartMs + prev.durationMs
                    val clipEnd = clip.timelineStartMs.coerceAtLeast(0L) + safeDuration
                    (minOf(prevEnd, clipEnd) - clip.timelineStartMs.coerceAtLeast(0L)).coerceAtLeast(0L)
                } ?: 0L
                val safeStart = clip.timelineStartMs.coerceAtLeast(cursorMs)

                val action = when {
                    safeStart != clip.timelineStartMs -> OverlapRepairAction.SHIFT_FORWARD
                    safeDuration != clip.durationMs -> OverlapRepairAction.CLAMP_MIN_DURATION
                    clip.trackId != track.id -> OverlapRepairAction.FIX_TRACK_ID
                    else -> null
                }

                if (action != null) {
                    trackChanged = true
                    val entry = ClipOverlapRepairEntry(
                        clipId = clip.clipId,
                        assetId = clip.assetId,
                        clipKind = clip.clipKind,
                        sourceTrackId = track.id,
                        sourceTrackName = track.name,
                        overlappingWithClipIds = conflicting.map { it.clipId },
                        overlapDurationMs = maxOverlapMs,
                        originalStartMs = clip.timelineStartMs,
                        originalDurationMs = clip.durationMs,
                        proposedTrackId = track.id,
                        proposedTrackName = track.name,
                        proposedStartMs = safeStart,
                        proposedDurationMs = safeDuration,
                        action = action,
                    )
                    affectedOnTrack += entry
                    allEntries += entry
                    val updatedClip = clip.copy(
                        trackId = track.id,
                        timelineStartMs = safeStart,
                        durationMs = safeDuration,
                    )
                    repairedClips += updatedClip
                    keptClips += updatedClip
                } else {
                    repairedClips += clip
                    keptClips += clip
                }
                cursorMs = safeStart + safeDuration
            }

            if (affectedOnTrack.isNotEmpty()) {
                trackEntries += TrackOverlapRepairEntry(
                    trackId = track.id,
                    trackName = track.name,
                    trackType = track.type,
                    affectedClips = affectedOnTrack,
                )
            }

            if (trackChanged) {
                changed = true
                track.copy(clips = repairedClips)
            } else {
                track
            }
        }

        val repairedDoc = if (changed) document.copy(tracks = updatedTracks) else document
        val report = TimelineOverlapRepairReport(
            mode = TimelineOverlapRepairMode.SHIFT_FORWARD_ON_TRACK,
            hasOverlaps = allEntries.any {
                it.overlappingWithClipIds.isNotEmpty() || it.originalStartMs < 0L || it.originalDurationMs < 1L
            },
            affectedTracks = trackEntries,
            affectedClips = allEntries,
            timingPreserved = allEntries.none { it.timingChanged },
        )
        return TimelineOverlapRepairOutcome(repairedDoc, report)
    }

    private fun computeOverflowTrackRepair(document: ProjectDocument): TimelineOverlapRepairOutcome {
        val orderedTracks = document.tracks.sortedBy { it.orderIndex }
        val workingTracks = orderedTracks.map { it.copy(clips = emptyList()) }.toMutableList()
        val clipsByTrackId = orderedTracks.associate { it.id to mutableListOf<TimelineClipRef>() }.toMutableMap()
        val trackEntriesById = linkedMapOf<String, MutableList<ClipOverlapRepairEntry>>()
        val trackMetaById = orderedTracks.associateBy { it.id }.toMutableMap()
        val allEntries = mutableListOf<ClipOverlapRepairEntry>()
        var changed = false

        // First pass: keep earliest non-overlapping clips on each track in place, collect conflicting clips.
        data class PendingOverflow(
            val sourceTrack: TimelineTrack,
            val clip: TimelineClipRef,
            val safeStart: Long,
            val safeDuration: Long,
            val conflictingIds: List<String>,
            val maxOverlapMs: Long,
        )
        val pendingOverflows = mutableListOf<PendingOverflow>()

        for (track in orderedTracks) {
            if (track.clips.isEmpty()) continue
            val sorted = track.clips.sortedWith(compareBy({ it.timelineStartMs }, { it.clipId }))
            if (sorted != track.clips) changed = true
            var keptCursorEnd = 0L
            val keptClips = clipsByTrackId.getValue(track.id)

            for (clip in sorted) {
                val safeDuration = clip.durationMs.coerceAtLeast(MIN_CLIP_DURATION_MS)
                val safeStart = clip.timelineStartMs.coerceAtLeast(0L)
                val conflicting = keptClips.filter { prev ->
                    (prev.timelineStartMs + prev.durationMs) > safeStart
                }
                if (conflicting.isNotEmpty()) {
                    val maxOverlapMs = conflicting.maxOf { prev ->
                        val prevEnd = prev.timelineStartMs + prev.durationMs
                        val clipEnd = safeStart + safeDuration
                        (minOf(prevEnd, clipEnd) - safeStart).coerceAtLeast(0L)
                    }
                    pendingOverflows += PendingOverflow(
                        sourceTrack = track,
                        clip = clip,
                        safeStart = safeStart,
                        safeDuration = safeDuration,
                        conflictingIds = conflicting.map { it.clipId },
                        maxOverlapMs = maxOverlapMs,
                    )
                } else {
                    val fixed = if (safeStart != clip.timelineStartMs ||
                        safeDuration != clip.durationMs ||
                        clip.trackId != track.id
                    ) {
                        changed = true
                        val action = when {
                            safeStart != clip.timelineStartMs -> OverlapRepairAction.SHIFT_FORWARD
                            safeDuration != clip.durationMs -> OverlapRepairAction.CLAMP_MIN_DURATION
                            else -> OverlapRepairAction.FIX_TRACK_ID
                        }
                        val entry = ClipOverlapRepairEntry(
                            clipId = clip.clipId,
                            assetId = clip.assetId,
                            clipKind = clip.clipKind,
                            sourceTrackId = track.id,
                            sourceTrackName = track.name,
                            overlappingWithClipIds = emptyList(),
                            overlapDurationMs = 0L,
                            originalStartMs = clip.timelineStartMs,
                            originalDurationMs = clip.durationMs,
                            proposedTrackId = track.id,
                            proposedTrackName = track.name,
                            proposedStartMs = safeStart,
                            proposedDurationMs = safeDuration,
                            action = action,
                        )
                        allEntries += entry
                        trackEntriesById.getOrPut(track.id) { mutableListOf() } += entry
                        clip.copy(
                            trackId = track.id,
                            timelineStartMs = safeStart,
                            durationMs = safeDuration,
                        )
                    } else {
                        clip
                    }
                    keptClips += fixed
                    keptCursorEnd = maxOf(keptCursorEnd, fixed.timelineStartMs + fixed.durationMs)
                }
            }
        }

        // Second pass: place each conflicting clip at its EXACT timing on an existing compatible track
        // (with no interval collision) or on a newly created overflow track.
        var overflowSeq = 1
        for (pending in pendingOverflows) {
            changed = true
            val preferredOverflowType = overflowTrackTypeFor(pending.sourceTrack.type, pending.clip.clipKind)
            val candidateTrack = workingTracks.firstOrNull { candidate ->
                candidate.id != pending.sourceTrack.id &&
                    !candidate.isLocked &&
                    candidate.type == preferredOverflowType &&
                    clipsByTrackId.getValue(candidate.id).none { existing ->
                        pending.safeStart < (existing.timelineStartMs + existing.durationMs) &&
                            (pending.safeStart + pending.safeDuration) > existing.timelineStartMs
                    }
            } ?: run {
                val existingCount = workingTracks.count { it.type == preferredOverflowType }
                val newTrackId = "track_${preferredOverflowType.name.lowercase()}_repair_${overflowSeq++}"
                val newTrackName = "${preferredOverflowType.shortPrefix}${existingCount + 1} ${preferredOverflowType.label}"
                val newTrack = TimelineTrack(
                    id = newTrackId,
                    name = newTrackName,
                    type = preferredOverflowType,
                    orderIndex = workingTracks.size,
                    isMuted = pending.sourceTrack.isMuted,
                    isLocked = false,
                    clips = emptyList(),
                )
                workingTracks += newTrack
                clipsByTrackId[newTrackId] = mutableListOf()
                trackMetaById[newTrackId] = newTrack
                newTrack
            }

            val movedClip = pending.clip.copy(
                trackId = candidateTrack.id,
                timelineStartMs = pending.safeStart,
                durationMs = pending.safeDuration,
            )
            val destClips = clipsByTrackId.getValue(candidateTrack.id)
            destClips += movedClip
            destClips.sortWith(compareBy({ it.timelineStartMs }, { it.clipId }))

            val entry = ClipOverlapRepairEntry(
                clipId = pending.clip.clipId,
                assetId = pending.clip.assetId,
                clipKind = pending.clip.clipKind,
                sourceTrackId = pending.sourceTrack.id,
                sourceTrackName = pending.sourceTrack.name,
                overlappingWithClipIds = pending.conflictingIds,
                overlapDurationMs = pending.maxOverlapMs,
                originalStartMs = pending.clip.timelineStartMs,
                originalDurationMs = pending.clip.durationMs,
                proposedTrackId = candidateTrack.id,
                proposedTrackName = candidateTrack.name,
                proposedStartMs = pending.safeStart,
                proposedDurationMs = pending.safeDuration,
                action = OverlapRepairAction.MOVE_TO_OVERFLOW_TRACK,
            )
            allEntries += entry
            trackEntriesById.getOrPut(pending.sourceTrack.id) { mutableListOf() } += entry
        }

        val finalTracks = workingTracks.mapIndexed { index, track ->
            track.copy(
                orderIndex = index,
                clips = clipsByTrackId.getValue(track.id)
                    .sortedWith(compareBy({ it.timelineStartMs }, { it.clipId })),
            )
        }
        val repairedDoc = if (changed) document.copy(tracks = finalTracks) else document
        val trackEntries = trackEntriesById.mapNotNull { (trackId, entries) ->
            val track = trackMetaById[trackId] ?: return@mapNotNull null
            TrackOverlapRepairEntry(
                trackId = track.id,
                trackName = track.name,
                trackType = track.type,
                affectedClips = entries,
            )
        }
        val report = TimelineOverlapRepairReport(
            mode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
            hasOverlaps = allEntries.any {
                it.overlappingWithClipIds.isNotEmpty() || it.originalStartMs < 0L || it.originalDurationMs < 1L
            },
            affectedTracks = trackEntries,
            affectedClips = allEntries,
            timingPreserved = allEntries.none { it.timingChanged },
        )
        return TimelineOverlapRepairOutcome(repairedDoc, report)
    }

    private fun overflowTrackTypeFor(sourceTrackType: TrackType, clipKind: ClipKind): TrackType = when {
        clipKind == ClipKind.TEXT -> TrackType.TEXT
        clipKind == ClipKind.ADJUSTMENT -> TrackType.ADJUSTMENT
        sourceTrackType == TrackType.VIDEO -> TrackType.OVERLAY
        else -> sourceTrackType
    }

    private fun resolveNonOverlappingStart(
        existingClips: List<TimelineClipRef>,
        desiredStartMs: Long,
        durationMs: Long,
    ): Long {
        val safeDesired = desiredStartMs.coerceAtLeast(0L)
        if (existingClips.isEmpty()) return safeDesired
        val sorted = existingClips.sortedBy { it.timelineStartMs }
        var candidate = safeDesired
        for (clip in sorted) {
            val clipEnd = clip.timelineStartMs + clip.durationMs
            val overlaps = candidate < clipEnd && (candidate + durationMs) > clip.timelineStartMs
            if (overlaps) {
                candidate = clipEnd
            }
        }
        return candidate
    }

    private fun resolveNearestValidStart(
        existingClips: List<TimelineClipRef>,
        desiredStartMs: Long,
        durationMs: Long,
    ): Long {
        val sorted = existingClips.sortedBy { it.timelineStartMs }
        if (sorted.isEmpty()) return desiredStartMs.coerceAtLeast(0L)

        val desired = desiredStartMs.coerceAtLeast(0L)
        val overlapsAny = sorted.any { clip ->
            val clipEnd = clip.timelineStartMs + clip.durationMs
            desired < clipEnd && (desired + durationMs) > clip.timelineStartMs
        }
        if (!overlapsAny) return desired

        val candidates = mutableListOf<Long>()
        if (sorted.first().timelineStartMs >= durationMs) {
            candidates += (sorted.first().timelineStartMs - durationMs).coerceAtLeast(0L)
        }
        for (i in 0 until sorted.lastIndex) {
            val gapStart = sorted[i].timelineStartMs + sorted[i].durationMs
            val gapEnd = sorted[i + 1].timelineStartMs
            if (gapEnd - gapStart >= durationMs) {
                candidates += desired.coerceIn(gapStart, gapEnd - durationMs)
            }
        }
        candidates += sorted.maxOf { it.timelineStartMs + it.durationMs }
        return candidates.minByOrNull { abs(it - desired) } ?: desired
    }

    private fun splitKeyframeTracks(
        clip: TimelineClipRef,
        leftDurationMs: Long,
        rightDurationMs: Long,
    ): Pair<List<KeyframeTrack>, List<KeyframeTrack>> {
        if (clip.keyframeTracks.isEmpty()) return emptyList<KeyframeTrack>() to emptyList()
        val leftList = mutableListOf<KeyframeTrack>()
        val rightList = mutableListOf<KeyframeTrack>()
        for (track in clip.keyframeTracks) {
            val splitValue = animationEngine.evaluateProperty(clip, track.property, leftDurationMs)
            val leftKfs = track.keyframes
                .filter { it.timeMs < leftDurationMs }
                .toMutableList()
            val rightKfs = track.keyframes
                .filter { it.timeMs > leftDurationMs }
                .map { it.copy(id = UUID.randomUUID().toString(), timeMs = (it.timeMs - leftDurationMs).coerceIn(0L, rightDurationMs)) }
                .toMutableList()

            if (track.keyframes.any { it.timeMs <= leftDurationMs } && track.keyframes.any { it.timeMs >= leftDurationMs }) {
                leftKfs += PropertyKeyframe(
                    id = UUID.randomUUID().toString(),
                    timeMs = leftDurationMs,
                    value = splitValue,
                    interpolation = KeyframeInterpolation.LINEAR,
                )
                rightKfs.add(
                    0,
                    PropertyKeyframe(
                        id = UUID.randomUUID().toString(),
                        timeMs = 0L,
                        value = splitValue,
                        interpolation = track.keyframes.lastOrNull { it.timeMs <= leftDurationMs }?.interpolation
                            ?: KeyframeInterpolation.EASE_IN_OUT,
                    ),
                )
            }
            if (leftKfs.isNotEmpty()) leftList += track.copy(keyframes = leftKfs.sortedBy { it.timeMs })
            if (rightKfs.isNotEmpty()) rightList += track.copy(keyframes = rightKfs.sortedBy { it.timeMs })
        }
        return leftList to rightList
    }

    private fun shiftAndClampKeyframes(
        tracks: List<KeyframeTrack>,
        offsetMs: Long,
        newDurationMs: Long,
    ): List<KeyframeTrack> {
        if (tracks.isEmpty()) return emptyList()
        return tracks.mapNotNull { track ->
            val shifted = track.keyframes.mapNotNull { kf ->
                val t = kf.timeMs + offsetMs
                if (t < -50L || t > newDurationMs + 50L) {
                    null
                } else {
                    kf.copy(timeMs = t.coerceIn(0L, newDurationMs))
                }
            }.sortedBy { it.timeMs }
            if (shifted.isEmpty()) null else track.copy(keyframes = shifted)
        }
    }
}
