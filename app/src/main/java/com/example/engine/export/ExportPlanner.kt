package com.example.engine.export

import android.media.MediaCodecList
import android.media.MediaFormat
import com.example.engine.project.ClipKind
import com.example.engine.project.ExportSettingsSpec
import com.example.engine.project.FilterPreset
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import com.example.engine.timeline.TimelineEngine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Inspects a [ProjectDocument] and device encoder capabilities to build a deterministic
 * export execution plan routing between Path A (Media3 Transformer) and Path B
 * (Custom OpenGL ES / MediaCodec / MediaMuxer / PCM Audio Mixer).
 */
class ExportPlanner(
    private val timelineEngine: TimelineEngine = TimelineEngine(),
) {

    fun probeHardwareCapabilities(): HardwareExportCapabilities {
        return runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var avcEncoder: String? = null
            var hevcEncoder: String? = null
            var maxShortSide = 1080

            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                        if (avcEncoder == null) avcEncoder = info.name
                        val caps = info.getCapabilitiesForType(type)?.videoCapabilities
                        if (caps != null) {
                            val supportedH = caps.supportedHeights.upper
                            val supportedW = caps.supportedWidths.upper
                            maxShortSide = max(maxShortSide, min(supportedW, supportedH))
                        }
                    } else if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                        if (hevcEncoder == null) hevcEncoder = info.name
                        val caps = info.getCapabilitiesForType(type)?.videoCapabilities
                        if (caps != null) {
                            val supportedH = caps.supportedHeights.upper
                            val supportedW = caps.supportedWidths.upper
                            maxShortSide = max(maxShortSide, min(supportedW, supportedH))
                        }
                    }
                }
            }

            HardwareExportCapabilities(
                supportsAvc = avcEncoder != null,
                supportsHevc = hevcEncoder != null,
                maxSupportedShortSidePx = maxShortSide.coerceIn(720, 2160),
                avcEncoderName = avcEncoder,
                hevcEncoderName = hevcEncoder,
            )
        }.getOrDefault(HardwareExportCapabilities())
    }

    fun buildPlan(
        document: ProjectDocument,
        assetsById: Map<String, MediaAsset>,
        settings: ExportSettingsSpec,
        preferredPath: ExportRenderPath = ExportRenderPath.AUTO,
        capabilities: HardwareExportCapabilities = HardwareExportCapabilities(supportsHevc = true, maxSupportedShortSidePx = 2160),
    ): ExportPlan {
        val totalDurationMs = timelineEngine.calculateTimelineDurationMs(document)
        val resPreset = ExportResolutionPreset.fromHeight(settings.resolutionHeight)
        val safeShortSide = resPreset.shortSidePx.coerceAtMost(capabilities.maxSupportedShortSidePx)

        val (outW, outH) = computeOutputDimensions(
            canvasWidth = document.canvasWidth,
            canvasHeight = document.canvasHeight,
            targetShortSidePx = safeShortSide,
        )

        val requestedCodec = ExportVideoCodec.fromLabel(settings.codec)
        val effectiveCodec = if (requestedCodec == ExportVideoCodec.H265 && !capabilities.supportsHevc) {
            ExportVideoCodec.H264
        } else {
            requestedCodec
        }

        val fps = settings.fps.coerceIn(24, 60)
        val bitrateScale = (safeShortSide.toFloat() / 1080f).coerceIn(0.45f, 3.2f)
        val fpsScale = if (fps >= 50) 1.35f else 1.0f
        val codecFactor = if (effectiveCodec == ExportVideoCodec.H265) 0.72f else 1.0f
        val videoBitrateBps = (resPreset.videoBitrateBps * codecFactor * fpsScale)
            .roundToInt()
            .coerceIn(2_500_000, 42_000_000)
        val audioBitrateKbps = ExportAudioBitrateOption.fromKbps(settings.audioBitrateKbps).kbps
        val audioBitrateBps = audioBitrateKbps * 1_000

        val allClips = document.tracks.filterNot { it.isMuted }.flatMap { it.clips }
        val hasAudio = document.tracks
            .filter { !it.isMuted && (it.type == TrackType.AUDIO || it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY) }
            .flatMap { it.clips }
            .any { clip ->
                clip.clipKind == ClipKind.MEDIA &&
                    !clip.isMuted &&
                    assetsById[clip.assetId]?.let { it.type != MediaType.IMAGE && it.status == MediaStatus.READY } == true
            }

        val estimatedSizeMb = if (totalDurationMs <= 0L) {
            0f
        } else {
            val totalBps = videoBitrateBps.toDouble() + (if (hasAudio) audioBitrateBps.toDouble() else 0.0)
            ((totalBps / 8.0) * (totalDurationMs / 1000.0) / (1024.0 * 1024.0)).toFloat()
        }

        if (totalDurationMs <= 0L || allClips.isEmpty()) {
            return ExportPlan(
                selectedPath = ExportRenderPath.CUSTOM_GL_CODEC,
                reason = "Timeline has no active clips to export.",
                compatibilityNotes = listOf("Empty timeline"),
                outputWidth = outW,
                outputHeight = outH,
                fps = fps,
                codec = effectiveCodec,
                videoBitrateBps = videoBitrateBps,
                audioBitrateBps = audioBitrateBps,
                hasAudio = false,
                totalDurationMs = 0L,
                estimatedFileSizeMb = 0f,
                isExportable = false,
                blockingError = "Add at least one clip to an unmuted track before exporting.",
            )
        }

        val missingAssets = allClips.filter { it.clipKind == ClipKind.MEDIA }
            .mapNotNull { assetsById[it.assetId] }
            .filter { it.status != MediaStatus.READY }
        if (missingAssets.isNotEmpty()) {
            return ExportPlan(
                selectedPath = ExportRenderPath.CUSTOM_GL_CODEC,
                reason = "Timeline references missing or unsupported media.",
                compatibilityNotes = missingAssets.map { "Missing/unsupported: ${it.displayName}" },
                outputWidth = outW,
                outputHeight = outH,
                fps = fps,
                codec = effectiveCodec,
                videoBitrateBps = videoBitrateBps,
                audioBitrateBps = audioBitrateBps,
                hasAudio = hasAudio,
                totalDurationMs = totalDurationMs,
                estimatedFileSizeMb = estimatedSizeMb,
                isExportable = false,
                blockingError = "Remove or replace missing media asset (${missingAssets.first().displayName}) before exporting.",
            )
        }

        val pathBReasons = collectCustomPathRequirements(document, assetsById)
        val pathACompatible = pathBReasons.isEmpty()

        val (chosenPath, summaryReason) = when (preferredPath) {
            ExportRenderPath.CUSTOM_GL_CODEC -> {
                ExportRenderPath.CUSTOM_GL_CODEC to (
                    if (pathBReasons.isEmpty()) {
                        "Custom GL + Codec pipeline manually selected for frame-accurate compositing"
                    } else {
                        "Custom GL + Codec required: ${pathBReasons.joinToString(", ")}"
                    }
                    )
            }
            ExportRenderPath.MEDIA3_TRANSFORMER -> {
                if (pathACompatible) {
                    ExportRenderPath.MEDIA3_TRANSFORMER to "Single-sequence timeline eligible for Media3 Transformer hardware pipeline"
                } else {
                    // Never silently omit edits (spec 3.1)
                    ExportRenderPath.CUSTOM_GL_CODEC to "Routed to Path B (Custom GL) to preserve: ${pathBReasons.joinToString(", ")}"
                }
            }
            ExportRenderPath.AUTO -> {
                if (pathACompatible) {
                    ExportRenderPath.MEDIA3_TRANSFORMER to "Path A (Media3 Transformer): fast hardware transcode for sequential clip timeline"
                } else {
                    ExportRenderPath.CUSTOM_GL_CODEC to "Path B (Custom GL + Codec): ${pathBReasons.joinToString(", ")}"
                }
            }
        }

        val notes = buildList {
            if (requestedCodec == ExportVideoCodec.H265 && effectiveCodec == ExportVideoCodec.H264) {
                add("Device lacks H.265 encoder; automatically using H.264")
            }
            if (safeShortSide < resPreset.shortSidePx) {
                add("Clamped to ${safeShortSide}p due to device hardware encoder limit")
            }
            addAll(pathBReasons)
        }

        return ExportPlan(
            selectedPath = chosenPath,
            reason = summaryReason,
            compatibilityNotes = notes,
            outputWidth = outW,
            outputHeight = outH,
            fps = fps,
            codec = effectiveCodec,
            videoBitrateBps = (videoBitrateBps * (bitrateScale / (safeShortSide / 1080f).coerceAtLeast(0.45f))).roundToInt(),
            audioBitrateBps = audioBitrateBps,
            hasAudio = hasAudio,
            totalDurationMs = totalDurationMs,
            estimatedFileSizeMb = estimatedSizeMb,
            isExportable = true,
        )
    }

    /**
     * Returns explicit reasons why Path B (Custom GL + MediaCodec + PCM Mixer) is required,
     * or empty list if Media3 Transformer Path A can reproduce the timeline faithfully.
     */
    fun collectCustomPathRequirements(
        document: ProjectDocument,
        assetsById: Map<String, MediaAsset>,
    ): List<String> {
        val reasons = linkedSetOf<String>()
        val activeTracks = document.tracks.filterNot { it.isMuted }

        val activeVideoTracks = activeTracks.filter {
            (it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY) && it.clips.isNotEmpty()
        }
        val activeAudioTracks = activeTracks.filter {
            it.type == TrackType.AUDIO && it.clips.isNotEmpty()
        }

        if (activeVideoTracks.size > 1) {
            reasons += "Multi-track video/overlay compositing"
        }
        if (activeTracks.any { it.type == TrackType.TEXT && it.clips.isNotEmpty() }) {
            reasons += "Text & kinetic typography layers"
        }
        val activeTextSpecs = activeTracks
            .filter { it.type == TrackType.TEXT }
            .flatMap { it.clips }
            .mapNotNull { it.textSpec }
        if (activeTextSpecs.any { it.renderMode == TextRenderMode.INVERT_VIDEO }) {
            reasons += "Invert Lyric GPU mask effect"
        }
        if (activeTextSpecs.any { it.timedWords.isNotEmpty() && it.karaokeMode != KaraokeHighlightMode.NONE }) {
            reasons += "Word-level karaoke highlighting"
        }
        if (activeTracks.any { it.type == TrackType.ADJUSTMENT && it.clips.isNotEmpty() }) {
            reasons += "Adjustment layers"
        }
        if (activeAudioTracks.size > 1 || (activeAudioTracks.isNotEmpty() && activeVideoTracks.any { track ->
                track.clips.any { clip ->
                    !clip.isMuted && assetsById[clip.assetId]?.type == MediaType.VIDEO
                }
            })
        ) {
            reasons += "Multi-track PCM audio mixing"
        }

        val primaryVideo = activeVideoTracks.firstOrNull()
        if (primaryVideo != null) {
            val sorted = primaryVideo.clips.sortedBy { it.timelineStartMs }
            if (sorted.isNotEmpty() && sorted.first().timelineStartMs > 0L) {
                reasons += "Leading timeline gap"
            }
            for (i in 0 until sorted.lastIndex) {
                val gap = sorted[i + 1].timelineStartMs - (sorted[i].timelineStartMs + sorted[i].durationMs)
                if (gap > 0L) {
                    reasons += "Timeline gap frames"
                    break
                }
            }
        }
        val primaryAudio = activeAudioTracks.firstOrNull()
        if (primaryAudio != null) {
            val sorted = primaryAudio.clips.sortedBy { it.timelineStartMs }
            if (sorted.isNotEmpty() && sorted.first().timelineStartMs > 0L) {
                reasons += "Audio track offset/gap"
            }
            for (i in 0 until sorted.lastIndex) {
                val gap = sorted[i + 1].timelineStartMs - (sorted[i].timelineStartMs + sorted[i].durationMs)
                if (gap > 0L) {
                    reasons += "Audio track offset/gap"
                    break
                }
            }
        }

        for (track in activeTracks) {
            for (clip in track.clips) {
                if (clip.transitionIn != null && clip.transitionIn.type != TransitionType.NONE) {
                    reasons += "Transitions (${clip.transitionIn.type.label})"
                }
                if (clip.blendMode.shaderId != 0) {
                    reasons += "Blend mode (${clip.blendMode.label})"
                }
                if (clip.mask.isActive) {
                    reasons += "Layer mask (${clip.mask.shape.label})"
                }
                if (clip.shaderEffect.isActive) {
                    reasons += "GLSL shader (${clip.shaderEffect.type.label})"
                }
                if (!clip.transform.isDefault) {
                    reasons += "Custom layer transform"
                }
                if (clip.keyframeTracks.isNotEmpty()) {
                    reasons += "Keyframe animation curves"
                }
                if (!clip.adjustments.isDefault) {
                    val adj = clip.adjustments
                    if (adj.filterPreset != FilterPreset.NONE ||
                        abs(adj.temperature) > 0.01f ||
                        abs(adj.tint) > 0.01f ||
                        adj.vignette > 0.01f ||
                        adj.blurRadius > 0.05f ||
                        adj.sharpen > 0.01f ||
                        abs(adj.contrast - 1f) > 0.01f ||
                        abs(adj.saturation - 1f) > 0.01f ||
                        abs(adj.brightness) > 0.01f ||
                        abs(adj.exposure) > 0.01f
                    ) {
                        reasons += "Color grading & filters"
                    }
                }
                if (clip.isReversed) {
                    reasons += "Reverse playback"
                }
                if (clip.speedRampPreset != com.example.engine.project.SpeedRampPreset.NONE || clip.speedRampPoints.isNotEmpty()) {
                    reasons += "Speed ramping curve"
                }
                if (clip.chromaKey.enabled) {
                    reasons += "Chroma key compositing"
                }
                if (clip.lutSpec.isActive) {
                    reasons += "3D LUT grading"
                }
                if (clip.stabilization.enabled) {
                    reasons += "Video stabilization"
                }
                if (!clip.audioEffect.isDefault || clip.fadeInMs > 0L || clip.fadeOutMs > 0L) {
                    reasons += "Audio fades & FX/EQ/Pan"
                }
                if (clip.clipKind == ClipKind.MEDIA &&
                    !clip.isMuted &&
                    clip.volume > 0.001f &&
                    abs(clip.volume - 1.0f) > 0.01f &&
                    assetsById[clip.assetId]?.type != MediaType.IMAGE
                ) {
                    reasons += "Clip audio volume scaling"
                }
                if (track.type == TrackType.AUDIO && (clip.isMuted || clip.volume <= 0.001f)) {
                    reasons += "Muted audio clip timing"
                }
            }
        }
        if (timelineEngine.hasOverlappingClips(document)) {
            reasons += "Overlapping clip interval resolution"
        }
        return reasons.toList()
    }

    fun computeOutputDimensions(
        canvasWidth: Int,
        canvasHeight: Int,
        targetShortSidePx: Int,
    ): Pair<Int, Int> {
        val safeW = canvasWidth.coerceAtLeast(1)
        val safeH = canvasHeight.coerceAtLeast(1)
        val currentShort = min(safeW, safeH).toDouble()
        val scale = targetShortSidePx.toDouble() / currentShort
        val scaledW = ((safeW * scale) / 2.0).roundToInt() * 2
        val scaledH = ((safeH * scale) / 2.0).roundToInt() * 2
        return scaledW.coerceAtLeast(256) to scaledH.coerceAtLeast(256)
    }
}
