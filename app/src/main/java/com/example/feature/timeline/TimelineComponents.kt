package com.example.feature.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.core.common.TimeFormatting
import com.example.engine.audio.WaveformEnvelope
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ClipKind
import com.example.engine.project.EditorMode
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineMarker
import com.example.engine.project.TimelineTrack
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalStudioColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val TrackHeaderWidth = 118.dp
private val RulerHeight = 28.dp
private val TrackLaneHeight = 58.dp
private val BaseDpPerSecond = 64.dp

@Composable
fun TimelineEditorPane(
    project: ProjectDocument,
    assetsById: Map<String, MediaAsset>,
    waveformsByAssetId: Map<String, WaveformEnvelope> = emptyMap(),
    playheadMs: Long,
    totalDurationMs: Long,
    selectedClipIds: Set<String>,
    selectedTrackId: String?,
    timelineZoom: Float,
    snappingEnabled: Boolean,
    activeSnapGuideMs: Long?,
    onSeekMs: (Long) -> Unit,
    onSelectClip: (clipId: String?, multiSelect: Boolean) -> Unit,
    onSelectTrack: (String?) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onReorderTrack: (trackId: String, delta: Int) -> Unit,
    onAddTrack: (TrackType) -> Unit,
    onZoomChange: (Float) -> Unit,
    onToggleSnapping: () -> Unit,
    onBeginClipGesture: () -> Unit,
    onPreviewTrimLeft: (clipId: String, desiredStartMs: Long) -> Unit,
    onPreviewTrimRight: (clipId: String, desiredEndMs: Long) -> Unit,
    onPreviewMoveClip: (clipId: String, desiredStartMs: Long, targetTrackId: String?) -> Unit,
    onCommitClipGesture: (label: String) -> Unit,
    onCancelClipGesture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val density = LocalDensity.current
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    var showAddTrackMenu by remember { mutableStateOf(false) }
    var multiSelectMode by remember { mutableStateOf(false) }

    val visibleTracks = remember(project.tracks, project.editorMode) {
        val sorted = project.tracks.sortedBy { it.orderIndex }
        if (project.editorMode == EditorMode.QUICK) {
            sorted.filter {
                it.type == TrackType.VIDEO || it.type == TrackType.AUDIO || it.clips.isNotEmpty()
            }.ifEmpty { sorted.take(2) }
        } else {
            sorted
        }
    }

    val dpPerSecond: Dp = BaseDpPerSecond * timelineZoom
    val pxPerSecond = with(density) { dpPerSecond.toPx() }
    val pxPerMs = pxPerSecond / 1_000f
    val trackLaneHeightPx = with(density) { TrackLaneHeight.toPx() }
    val timelineExtentMs = maxOf(totalDurationMs + 5_000L, 12_000L)
    val timelineWidthDp = dpPerSecond * (timelineExtentMs / 1_000f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, studio.hairlineBorder, MaterialTheme.shapes.medium),
    ) {
        // Timeline Control Strip: Add Track, Snap Toggle, Multi-Select Toggle, Zoom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(studio.elevatedPanel)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(
                    onClick = { showAddTrackMenu = true },
                    modifier = Modifier.testTag("timeline_add_track_button"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add track",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("Track", style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(
                    expanded = showAddTrackMenu,
                    onDismissRequest = { showAddTrackMenu = false },
                ) {
                    TrackType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text("Add ${type.label} Track") },
                            onClick = {
                                showAddTrackMenu = false
                                onAddTrack(type)
                            },
                        )
                    }
                }
            }

            FilterChip(
                selected = snappingEnabled,
                onClick = onToggleSnapping,
                label = { Text("Snap", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = studio.audioAmberContainer,
                    selectedLabelColor = studio.audioAmber,
                    selectedLeadingIconColor = studio.audioAmber,
                ),
                modifier = Modifier.testTag("timeline_snap_toggle"),
            )

            FilterChip(
                selected = multiSelectMode,
                onClick = { multiSelectMode = !multiSelectMode },
                label = {
                    Text(
                        text = if (selectedClipIds.size > 1) "Multi (${selectedClipIds.size})" else "Multi",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (multiSelectMode) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                modifier = Modifier.testTag("timeline_multiselect_toggle"),
            )

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = { onZoomChange(timelineZoom - 0.25f) },
                enabled = timelineZoom > 0.45f,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("timeline_zoom_out"),
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomOut,
                    contentDescription = "Zoom out timeline",
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "${(timelineZoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = { onZoomChange(timelineZoom + 0.25f) },
                enabled = timelineZoom < 3.9f,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("timeline_zoom_in"),
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = "Zoom in timeline",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Ruler + Track Headers & Lanes
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Left fixed Track Headers column
            Column(
                modifier = Modifier
                    .width(TrackHeaderWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, studio.hairlineBorder),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(RulerHeight)
                        .background(studio.elevatedPanel)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "${project.fps} FPS • ${visibleTracks.size}T",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(verticalScrollState),
                ) {
                    visibleTracks.forEachIndexed { idx, track ->
                        TrackHeaderRow(
                            track = track,
                            isSelected = track.id == selectedTrackId,
                            canMoveUp = idx > 0,
                            canMoveDown = idx < visibleTracks.lastIndex,
                            onSelect = { onSelectTrack(track.id) },
                            onToggleMute = { onToggleTrackMute(track.id) },
                            onToggleLock = { onToggleTrackLock(track.id) },
                            onMoveUp = { onReorderTrack(track.id, -1) },
                            onMoveDown = { onReorderTrack(track.id, 1) },
                        )
                    }
                }
            }

            // Right horizontally scrollable timeline canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScrollState),
            ) {
                Box(modifier = Modifier.width(timelineWidthDp).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TimelineRuler(
                            extentMs = timelineExtentMs,
                            pxPerMs = pxPerMs,
                            zoom = timelineZoom,
                            markers = project.markers,
                            onSeekMs = onSeekMs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(RulerHeight),
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(verticalScrollState),
                        ) {
                            visibleTracks.forEachIndexed { trackIndex, track ->
                                TimelineTrackLane(
                                    track = track,
                                    trackIndex = trackIndex,
                                    visibleTracks = visibleTracks,
                                    assetsById = assetsById,
                                    waveformsByAssetId = waveformsByAssetId,
                                    pxPerMs = pxPerMs,
                                    trackLaneHeightPx = trackLaneHeightPx,
                                    selectedClipIds = selectedClipIds,
                                    isTrackSelected = track.id == selectedTrackId,
                                    onSelectClip = { clipId, isLongPress ->
                                        onSelectClip(clipId, multiSelectMode || isLongPress)
                                    },
                                    onTapLaneMs = { tappedMs ->
                                        onSelectTrack(track.id)
                                        if (!multiSelectMode) onSelectClip(null, false)
                                        onSeekMs(tappedMs)
                                    },
                                    onBeginClipGesture = onBeginClipGesture,
                                    onPreviewTrimLeft = onPreviewTrimLeft,
                                    onPreviewTrimRight = onPreviewTrimRight,
                                    onPreviewMoveClip = onPreviewMoveClip,
                                    onCommitClipGesture = onCommitClipGesture,
                                    onCancelClipGesture = onCancelClipGesture,
                                )
                            }
                        }
                    }

                    // Active Magnetic Snap Guide overlay
                    if (activeSnapGuideMs != null) {
                        val snapXPx = (activeSnapGuideMs * pxPerMs).roundToInt()
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(x = snapXPx, y = 0) }
                                .width(1.5.dp)
                                .fillMaxHeight()
                                .background(studio.audioAmber),
                        )
                    }

                    // Full-height Playhead Needle overlay
                    PlayheadIndicator(
                        playheadMs = playheadMs,
                        pxPerMs = pxPerMs,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackHeaderRow(
    track: TimelineTrack,
    isSelected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val studio = LocalStudioColors.current
    val accent = trackAccentColor(track.type)
    val bg = if (isSelected) studio.elevatedPanel else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackLaneHeight)
            .background(bg)
            .clickable(onClick = onSelect)
            .border(0.5.dp, studio.hairlineBorder)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (track.isMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                val muteIcon = when {
                    track.type == TrackType.AUDIO && track.isMuted -> Icons.AutoMirrored.Filled.VolumeOff
                    track.type == TrackType.AUDIO -> Icons.AutoMirrored.Filled.VolumeUp
                    track.isMuted -> Icons.Default.VisibilityOff
                    else -> Icons.Default.Visibility
                }
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("track_mute_${track.id}"),
                ) {
                    Icon(
                        imageVector = muteIcon,
                        contentDescription = "Toggle ${track.name} mute",
                        tint = if (track.isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("track_lock_${track.id}"),
                ) {
                    Icon(
                        imageVector = if (track.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Toggle ${track.name} lock",
                        tint = if (track.isLocked) studio.audioAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (isSelected) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Move track up",
                            modifier = Modifier.size(13.dp),
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Move track down",
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRuler(
    extentMs: Long,
    pxPerMs: Float,
    zoom: Float,
    markers: List<TimelineMarker> = emptyList(),
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tickColor = studio.hairlineBorder
    val majorTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val beatColor = studio.audioAmber
    val labelStyle = remember(labelColor) {
        TextStyle(
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            color = labelColor,
        )
    }

    val stepMs = when {
        zoom >= 2.0f -> 500L
        zoom >= 0.8f -> 1_000L
        else -> 2_000L
    }

    Canvas(
        modifier = modifier
            .background(studio.elevatedPanel)
            .pointerInput(pxPerMs) {
                detectTapGestures { offset ->
                    val tappedMs = (offset.x / pxPerMs).roundToLong().coerceAtLeast(0L)
                    onSeekMs(tappedMs)
                }
            }
            .pointerInput(pxPerMs) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val draggedMs = (change.position.x / pxPerMs).roundToLong().coerceAtLeast(0L)
                    onSeekMs(draggedMs)
                }
            }
            .testTag("timeline_ruler"),
    ) {
        drawLine(
            color = tickColor,
            start = Offset(0f, size.height - 1f),
            end = Offset(size.width, size.height - 1f),
            strokeWidth = 1.dp.toPx(),
        )
        var t = 0L
        while (t <= extentMs) {
            val x = t * pxPerMs
            val isMajor = t % (stepMs * 2) == 0L || stepMs >= 1_000L
            val tickHeight = if (isMajor) 10.dp.toPx() else 5.dp.toPx()
            drawLine(
                color = if (isMajor) majorTickColor else tickColor,
                start = Offset(x, size.height - tickHeight),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            if (isMajor) {
                val label = if (t % 1_000L == 0L) "${t / 1_000L}s" else "${t / 1_000f}s"
                val measured = textMeasurer.measure(label, style = labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft = Offset(x + 3.dp.toPx(), 3.dp.toPx()),
                )
            }
            t += stepMs
        }

        // Beat / Cue Markers on ruler
        markers.forEach { marker ->
            val mx = marker.timeMs * pxPerMs
            if (mx in 0f..size.width) {
                val r = 4.dp.toPx()
                val cy = size.height - r - 2f
                val diamond = Path().apply {
                    moveTo(mx, cy - r)
                    lineTo(mx + r, cy)
                    lineTo(mx, cy + r)
                    lineTo(mx - r, cy)
                    close()
                }
                drawPath(diamond, color = beatColor)
            }
        }
    }
}

@Composable
private fun TimelineTrackLane(
    track: TimelineTrack,
    trackIndex: Int,
    visibleTracks: List<TimelineTrack>,
    assetsById: Map<String, MediaAsset>,
    waveformsByAssetId: Map<String, WaveformEnvelope>,
    pxPerMs: Float,
    trackLaneHeightPx: Float,
    selectedClipIds: Set<String>,
    isTrackSelected: Boolean,
    onSelectClip: (clipId: String, isLongPress: Boolean) -> Unit,
    onTapLaneMs: (Long) -> Unit,
    onBeginClipGesture: () -> Unit,
    onPreviewTrimLeft: (clipId: String, desiredStartMs: Long) -> Unit,
    onPreviewTrimRight: (clipId: String, desiredEndMs: Long) -> Unit,
    onPreviewMoveClip: (clipId: String, desiredStartMs: Long, targetTrackId: String?) -> Unit,
    onCommitClipGesture: (label: String) -> Unit,
    onCancelClipGesture: () -> Unit,
) {
    val studio = LocalStudioColors.current
    val density = LocalDensity.current
    val laneBg = when {
        track.isLocked -> MaterialTheme.colorScheme.background.copy(alpha = 0.75f)
        isTrackSelected -> studio.elevatedPanel.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.background
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackLaneHeight)
            .background(laneBg)
            .border(0.5.dp, studio.hairlineBorder.copy(alpha = 0.6f))
            .pointerInput(pxPerMs) {
                detectTapGestures { offset ->
                    val ms = (offset.x / pxPerMs).roundToLong().coerceAtLeast(0L)
                    onTapLaneMs(ms)
                }
            },
    ) {
        track.clips.forEach { clip ->
            val asset = assetsById[clip.assetId]
            val offsetDp = with(density) { (clip.timelineStartMs * pxPerMs).toDp() }
            val widthDp = with(density) { (clip.durationMs * pxPerMs).coerceAtLeast(36f).toDp() }
            val isSelected = clip.clipId in selectedClipIds

            TimelineClipBlock(
                clip = clip,
                asset = asset,
                waveform = waveformsByAssetId[clip.assetId],
                trackType = track.type,
                trackIndex = trackIndex,
                visibleTracks = visibleTracks,
                pxPerMs = pxPerMs,
                trackLaneHeightPx = trackLaneHeightPx,
                isSelected = isSelected,
                showTrimHandles = isSelected && selectedClipIds.size == 1 && !track.isLocked,
                isLocked = track.isLocked,
                isMuted = track.isMuted,
                onClick = { onSelectClip(clip.clipId, false) },
                onLongClick = { onSelectClip(clip.clipId, true) },
                onBeginGesture = onBeginClipGesture,
                onPreviewTrimLeft = { startMs -> onPreviewTrimLeft(clip.clipId, startMs) },
                onPreviewTrimRight = { endMs -> onPreviewTrimRight(clip.clipId, endMs) },
                onPreviewMove = { startMs, targetTrack ->
                    onPreviewMoveClip(clip.clipId, startMs, targetTrack)
                },
                onCommitGesture = onCommitClipGesture,
                onCancelGesture = onCancelClipGesture,
                modifier = Modifier
                    .offset(x = offsetDp)
                    .width(widthDp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp, horizontal = 1.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineClipBlock(
    clip: TimelineClipRef,
    asset: MediaAsset?,
    waveform: WaveformEnvelope?,
    trackType: TrackType,
    trackIndex: Int,
    visibleTracks: List<TimelineTrack>,
    pxPerMs: Float,
    trackLaneHeightPx: Float,
    isSelected: Boolean,
    showTrimHandles: Boolean,
    isLocked: Boolean,
    isMuted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onBeginGesture: () -> Unit,
    onPreviewTrimLeft: (Long) -> Unit,
    onPreviewTrimRight: (Long) -> Unit,
    onPreviewMove: (Long, String?) -> Unit,
    onCommitGesture: (String) -> Unit,
    onCancelGesture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val accent = trackAccentColor(trackType)
    val borderStroke = when {
        isSelected -> BorderStroke(2.dp, Color.White)
        else -> BorderStroke(1.dp, accent.copy(alpha = 0.7f))
    }
    val containerColor = accent.copy(alpha = if (isMuted) 0.12f else 0.26f)

    val currentClip by rememberUpdatedState(clip)
    val currentPxPerMs by rememberUpdatedState(pxPerMs)
    val currentVisibleTracks by rememberUpdatedState(visibleTracks)
    val currentTrackIndex by rememberUpdatedState(trackIndex)

    var moveAccumX by remember { mutableFloatStateOf(0f) }
    var moveAccumY by remember { mutableFloatStateOf(0f) }
    var initialMoveStartMs by remember { mutableLongStateOf(0L) }

    var leftTrimAccumX by remember { mutableFloatStateOf(0f) }
    var initialLeftStartMs by remember { mutableLongStateOf(0L) }

    var rightTrimAccumX by remember { mutableFloatStateOf(0f) }
    var initialRightEndMs by remember { mutableLongStateOf(0L) }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .pointerInput(clip.clipId, isLocked) {
                if (isLocked) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        onClick()
                        moveAccumX = 0f
                        moveAccumY = 0f
                        initialMoveStartMs = currentClip.timelineStartMs
                        onBeginGesture()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        moveAccumX += dragAmount.x
                        moveAccumY += dragAmount.y
                        val deltaMs = (moveAccumX / currentPxPerMs).roundToLong()
                        val trackOffset = (moveAccumY / trackLaneHeightPx).roundToInt()
                        val targetIdx = (currentTrackIndex + trackOffset)
                            .coerceIn(0, currentVisibleTracks.lastIndex)
                        val targetTrackId = currentVisibleTracks.getOrNull(targetIdx)?.id
                        onPreviewMove(
                            (initialMoveStartMs + deltaMs).coerceAtLeast(0L),
                            targetTrackId,
                        )
                    },
                    onDragEnd = { onCommitGesture("Move Clip") },
                    onDragCancel = onCancelGesture,
                )
            }
            .testTag("timeline_clip_${clip.clipId}"),
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = borderStroke,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (asset != null && asset.type != MediaType.AUDIO && clip.clipKind == ClipKind.MEDIA) {
                AsyncImage(
                    model = asset.playbackUri,
                    contentDescription = asset.displayName,
                    contentScale = ContentScale.Crop,
                    alpha = if (isMuted || clip.isMuted) 0.25f else 0.45f,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Fade In / Fade Out envelope ramps + Keyframe diamond markers
            val keyframeTimestamps = remember(clip.keyframeTracks) {
                clip.keyframeTracks
                    .flatMap { it.keyframes }
                    .map { it.timeMs }
                    .distinct()
            }
            if (clip.fadeInMs > 0L || clip.fadeOutMs > 0L || keyframeTimestamps.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val dur = clip.durationMs.coerceAtLeast(1L).toFloat()
                    if (clip.fadeInMs > 0L) {
                        val fadeInX = ((clip.fadeInMs / dur) * size.width).coerceIn(0f, size.width)
                        drawLine(
                            color = Color.White.copy(alpha = 0.65f),
                            start = Offset(0f, size.height),
                            end = Offset(fadeInX, 4f),
                            strokeWidth = 2f,
                        )
                    }
                    if (clip.fadeOutMs > 0L) {
                        val fadeOutStartX = (size.width - (clip.fadeOutMs / dur) * size.width)
                            .coerceIn(0f, size.width)
                        drawLine(
                            color = Color.White.copy(alpha = 0.65f),
                            start = Offset(fadeOutStartX, 4f),
                            end = Offset(size.width, size.height),
                            strokeWidth = 2f,
                        )
                    }
                    if (keyframeTimestamps.isNotEmpty()) {
                        val r = 4.dp.toPx()
                        val markerY = (size.height - r - 2.dp.toPx()).coerceAtLeast(r)
                        keyframeTimestamps.forEach { kfTimeMs ->
                            val kfX = ((kfTimeMs.toFloat() / dur) * size.width)
                                .coerceIn(r, (size.width - r).coerceAtLeast(r))
                            val diamond = Path().apply {
                                moveTo(kfX, markerY - r)
                                lineTo(kfX + r, markerY)
                                lineTo(kfX, markerY + r)
                                lineTo(kfX - r, markerY)
                                close()
                            }
                            drawPath(diamond, color = studio.audioAmber)
                        }
                    }
                }
            }

            // Real Sliced Waveform Bars (full height for Audio tracks, bottom mini-strip for Video tracks)
            if (waveform != null && waveform.peaks.isNotEmpty() && clip.clipKind == ClipKind.MEDIA) {
                val barCount = ((clip.durationMs * pxPerMs) / 5f).toInt().coerceIn(8, 120)
                val slicedPeaks = remember(waveform, clip.sourceStartMs, clip.durationMs, clip.speed, barCount) {
                    waveform.slicePeaks(
                        sourceStartMs = clip.sourceStartMs,
                        sourceSpanMs = (clip.durationMs * clip.speed).toLong().coerceAtLeast(100L),
                        targetBars = barCount,
                    )
                }
                val waveColor = if (trackType == TrackType.AUDIO) {
                    studio.audioAmber.copy(alpha = if (clip.isMuted || isMuted) 0.2f else 0.52f)
                } else {
                    Color.White.copy(alpha = if (clip.isMuted || isMuted) 0.14f else 0.34f)
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val step = size.width / slicedPeaks.size.coerceAtLeast(1)
                    val barW = (step * 0.58f).coerceAtLeast(1.5f)
                    val gainScale = if (clip.isMuted || isMuted) 0.25f else clip.volume.coerceIn(0.2f, 1.4f)
                    slicedPeaks.forEachIndexed { idx, amp ->
                        val bx = idx * step + step * 0.2f
                        if (trackType == TrackType.AUDIO) {
                            val h = (amp * gainScale * size.height * 0.82f).coerceIn(3f, size.height * 0.9f)
                            val top = (size.height - h) * 0.5f
                            drawLine(
                                color = waveColor,
                                start = Offset(bx, top),
                                end = Offset(bx, top + h),
                                strokeWidth = barW,
                            )
                        } else {
                            val h = (amp * gainScale * size.height * 0.26f).coerceIn(2f, size.height * 0.32f)
                            drawLine(
                                color = waveColor,
                                start = Offset(bx, size.height - 2f),
                                end = Offset(bx, size.height - 2f - h),
                                strokeWidth = barW,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (showTrimHandles) 14.dp else 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (clip.transitionIn != null && clip.transitionIn.type != TransitionType.NONE) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "FX",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                if (asset?.type == MediaType.AUDIO) {
                    Icon(
                        imageVector = if (clip.isMuted || isMuted) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.Default.AudioFile
                        },
                        contentDescription = null,
                        tint = studio.audioAmber,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    val primaryTitle = when {
                        clip.clipKind == ClipKind.TEXT -> "T • ${clip.textSpec?.text?.ifBlank { "Text" } ?: "Text"}"
                        clip.clipKind == ClipKind.ADJUSTMENT -> {
                            val label = if (clip.shaderEffect.isActive && clip.adjustments.isDefault) {
                                clip.shaderEffect.type.label
                            } else {
                                clip.adjustments.filterPreset.label
                            }
                            "ADJ • $label"
                        }
                        else -> asset?.displayName ?: "Clip"
                    }
                    Text(
                        text = primaryTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = TimeFormatting.formatDurationLabel(clip.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        if (abs(clip.speed - 1f) > 0.02f) {
                            Text(
                                text = "${String.format("%.2gx", clip.speed)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                        if (!clip.adjustments.isDefault) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(EffectsEngine.filterAccentColorArgb(clip.adjustments.filterPreset).toInt())),
                            )
                        }
                        if (clip.blendMode != BlendModePreset.NORMAL) {
                            Text(
                                text = clip.blendMode.label.take(3).uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                            )
                        }
                        if (clip.mask.isActive) {
                            Text(
                                text = "M",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                color = studio.readyEmerald,
                                maxLines = 1,
                            )
                        }
                        if (clip.shaderEffect.isActive) {
                            Text(
                                text = "GL",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                        if (keyframeTimestamps.isNotEmpty()) {
                            Text(
                                text = "KF",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                color = studio.audioAmber,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (isLocked) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = studio.audioAmber,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            if (showTrimHandles) {
                // Left Trim Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(14.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.92f))
                        .pointerInput(clip.clipId) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    leftTrimAccumX = 0f
                                    initialLeftStartMs = currentClip.timelineStartMs
                                    onBeginGesture()
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    leftTrimAccumX += dragAmount
                                    val deltaMs = (leftTrimAccumX / currentPxPerMs).roundToLong()
                                    onPreviewTrimLeft((initialLeftStartMs + deltaMs).coerceAtLeast(0L))
                                },
                                onDragEnd = { onCommitGesture("Trim Clip In") },
                                onDragCancel = onCancelGesture,
                            )
                        }
                        .testTag("trim_left_${clip.clipId}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.background),
                    )
                }

                // Right Trim Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(14.dp)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.92f))
                        .pointerInput(clip.clipId) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    rightTrimAccumX = 0f
                                    initialRightEndMs =
                                        currentClip.timelineStartMs + currentClip.durationMs
                                    onBeginGesture()
                                },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    rightTrimAccumX += dragAmount
                                    val deltaMs = (rightTrimAccumX / currentPxPerMs).roundToLong()
                                    onPreviewTrimRight((initialRightEndMs + deltaMs).coerceAtLeast(100L))
                                },
                                onDragEnd = { onCommitGesture("Trim Clip Out") },
                                onDragCancel = onCancelGesture,
                            )
                        }
                        .testTag("trim_right_${clip.clipId}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.background),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayheadIndicator(
    playheadMs: Long,
    pxPerMs: Float,
) {
    val xPx = (playheadMs * pxPerMs).roundToInt()
    val cyan = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .offset { IntOffset(x = xPx - 6, y = 0) }
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp, 12.dp)
                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                .background(cyan),
        )
        Box(
            modifier = Modifier
                .width(2.dp)
                .weight(1f)
                .background(cyan),
        )
    }
}

@Composable
private fun trackAccentColor(type: TrackType): Color {
    val studio = LocalStudioColors.current
    return when (type) {
        TrackType.VIDEO -> MaterialTheme.colorScheme.primary
        TrackType.OVERLAY, TrackType.TEXT -> MaterialTheme.colorScheme.secondary
        TrackType.AUDIO -> studio.audioAmber
        TrackType.ADJUSTMENT -> studio.readyEmerald
    }
}
