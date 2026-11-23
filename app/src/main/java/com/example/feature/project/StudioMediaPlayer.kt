package com.example.feature.project

import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.StudioBadge
import com.example.core.graphics.GlCompositorRenderer
import com.example.engine.animation.AnimationEngine
import com.example.engine.compositor.CompositorEngine
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.BlendModePreset
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.LayerTransform
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TextAlignMode
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TextStylePreset
import com.example.engine.text.UnifiedTextEngine
import com.example.engine.text.WordPlaybackState
import com.example.engine.timeline.ActiveClipLayer
import com.example.engine.timeline.ActiveTimelineFrame
import com.example.engine.timeline.SpeedRampEngine
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.SpaceGroteskFontFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(UnstableApi::class)
@Composable
fun TimelinePreviewMonitor(
    project: ProjectDocument,
    activeFrame: ActiveTimelineFrame,
    assetsById: Map<String, MediaAsset>,
    playheadMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    preferProxyPlayback: Boolean = true,
    stageHeight: Dp = 188.dp,
    selectedClipId: String? = null,
    onSelectClip: (String) -> Unit = {},
    onBeginTransformGesture: () -> Unit = {},
    onPreviewClipTransform: (String, LayerTransform) -> Unit = { _, _ -> },
    onCommitTransformGesture: (String) -> Unit = {},
    onTogglePlayPause: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onJumpStart: () -> Unit,
    onJumpEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val animationEngine = remember { AnimationEngine() }
    val compositorEngine = remember(animationEngine) { CompositorEngine(animationEngine) }
    val canvasRatio = (project.canvasWidth.toFloat() / project.canvasHeight.toFloat())
        .coerceIn(0.45f, 2.4f)

    val resolvedComposition = remember(activeFrame, assetsById, project.canvasWidth, project.canvasHeight) {
        compositorEngine.resolveFrame(
            activeFrame = activeFrame,
            assetsById = assetsById,
            canvasWidth = project.canvasWidth,
            canvasHeight = project.canvasHeight,
        )
    }

    val activeVideoLayers = remember(resolvedComposition) {
        resolvedComposition.layers.filter {
            it.asset?.type == MediaType.VIDEO && it.asset.status == MediaStatus.READY
        }
    }
    val primaryVideoLayer = activeVideoLayers.getOrNull(0)?.sourceLayer
    val primaryVideoAsset = activeVideoLayers.getOrNull(0)?.asset
    val secondaryResolvedVideo = activeVideoLayers.getOrNull(1)
        ?: resolvedComposition.outgoingLayer?.takeIf {
            it.asset?.type == MediaType.VIDEO && it.asset.status == MediaStatus.READY
        }
    val secondaryVideoLayer = secondaryResolvedVideo?.sourceLayer
    val secondaryVideoAsset = secondaryResolvedVideo?.asset

    val activeAudioSlots = remember(activeFrame.audioLayers, assetsById) {
        activeFrame.audioLayers
            .filter { layer ->
                val a = assetsById[layer.clip.assetId]
                a != null && a.status == MediaStatus.READY
            }
            .take(4)
    }
    val primaryAudio = activeAudioSlots.firstOrNull()
    val audioAsset = primaryAudio?.let { assetsById[it.clip.assetId] }

    val primaryVideoPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    val secondaryVideoPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    val audioPlayers = remember(context) { List(4) { ExoPlayer.Builder(context).build() } }

    var loadedPrimaryVideoUri by remember { mutableStateOf<String?>(null) }
    var loadedSecondaryVideoUri by remember { mutableStateOf<String?>(null) }
    val loadedAudioUris = remember { Array<String?>(4) { null } }

    var reportedPrimaryRatio by remember { mutableFloatStateOf(16f / 9f) }
    var reportedSecondaryRatio by remember { mutableFloatStateOf(16f / 9f) }

    var attachedPrimarySurface by remember { mutableStateOf<Surface?>(null) }
    var attachedSecondarySurface by remember { mutableStateOf<Surface?>(null) }

    val perfMonitor = remember(context) {
        (context.applicationContext as? com.example.CutLabApplication)?.container?.performanceMonitor
    }

    val glRenderer = remember(context, perfMonitor) {
        GlCompositorRenderer(
            context = context,
            onFrameRendered = { frameMs ->
                perfMonitor?.recordPreviewFrameLatency(frameMs)
            },
            onVideoSurfacesReady = { s0, s1 ->
                mainHandler.post {
                    attachedPrimarySurface = s0
                    attachedSecondarySurface = s1
                    primaryVideoPlayer.setVideoSurface(s0)
                    secondaryVideoPlayer.setVideoSurface(s1)
                }
            },
        )
    }

    val perfSnapshot = perfMonitor?.snapshot?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(glRenderer, perfSnapshot?.effectivePreviewScale) {
        val scale = perfSnapshot?.effectivePreviewScale ?: 1.0f
        glRenderer.setPreviewResolutionScale(scale)
    }

    DisposableEffect(perfMonitor, glRenderer) {
        val unregister = perfMonitor?.registerTrimListener { level ->
            glRenderer.trimMemory(level)
        }
        onDispose {
            unregister?.invoke()
        }
    }

    DisposableEffect(glRenderer, primaryVideoPlayer, secondaryVideoPlayer, audioPlayers) {
        val primaryListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    reportedPrimaryRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) /
                        videoSize.height.toFloat()
                    glRenderer.requestRender()
                }
            }
        }
        val secondaryListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    reportedSecondaryRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) /
                        videoSize.height.toFloat()
                    glRenderer.requestRender()
                }
            }
        }
        primaryVideoPlayer.addListener(primaryListener)
        secondaryVideoPlayer.addListener(secondaryListener)
        onDispose {
            primaryVideoPlayer.removeListener(primaryListener)
            secondaryVideoPlayer.removeListener(secondaryListener)
            primaryVideoPlayer.setVideoSurface(null)
            secondaryVideoPlayer.setVideoSurface(null)
            glRenderer.release()
            primaryVideoPlayer.release()
            secondaryVideoPlayer.release()
            audioPlayers.forEach { it.release() }
        }
    }

    // Push resolved multi-layer composition state to GLSL compositor on every change
    LaunchedEffect(
        resolvedComposition,
        primaryVideoLayer?.clip?.clipId,
        secondaryVideoLayer?.clip?.clipId,
        reportedPrimaryRatio,
        reportedSecondaryRatio,
        attachedPrimarySurface,
        attachedSecondarySurface,
    ) {
        glRenderer.updateComposition(
            frame = resolvedComposition,
            primaryClipId = primaryVideoLayer?.clip?.clipId,
            secondaryClipId = secondaryVideoLayer?.clip?.clipId,
            primaryVideoRatio = reportedPrimaryRatio,
            secondaryVideoRatio = reportedSecondaryRatio,
        )
    }

    // Sync Primary Video Slot
    LaunchedEffect(
        primaryVideoAsset?.previewUri(preferProxyPlayback),
        primaryVideoLayer?.clip?.clipId,
        primaryVideoLayer?.clip?.speed,
        primaryVideoLayer?.clip?.speedRampPoints,
        primaryVideoLayer?.clip?.isReversed,
        primaryVideoLayer?.effectiveVolume,
        isPlaying,
        playheadMs,
        preferProxyPlayback,
        attachedPrimarySurface,
    ) {
        val result = syncExoPlayerToLayer(
            player = primaryVideoPlayer,
            layer = primaryVideoLayer,
            asset = primaryVideoAsset,
            loadedUri = loadedPrimaryVideoUri,
            isPlaying = isPlaying,
            preferProxyPlayback = preferProxyPlayback,
        )
        loadedPrimaryVideoUri = result.first
        if (result.second > 0f) reportedPrimaryRatio = result.second
    }

    // Sync Secondary Overlay Video Slot
    LaunchedEffect(
        secondaryVideoAsset?.previewUri(preferProxyPlayback),
        secondaryVideoLayer?.clip?.clipId,
        secondaryVideoLayer?.clip?.speed,
        secondaryVideoLayer?.clip?.speedRampPoints,
        secondaryVideoLayer?.clip?.isReversed,
        secondaryVideoLayer?.effectiveVolume,
        isPlaying,
        playheadMs,
        preferProxyPlayback,
        attachedSecondarySurface,
    ) {
        val result = syncExoPlayerToLayer(
            player = secondaryVideoPlayer,
            layer = secondaryVideoLayer,
            asset = secondaryVideoAsset,
            loadedUri = loadedSecondaryVideoUri,
            isPlaying = isPlaying,
            preferProxyPlayback = preferProxyPlayback,
        )
        loadedSecondaryVideoUri = result.first
        if (result.second > 0f) reportedSecondaryRatio = result.second
    }

    // Sync Multi-Track Audio Pool (up to 4 simultaneous active audio layers on timeline clock)
    LaunchedEffect(activeAudioSlots, isPlaying, playheadMs, preferProxyPlayback) {
        for (slotIdx in audioPlayers.indices) {
            val player = audioPlayers[slotIdx]
            val layer = activeAudioSlots.getOrNull(slotIdx)
            val slotAsset = layer?.let { assetsById[it.clip.assetId] }
            if (layer != null && slotAsset != null && slotAsset.status == MediaStatus.READY) {
                val chosenUri = slotAsset.previewUri(preferProxyPlayback)
                if (loadedAudioUris[slotIdx] != chosenUri) {
                    loadedAudioUris[slotIdx] = chosenUri
                    player.setMediaItem(MediaItem.fromUri(chosenUri))
                    player.prepare()
                }
                val speed = SpeedRampEngine.instantaneousSpeedAt(layer.clip, layer.clipRelativeMs)
                if (abs(player.playbackParameters.speed - speed) > 0.01f) {
                    player.playbackParameters = PlaybackParameters(speed)
                }
                val targetSourceMs = SpeedRampEngine.clipRelativeToSourceMs(
                    layer.clip,
                    layer.clipRelativeMs,
                    slotAsset.durationMs,
                )
                player.volume = if (layer.clip.isReversed) 0f else layer.effectiveVolume.coerceIn(0f, 1f)
                if (isPlaying && !layer.clip.isReversed) {
                    if (abs(player.currentPosition - targetSourceMs) > 260L) {
                        player.seekTo(targetSourceMs)
                    }
                    if (!player.isPlaying) {
                        player.playWhenReady = true
                    }
                } else {
                    if (player.isPlaying || player.playWhenReady) {
                        player.playWhenReady = false
                    }
                }
            } else {
                if (player.playWhenReady || player.isPlaying) {
                    player.playWhenReady = false
                }
                if (loadedAudioUris[slotIdx] != null && slotAsset == null) {
                    loadedAudioUris[slotIdx] = null
                    player.clearMediaItems()
                }
            }
        }
    }

    val anyVisualAssetMissing = resolvedComposition.layers.any {
        it.asset != null && it.asset.status != MediaStatus.READY
    }
    val hasInvertLyric = activeFrame.textLayers.any {
        it.clip.textSpec?.renderMode == TextRenderMode.INVERT_VIDEO
    }
    val hasVisualContent = resolvedComposition.layers.isNotEmpty() || hasInvertLyric
    var textTransformMode by remember { mutableStateOf(false) }
    val activeSelectedTextLayer = activeFrame.textLayers.firstOrNull {
        it.clip.clipId == selectedClipId
    }
    LaunchedEffect(activeFrame.textLayers.size) {
        if (activeFrame.textLayers.isEmpty()) {
            textTransformMode = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, studio.hairlineBorder, MaterialTheme.shapes.medium),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stageHeight)
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(canvasRatio)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black)
                    .border(1.dp, studio.hairlineBorder, RoundedCornerShape(6.dp))
                    .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                val stageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val stageHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

                // OpenGL ES 2.0 + GLSL Multi-Layer Compositor Surface (always mounted cleanly when visuals exist)
                if (hasVisualContent && !anyVisualAssetMissing) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                isOpaque = false
                                surfaceTextureListener = glRenderer
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (!hasVisualContent && audioAsset == null && activeFrame.textLayers.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = "${project.canvasWidth}×${project.canvasHeight}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!hasVisualContent && audioAsset != null && activeFrame.textLayers.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AudioFile,
                            contentDescription = "Audio layer active",
                            tint = studio.audioAmber,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            text = audioAsset.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else if (anyVisualAssetMissing) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Missing media",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                }

                // Active Text & Kinetic Typography Layers (NORMAL rendered via Compose; INVERT_VIDEO composited in GL with interactive selection outline overlay)
                activeFrame.textLayers.forEach { textLayer ->
                    val animatedSpec = animationEngine.evaluateTextSpec(textLayer.clip, textLayer.clipRelativeMs)
                    val animatedTransform = animationEngine.evaluateTransform(textLayer.clip, textLayer.clipRelativeMs)
                    if (animatedSpec != null) {
                        val isSelected = textLayer.clip.clipId == selectedClipId
                        RenderedTextOverlay(
                            spec = animatedSpec,
                            transform = animatedTransform,
                            blendMode = textLayer.clip.blendMode,
                            clipRelativeMs = textLayer.clipRelativeMs,
                            stageWidthPx = stageWidthPx,
                            stageHeightPx = stageHeightPx,
                            isInvertMaskLayer = animatedSpec.renderMode == TextRenderMode.INVERT_VIDEO,
                            isSelected = isSelected,
                            showTransformOutline = textTransformMode && isSelected,
                            onTapSelect = { onSelectClip(textLayer.clip.clipId) },
                        )
                    }
                }

                // Stage-wide Transform Gesture Overlay (only active when explicit Transform Mode is toggled on)
                if (textTransformMode && activeSelectedTextLayer != null) {
                    val selectedBaseTransform = UnifiedTextEngine.effectiveTransform(
                        activeSelectedTextLayer.clip.textSpec,
                        activeSelectedTextLayer.clip.transform,
                    )
                    PreviewTextTransformGestureOverlay(
                        clipId = activeSelectedTextLayer.clip.clipId,
                        baseTransform = selectedBaseTransform,
                        stageWidthPx = stageWidthPx,
                        stageHeightPx = stageHeightPx,
                        onBeginGesture = onBeginTransformGesture,
                        onPreviewTransform = onPreviewClipTransform,
                        onCommitGesture = onCommitTransformGesture,
                    )
                }
            }

            // Explicit Text Transform Mode Toggle + Quick Handles (prevents accidental drags on underlying video)
            if (activeFrame.textLayers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = textTransformMode,
                        onClick = {
                            if (!textTransformMode && activeSelectedTextLayer == null) {
                                activeFrame.textLayers.lastOrNull()?.clip?.clipId?.let(onSelectClip)
                            }
                            textTransformMode = !textTransformMode
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (textTransformMode) Icons.Default.Check else Icons.Default.OpenWith,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        label = {
                            Text(
                                text = if (textTransformMode) "Done" else "Transform",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        modifier = Modifier.testTag("preview_text_transform_toggle"),
                    )
                }
            }

            // Active Layer + Adjustment + Audio HUD Badges
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val topVisual = resolvedComposition.layers.lastOrNull()
                if (topVisual != null && topVisual.asset != null) {
                    val layerCountTag = if (resolvedComposition.layers.size > 1) {
                        "${resolvedComposition.layers.size}L • "
                    } else ""
                    val instantSpeed = SpeedRampEngine.instantaneousSpeedAt(
                        topVisual.sourceLayer.clip,
                        topVisual.sourceLayer.clipRelativeMs,
                    )
                    val speedSuffix = if (abs(instantSpeed - 1f) > 0.02f || topVisual.sourceLayer.clip.speedRampPoints.isNotEmpty()) {
                        " • ${String.format("%.2gx", instantSpeed)}"
                    } else ""
                    val revSuffix = if (topVisual.sourceLayer.clip.isReversed) " • REV" else ""
                    StudioBadge(
                        text = "$layerCountTag${topVisual.sourceLayer.track.name}: ${topVisual.asset.displayName}$speedSuffix$revSuffix",
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                    if (preferProxyPlayback && topVisual.asset.hasReadyProxy) {
                        StudioBadge(
                            text = "PROXY",
                            containerColor = studio.quickCyan.copy(alpha = 0.22f),
                            contentColor = studio.quickCyan,
                        )
                    }
                }
                if (resolvedComposition.hasActiveAdjustmentLayer) {
                    StudioBadge(
                        text = "ADJ",
                        containerColor = studio.readyEmeraldContainer.copy(alpha = 0.9f),
                        contentColor = studio.readyEmerald,
                    )
                }
                if (audioAsset != null) {
                    StudioBadge(
                        text = "♪ ${audioAsset.displayName}",
                        containerColor = studio.audioAmberContainer.copy(alpha = 0.9f),
                        contentColor = studio.audioAmber,
                    )
                }
                if (perfSnapshot != null && perfSnapshot.effectivePreviewScale < 0.99f) {
                    StudioBadge(
                        text = "${(perfSnapshot.effectivePreviewScale * 100).toInt()}% GL",
                        containerColor = studio.audioAmber.copy(alpha = 0.22f),
                        contentColor = studio.audioAmber,
                    )
                }
            }
        }

        // Studio Transport Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${TimeFormatting.formatTimecode(playheadMs, project.fps)} / ${
                    TimeFormatting.formatTimecode(totalDurationMs, project.fps)
                }",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("transport_timecode"),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = onJumpStart,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("transport_jump_start"),
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Jump to start",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onStepFrame(-1) },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("transport_step_back"),
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Step back 1 frame",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                M3Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("transport_play_pause"),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                IconButton(
                    onClick = { onStepFrame(1) },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("transport_step_forward"),
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Step forward 1 frame",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onJumpEnd,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("transport_jump_end"),
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Jump to end",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

private fun syncExoPlayerToLayer(
    player: ExoPlayer,
    layer: ActiveClipLayer?,
    asset: MediaAsset?,
    loadedUri: String?,
    isPlaying: Boolean,
    preferProxyPlayback: Boolean,
): Pair<String?, Float> {
    var nextUri = loadedUri
    var newRatio = -1f
    if (layer != null && asset != null &&
        asset.type == MediaType.VIDEO &&
        asset.status == MediaStatus.READY
    ) {
        val chosenUri = asset.previewUri(preferProxyPlayback)
        if (nextUri != chosenUri) {
            nextUri = chosenUri
            if (asset.width > 0 && asset.height > 0) {
                newRatio = asset.width.toFloat() / asset.height.toFloat()
            }
            player.setMediaItem(MediaItem.fromUri(chosenUri))
            player.prepare()
        }
        val speed = SpeedRampEngine.instantaneousSpeedAt(layer.clip, layer.clipRelativeMs)
        if (abs(player.playbackParameters.speed - speed) > 0.01f) {
            player.playbackParameters = PlaybackParameters(speed)
        }
        val targetSourceMs = SpeedRampEngine.clipRelativeToSourceMs(
            clip = layer.clip,
            clipRelativeMs = layer.clipRelativeMs,
            assetDurationMs = asset.durationMs,
        )
        player.volume = if (layer.clip.isReversed) 0f else layer.effectiveVolume.coerceIn(0f, 1f)
        if (isPlaying && !layer.clip.isReversed) {
            if (abs(player.currentPosition - targetSourceMs) > 260L) {
                player.seekTo(targetSourceMs)
            }
            if (!player.isPlaying) {
                player.playWhenReady = true
            }
        } else {
            if (player.isPlaying || player.playWhenReady) {
                player.playWhenReady = false
            }
            if (abs(player.currentPosition - targetSourceMs) > 35L) {
                player.seekTo(targetSourceMs)
            }
        }
    } else {
        if (player.playWhenReady || player.isPlaying) {
            player.playWhenReady = false
        }
        if (nextUri != null && asset == null) {
            nextUri = null
            player.clearMediaItems()
        }
    }
    return nextUri to newRatio
}

@Composable
private fun PreviewTextTransformGestureOverlay(
    clipId: String,
    baseTransform: LayerTransform,
    stageWidthPx: Float,
    stageHeightPx: Float,
    onBeginGesture: () -> Unit,
    onPreviewTransform: (String, LayerTransform) -> Unit,
    onCommitGesture: (String) -> Unit,
) {
    val currentBase by rememberUpdatedState(baseTransform)
    val currentClipId by rememberUpdatedState(clipId)
    val currentW by rememberUpdatedState(stageWidthPx.coerceAtLeast(1f))
    val currentH by rememberUpdatedState(stageHeightPx.coerceAtLeast(1f))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(clipId) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var working = currentBase
                    var gestureBegun = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val anyPressed = event.changes.any { it.pressed }
                            if (!anyPressed) break

                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            val rot = event.calculateRotation()
                            val hasChange = pan != Offset.Zero ||
                                abs(zoom - 1f) > 0.002f ||
                                abs(rot) > 0.1f

                            if (hasChange) {
                                if (!gestureBegun) {
                                    gestureBegun = true
                                    onBeginGesture()
                                    working = currentBase
                                }
                                val nextX = (working.positionX + pan.x / currentW).coerceIn(-1.5f, 1.5f)
                                val nextY = (working.positionY + pan.y / currentH).coerceIn(-1.5f, 1.5f)
                                val nextScaleX = (working.scaleX * zoom).coerceIn(0.1f, 4.0f)
                                val nextScaleY = if (working.uniformScale) {
                                    nextScaleX
                                } else {
                                    (working.scaleY * zoom).coerceIn(0.1f, 4.0f)
                                }
                                val nextRot = (working.rotationDeg + rot).coerceIn(-360f, 360f)
                                working = working.copy(
                                    positionX = nextX,
                                    positionY = nextY,
                                    scaleX = nextX.let { nextScaleX },
                                    scaleY = nextScaleY,
                                    rotationDeg = nextRot,
                                )
                                onPreviewTransform(currentClipId, working)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } finally {
                        if (gestureBegun) {
                            onCommitGesture("Preview Text Transform")
                        }
                    }
                }
            },
    ) {
        // Explicit corner Scale & top Rotate handles for accessible single-finger manipulation
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f))
                .pointerInput(clipId, "scale_handle") {
                    var working = currentBase
                    detectDragGestures(
                        onDragStart = {
                            onBeginGesture()
                            working = currentBase
                        },
                        onDragEnd = { onCommitGesture("Preview Scale Text") },
                        onDragCancel = { onCommitGesture("Preview Scale Text") },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val factor = 1f + (dragAmount.x + dragAmount.y) / 140f
                            val nextSx = (working.scaleX * factor).coerceIn(0.2f, 3.0f)
                            val nextSy = if (working.uniformScale) nextSx else (working.scaleY * factor).coerceIn(0.2f, 3.0f)
                            working = working.copy(scaleX = nextSx, scaleY = nextSy)
                            onPreviewTransform(currentClipId, working)
                        },
                    )
                }
                .testTag("preview_scale_handle"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.OpenWith,
                contentDescription = "Drag to scale text",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f))
                .pointerInput(clipId, "rotate_handle") {
                    var working = currentBase
                    detectDragGestures(
                        onDragStart = {
                            onBeginGesture()
                            working = currentBase
                        },
                        onDragEnd = { onCommitGesture("Preview Rotate Text") },
                        onDragCancel = { onCommitGesture("Preview Rotate Text") },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val nextRot = (working.rotationDeg + dragAmount.x * 0.75f).coerceIn(-360f, 360f)
                            working = working.copy(rotationDeg = nextRot)
                            onPreviewTransform(currentClipId, working)
                        },
                    )
                }
                .testTag("preview_rotate_handle"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Drag to rotate text",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun RenderedTextOverlay(
    spec: TextOverlaySpec,
    transform: LayerTransform,
    blendMode: BlendModePreset,
    clipRelativeMs: Long,
    stageWidthPx: Float,
    stageHeightPx: Float,
    isInvertMaskLayer: Boolean = false,
    isSelected: Boolean = false,
    showTransformOutline: Boolean = false,
    onTapSelect: () -> Unit = {},
) {
    val animWindowMs = 450f
    val rawAnimT = (clipRelativeMs.toFloat() / animWindowMs).coerceIn(0f, 1f)
    val eased = EffectsEngine.applyEasing(rawAnimT, com.example.engine.project.EasingPreset.EASE_OUT)

    val karaokeEval = remember(spec, clipRelativeMs) {
        UnifiedTextEngine.evaluateKaraoke(spec, clipRelativeMs)
    }
    val fullPlainText = remember(spec.text, karaokeEval) {
        if (karaokeEval.hasTimedWords) {
            karaokeEval.words.joinToString(" ") { it.word.text }
        } else {
            spec.text
        }
    }
    val visibleCharCount = when (spec.animationPreset) {
        TextAnimationPreset.TYPEWRITER -> {
            if (fullPlainText.isEmpty()) 0 else {
                (fullPlainText.length * (clipRelativeMs.toFloat() / 900f).coerceIn(0.05f, 1f))
                    .roundToInt()
                    .coerceIn(1, fullPlainText.length)
            }
        }
        else -> fullPlainText.length
    }
    val displayText = if (visibleCharCount <= 0) "" else fullPlainText.take(visibleCharCount)

    val animAlpha = when (spec.animationPreset) {
        TextAnimationPreset.FADE_IN, TextAnimationPreset.SLIDE_UP -> eased
        TextAnimationPreset.POP_OVERSHOOT -> (eased * 1.4f).coerceIn(0f, 1f)
        else -> 1f
    }
    val animScale = when (spec.animationPreset) {
        TextAnimationPreset.POP_OVERSHOOT -> {
            if (rawAnimT < 0.7f) {
                0.65f + (rawAnimT / 0.7f) * 0.45f
            } else {
                1.10f - ((rawAnimT - 0.7f) / 0.3f) * 0.10f
            }
        }
        else -> 1f
    }
    val fontFamily = when (spec.stylePreset) {
        TextStylePreset.BOLD_TITLE, TextStylePreset.NEON_POP, TextStylePreset.KINETIC_BADGE -> SpaceGroteskFontFamily
        TextStylePreset.CINEMA_SERIF -> FontFamily.Serif
        TextStylePreset.MONO_TYPEWRITER -> JetBrainsMonoFontFamily
        TextStylePreset.MINIMAL_SUBTITLE -> InterFontFamily
    }
    val fontWeight = when (spec.stylePreset) {
        TextStylePreset.BOLD_TITLE, TextStylePreset.NEON_POP, TextStylePreset.KINETIC_BADGE -> FontWeight.ExtraBold
        TextStylePreset.CINEMA_SERIF -> FontWeight.SemiBold
        TextStylePreset.MONO_TYPEWRITER, TextStylePreset.MINIMAL_SUBTITLE -> FontWeight.Medium
    }
    val textAlign = when (spec.alignment) {
        TextAlignMode.LEFT -> TextAlign.Start
        TextAlignMode.CENTER -> TextAlign.Center
        TextAlignMode.RIGHT -> TextAlign.End
    }
    val textColor = Color(spec.textColorArgb.toInt())
    val bgColor = Color(spec.backgroundColorArgb.toInt())

    val minStageDim = minOf(stageWidthPx, stageHeightPx).coerceAtLeast(120f)
    val textSizePx = (minStageDim * 0.068f * spec.fontScale.coerceIn(0.5f, 2.5f)).coerceIn(10f, 180f)
    val density = LocalDensity.current
    val baseSize = with(density) { textSizePx.toSp() }
    val cornerRadiusDp = with(density) { ((minStageDim * 0.016f).coerceIn(3f, 18f)).toDp() }
    val bgAlphaRatio = bgColor.alpha > 0.02f
    val padHDp = with(density) { (if (bgAlphaRatio) textSizePx * 0.55f else textSizePx * 0.28f).toDp() }
    val padVDp = with(density) { (if (bgAlphaRatio) textSizePx * 0.28f else textSizePx * 0.18f).toDp() }
    val maxWrapWidthDp = with(density) { (stageWidthPx * 0.86f).coerceAtLeast(48f).toDp() }

    val animTranslateY = when (spec.animationPreset) {
        TextAnimationPreset.SLIDE_UP -> (1f - eased) * (stageHeightPx * 0.04f)
        else -> 0f
    }
    val glitchOffsetX = if (spec.animationPreset == TextAnimationPreset.GLITCH && rawAnimT < 1f) {
        sin(clipRelativeMs * 0.25f) * (stageWidthPx * 0.01f) * (1f - rawAnimT)
    } else 0f

    val effPosX = UnifiedTextEngine.effectivePositionX(spec, transform)
    val effPosY = UnifiedTextEngine.effectivePositionY(spec, transform)
    val offsetPxX = (effPosX * stageWidthPx + glitchOffsetX).roundToInt()
    val offsetPxY = (effPosY * stageHeightPx + animTranslateY).roundToInt()

    val composeBlend = when (blendMode) {
        BlendModePreset.NORMAL -> BlendMode.SrcOver
        BlendModePreset.ADD -> BlendMode.Plus
        BlendModePreset.MULTIPLY -> BlendMode.Multiply
        BlendModePreset.SCREEN -> BlendMode.Screen
        BlendModePreset.OVERLAY -> BlendMode.Overlay
        BlendModePreset.DARKEN -> BlendMode.Darken
        BlendModePreset.LIGHTEN -> BlendMode.Lighten
    }

    val annotatedDisplayText: AnnotatedString = remember(
        spec,
        displayText,
        karaokeEval,
        visibleCharCount,
        baseSize,
    ) {
        val spacingExtraSp = if (spec.wordSpacingEm > 0.01f) {
            (baseSize.value * spec.wordSpacingEm.coerceIn(0f, 1.5f) * 0.65f).sp
        } else {
            0.sp
        }
        buildAnnotatedString {
            if (!karaokeEval.hasTimedWords || spec.karaokeMode == KaraokeHighlightMode.NONE) {
                if (spacingExtraSp.value <= 0.05f) {
                    append(displayText)
                } else {
                    displayText.forEach { ch ->
                        if (ch == ' ') {
                            withStyle(SpanStyle(letterSpacing = spacingExtraSp)) { append(" ") }
                        } else {
                            append(ch)
                        }
                    }
                }
            } else {
                var remaining = visibleCharCount
                karaokeEval.words.forEachIndexed { idx, evalWord ->
                    if (remaining <= 0) return@forEachIndexed
                    if (idx > 0) {
                        if (spacingExtraSp.value > 0.05f) {
                            withStyle(SpanStyle(letterSpacing = spacingExtraSp)) { append(" ") }
                        } else {
                            append(" ")
                        }
                        remaining -= 1
                        if (remaining <= 0) return@forEachIndexed
                    }
                    val wordChunk = evalWord.word.text.take(remaining)
                    remaining -= wordChunk.length
                    val wordColor = when (evalWord.state) {
                        WordPlaybackState.ACTIVE -> Color(spec.highlightColorArgb.toInt())
                        WordPlaybackState.SPOKEN -> Color(spec.spokenColorArgb.toInt())
                        WordPlaybackState.INACTIVE -> textColor
                    }
                    withStyle(SpanStyle(color = wordColor)) {
                        append(wordChunk)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPxX, offsetPxY) }
            .widthIn(max = maxWrapWidthDp)
            .pointerInput(onTapSelect) {
                detectTapGestures { onTapSelect() }
            }
            .then(
                if (!isInvertMaskLayer && blendMode != BlendModePreset.NORMAL) {
                    Modifier.drawWithContent {
                        val layerPaint = Paint().apply { this.blendMode = composeBlend }
                        drawContext.canvas.saveLayer(size.toRect(), layerPaint)
                        drawContent()
                        drawContext.canvas.restore()
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                alpha = if (isInvertMaskLayer) 1f else (animAlpha * transform.opacity).coerceIn(0f, 1f)
                scaleX = animScale * transform.scaleX
                scaleY = animScale * transform.scaleY
                rotationZ = transform.rotationDeg
                transformOrigin = TransformOrigin(transform.anchorX, transform.anchorY)
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .then(
                if (showTransformOutline) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(cornerRadiusDp),
                    )
                } else if (isSelected) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(cornerRadiusDp),
                    )
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(cornerRadiusDp))
            .background(if (isInvertMaskLayer) Color.Transparent else bgColor)
            .padding(horizontal = padHDp, vertical = padVDp),
        contentAlignment = Alignment.Center,
    ) {
        if (!isInvertMaskLayer && spec.hasStroke) {
            Text(
                text = annotatedDisplayText,
                textAlign = textAlign,
                style = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = fontWeight,
                    fontSize = baseSize,
                    color = Color.Black.copy(alpha = 0.85f),
                    drawStyle = Stroke(
                        width = (textSizePx * 0.14f).coerceIn(2f, 14f),
                        join = StrokeJoin.Round,
                    ),
                ),
            )
        }
        Text(
            text = annotatedDisplayText,
            textAlign = textAlign,
            modifier = if (isInvertMaskLayer) Modifier.alpha(0f) else Modifier,
            style = TextStyle(
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                fontSize = baseSize,
                color = textColor,
                shadow = if (!isInvertMaskLayer && spec.hasShadow) {
                    Shadow(
                        color = Color.Black.copy(alpha = 0.75f),
                        offset = Offset(textSizePx * 0.04f, textSizePx * 0.06f),
                        blurRadius = (textSizePx * 0.14f).coerceAtLeast(2f),
                    )
                } else null,
            ),
        )
        if (showTransformOutline) {
            // Visual anchor pivot indicator inside transformed text box
            Box(
                modifier = Modifier
                    .matchParentSize(),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
