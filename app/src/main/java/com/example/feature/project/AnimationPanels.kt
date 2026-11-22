package com.example.feature.project

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.StudioPanel
import com.example.engine.animation.AnimationEngine
import com.example.engine.project.BezierCurveSpec
import com.example.engine.project.ClipKind
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.MotionPreset
import com.example.engine.project.PropertyKeyframe
import com.example.engine.project.TimelineClipRef
import com.example.ui.theme.LocalStudioColors
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun AnimationOverviewContent(
    clip: TimelineClipRef?,
    asset: MediaAsset?,
    playheadMs: Long,
    selectedProperty: KeyframeProperty,
    selectedKeyframeIds: Set<String>,
    onSelectProperty: (KeyframeProperty) -> Unit,
    onSelectKeyframe: (keyframeId: String?, multiSelect: Boolean) -> Unit,
    onJumpToClipRelativeTime: (Long) -> Unit,
    onToggleKeyframeAtPlayhead: (KeyframeProperty) -> Unit,
    onPreviewValueAtPlayhead: (KeyframeProperty, Float) -> Unit,
    onPreviewMoveKeyframe: (KeyframeProperty, String, Long, Float) -> Unit,
    onPreviewBezierHandle: (KeyframeProperty, String, BezierCurveSpec) -> Unit,
    onUpdateKeyframesInterpolation: (KeyframeProperty, KeyframeInterpolation, BezierCurveSpec?) -> Unit,
    onDeleteSelectedKeyframes: (KeyframeProperty) -> Unit,
    onClearPropertyKeyframes: (KeyframeProperty) -> Unit,
    onApplyMotionPreset: (MotionPreset) -> Unit,
    onCommitGesture: (String) -> Unit,
) {
    if (clip == null) {
        EmptyStudioState(
            icon = Icons.Default.Animation,
            title = "Select Any Clip to Animate",
            subtitle = "Tap a video, photo, text, audio, or adjustment clip on the timeline to add keyframes, Bezier curves, and motion presets.",
        )
        return
    }

    val animationEngine = remember { AnimationEngine() }
    val studio = LocalStudioColors.current
    val clipRelativeMs = (playheadMs - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
    val availableProperties = remember(clip.clipKind, asset?.type) {
        eligibleKeyframeProperties(clip, asset)
    }
    val activeProperty = if (selectedProperty in availableProperties) {
        selectedProperty
    } else {
        availableProperties.first()
    }

    val currentTrack = clip.keyframeTracks.firstOrNull { it.property == activeProperty }
    val sortedKeyframes = remember(currentTrack) {
        currentTrack?.keyframes?.sortedBy { it.timeMs }.orEmpty()
    }
    val currentValue = animationEngine.evaluateProperty(clip, activeProperty, clipRelativeMs)
    val hasKeyAtPlayhead = sortedKeyframes.any { kotlin.math.abs(it.timeMs - clipRelativeMs) <= 40L }

    var graphZoom by remember { mutableFloatStateOf(1f) }
    var graphPanNorm by remember { mutableFloatStateOf(0f) }
    var multiSelectMode by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Motion Graphics Presets Card
        StudioPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Animation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Motion Graphics Presets",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MotionPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = { onApplyMotionPreset(preset) },
                            label = { Text(preset.label) },
                            modifier = Modifier.testTag("motion_preset_${preset.name.lowercase()}"),
                        )
                    }
                }
            }
        }

        // Property Selector & Keyframe Graph Editor Card
        StudioPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Keyframe & Graph Editor",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (sortedKeyframes.isNotEmpty()) {
                        TextButton(
                            onClick = { onClearPropertyKeyframes(activeProperty) },
                            modifier = Modifier.testTag("clear_keyframe_track_button"),
                        ) {
                            Text("Clear ${activeProperty.label}")
                        }
                    }
                }

                // Property Selector Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    availableProperties.forEach { prop ->
                        val hasKeys = clip.keyframeTracks.any { it.property == prop && it.keyframes.isNotEmpty() }
                        FilterChip(
                            selected = prop == activeProperty,
                            onClick = { onSelectProperty(prop) },
                            leadingIcon = if (hasKeys) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(studio.audioAmber),
                                    )
                                }
                            } else null,
                            label = { Text(prop.label) },
                            modifier = Modifier.testTag("kf_prop_${prop.name.lowercase()}"),
                        )
                    }
                }

                // Keyframe Navigation & Playhead Diamond Control
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val prevKf = sortedKeyframes.lastOrNull { it.timeMs < clipRelativeMs - 40L }
                    val nextKf = sortedKeyframes.firstOrNull { it.timeMs > clipRelativeMs + 40L }

                    IconButton(
                        onClick = { prevKf?.let { onJumpToClipRelativeTime(it.timeMs) } },
                        enabled = prevKf != null,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("kf_prev_button"),
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous keyframe")
                    }

                    OutlinedButton(
                        onClick = { onToggleKeyframeAtPlayhead(activeProperty) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("kf_toggle_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = if (hasKeyAtPlayhead) studio.audioAmber else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (hasKeyAtPlayhead) "Remove KF" else "Add KF")
                    }

                    IconButton(
                        onClick = { nextKf?.let { onJumpToClipRelativeTime(it.timeMs) } },
                        enabled = nextKf != null,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("kf_next_button"),
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next keyframe")
                    }

                    FilterChip(
                        selected = multiSelectMode,
                        onClick = { multiSelectMode = !multiSelectMode },
                        label = { Text(if (selectedKeyframeIds.size > 1) "Multi (${selectedKeyframeIds.size})" else "Multi") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (multiSelectMode) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        modifier = Modifier.testTag("kf_multiselect_chip"),
                    )

                    if (selectedKeyframeIds.isNotEmpty()) {
                        IconButton(
                            onClick = { onDeleteSelectedKeyframes(activeProperty) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("kf_delete_selected_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete selected keyframes",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                // Value Slider at Playhead (auto-keys at current clipRelativeMs)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${activeProperty.label} @ ${TimeFormatting.formatDurationLabel(clipRelativeMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatPropertyValue(activeProperty, currentValue),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = currentValue.coerceIn(activeProperty.minValue, activeProperty.maxValue),
                        onValueChange = { onPreviewValueAtPlayhead(activeProperty, it) },
                        onValueChangeFinished = { onCommitGesture("Keyframe ${activeProperty.label}") },
                        valueRange = activeProperty.minValue..activeProperty.maxValue,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("kf_value_slider"),
                    )
                }

                // Graph Zoom / Pan Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Curve Graph (${sortedKeyframes.size} KF)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { graphZoom = (graphZoom - 0.5f).coerceIn(1f, 4f) },
                            enabled = graphZoom > 1.05f,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out graph", modifier = Modifier.size(16.dp))
                        }
                        Text(
                            text = "${String.format("%.1fx", graphZoom)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(
                            onClick = { graphZoom = (graphZoom + 0.5f).coerceIn(1f, 4f) },
                            enabled = graphZoom < 3.9f,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in graph", modifier = Modifier.size(16.dp))
                        }
                        if (graphZoom > 1f || graphPanNorm != 0f) {
                            TextButton(onClick = { graphZoom = 1f; graphPanNorm = 0f }) {
                                Text("Reset")
                            }
                        }
                    }
                }

                // Interactive Bezier Graph Editor Canvas
                GraphEditorCanvas(
                    clip = clip,
                    property = activeProperty,
                    keyframes = sortedKeyframes,
                    selectedKeyframeIds = selectedKeyframeIds,
                    clipRelativeMs = clipRelativeMs,
                    graphZoom = graphZoom,
                    graphPanNorm = graphPanNorm,
                    multiSelectMode = multiSelectMode,
                    onPanChanged = { delta ->
                        val maxPan = (1f - 1f / graphZoom).coerceAtLeast(0f)
                        graphPanNorm = (graphPanNorm + delta).coerceIn(0f, maxPan)
                    },
                    onSelectKeyframe = onSelectKeyframe,
                    onPreviewMoveKeyframe = { kfId, newTime, newVal ->
                        onPreviewMoveKeyframe(activeProperty, kfId, newTime, newVal)
                    },
                    onPreviewBezierHandle = { kfId, bezier ->
                        onPreviewBezierHandle(activeProperty, kfId, bezier)
                    },
                    onCommitGesture = onCommitGesture,
                )

                // Interpolation & Bezier Readout for selected keyframe(s)
                val primarySelectedKf = sortedKeyframes.firstOrNull { it.id in selectedKeyframeIds }
                    ?: sortedKeyframes.firstOrNull()
                if (primarySelectedKf != null) {
                    Text(
                        text = if (selectedKeyframeIds.size > 1) {
                            "Easing for ${selectedKeyframeIds.size} selected keyframes"
                        } else {
                            "Segment Easing (${TimeFormatting.formatDurationLabel(primarySelectedKf.timeMs)} • ${
                                formatPropertyValue(activeProperty, primarySelectedKf.value)
                            })"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        KeyframeInterpolation.entries.forEach { interp ->
                            FilterChip(
                                selected = primarySelectedKf.interpolation == interp,
                                onClick = {
                                    onUpdateKeyframesInterpolation(activeProperty, interp, primarySelectedKf.bezier)
                                },
                                label = { Text(interp.label) },
                                modifier = Modifier.testTag("kf_interp_${interp.name.lowercase()}"),
                            )
                        }
                    }
                    if (primarySelectedKf.interpolation == KeyframeInterpolation.CUBIC_BEZIER) {
                        val b = primarySelectedKf.bezier
                        Text(
                            text = "Bezier P1(${String.format("%.2f", b.x1)}, ${String.format("%.2f", b.y1)}) • P2(${String.format("%.2f", b.x2)}, ${String.format("%.2f", b.y2)}) — drag green/amber handles in graph",
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.readyEmerald,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphEditorCanvas(
    clip: TimelineClipRef,
    property: KeyframeProperty,
    keyframes: List<PropertyKeyframe>,
    selectedKeyframeIds: Set<String>,
    clipRelativeMs: Long,
    graphZoom: Float,
    graphPanNorm: Float,
    multiSelectMode: Boolean,
    onPanChanged: (Float) -> Unit,
    onSelectKeyframe: (String?, Boolean) -> Unit,
    onPreviewMoveKeyframe: (keyframeId: String, newTimeMs: Long, newValue: Float) -> Unit,
    onPreviewBezierHandle: (keyframeId: String, bezier: BezierCurveSpec) -> Unit,
    onCommitGesture: (String) -> Unit,
) {
    val studio = LocalStudioColors.current
    val animationEngine = remember { AnimationEngine() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val amberColor = studio.audioAmber
    val emeraldColor = studio.readyEmerald
    val gridColor = studio.hairlineBorder

    val durMs = clip.durationMs.coerceAtLeast(1L)
    val valSpan = (property.maxValue - property.minValue).coerceAtLeast(0.01f)
    val windowSpan = (1f / graphZoom.coerceIn(1f, 4f))
    val windowStart = graphPanNorm.coerceIn(0f, (1f - windowSpan).coerceAtLeast(0f))

    val currentKeyframes by rememberUpdatedState(keyframes)
    val currentSelectedIds by rememberUpdatedState(selectedKeyframeIds)
    val currentWindowStart by rememberUpdatedState(windowStart)
    val currentWindowSpan by rememberUpdatedState(windowSpan)
    val currentDurMs by rememberUpdatedState(durMs)
    val currentProperty by rememberUpdatedState(property)
    val currentMultiSelect by rememberUpdatedState(multiSelectMode)

    fun toScreenX(timeMs: Long, width: Float): Float {
        val normT = timeMs.toFloat() / durMs.toFloat()
        return ((normT - windowStart) / windowSpan) * width
    }

    fun toScreenY(value: Float, height: Float): Float {
        val pad = 16f
        val normV = ((value - property.minValue) / valSpan).coerceIn(-0.2f, 1.2f)
        return height - pad - normV * (height - pad * 2f)
    }

    fun toTimeMs(x: Float, width: Float): Long {
        val normT = (currentWindowStart + (x / width.coerceAtLeast(1f)) * currentWindowSpan).coerceIn(0f, 1f)
        return (normT * currentDurMs).roundToLong()
    }

    fun toValue(y: Float, height: Float): Float {
        val pad = 16f
        val usableH = (height - pad * 2f).coerceAtLeast(1f)
        val normV = ((height - pad - y) / usableH).coerceIn(0f, 1f)
        return currentProperty.minValue + normV * (currentProperty.maxValue - currentProperty.minValue)
    }

    val sampledPoints = remember(clip, property) {
        animationEngine.sampleCurvePoints(clip, property, sampleCount = 72)
    }

    // Active Bezier segment if selected keyframe has a successor and uses CUBIC_BEZIER
    val activeBezierKfIndex = keyframes.indexOfFirst {
        it.id in selectedKeyframeIds && it.interpolation == KeyframeInterpolation.CUBIC_BEZIER
    }.takeIf { it in 0 until keyframes.lastIndex }
        ?: keyframes.indexOfFirst { it.interpolation == KeyframeInterpolation.CUBIC_BEZIER }
            .takeIf { it in 0 until keyframes.lastIndex }

    var dragTarget by remember { mutableStateOf<String?>(null) } // "kf:<id>", "h1:<id>", "h2:<id>", or "pan"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, studio.hairlineBorder, RoundedCornerShape(8.dp))
            .clipToBounds()
            .testTag("graph_editor_canvas"),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(clip.clipId, property) {
                    detectTapGestures { tap ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val hit = currentKeyframes.minByOrNull { kf ->
                            val sx = ((kf.timeMs.toFloat() / currentDurMs) - currentWindowStart) / currentWindowSpan * w
                            val normV = ((kf.value - currentProperty.minValue) /
                                (currentProperty.maxValue - currentProperty.minValue).coerceAtLeast(0.01f))
                            val sy = h - 16f - normV * (h - 32f)
                            hypot(tap.x - sx, tap.y - sy)
                        }
                        if (hit != null) {
                            val sx = ((hit.timeMs.toFloat() / currentDurMs) - currentWindowStart) / currentWindowSpan * w
                            val normV = ((hit.value - currentProperty.minValue) /
                                (currentProperty.maxValue - currentProperty.minValue).coerceAtLeast(0.01f))
                            val sy = h - 16f - normV * (h - 32f)
                            if (hypot(tap.x - sx, tap.y - sy) <= 44f) {
                                onSelectKeyframe(hit.id, currentMultiSelect)
                            } else if (!currentMultiSelect) {
                                onSelectKeyframe(null, false)
                            }
                        }
                    }
                }
                .pointerInput(clip.clipId, property) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val spanV = (currentProperty.maxValue - currentProperty.minValue).coerceAtLeast(0.01f)

                            // Check Bezier handles first
                            val bezIdx = currentKeyframes.indexOfFirst {
                                it.id in currentSelectedIds && it.interpolation == KeyframeInterpolation.CUBIC_BEZIER
                            }.takeIf { it in 0 until currentKeyframes.lastIndex }
                            if (bezIdx != null) {
                                val k0 = currentKeyframes[bezIdx]
                                val k1 = currentKeyframes[bezIdx + 1]
                                val x0 = ((k0.timeMs.toFloat() / currentDurMs) - currentWindowStart) / currentWindowSpan * w
                                val x1 = ((k1.timeMs.toFloat() / currentDurMs) - currentWindowStart) / currentWindowSpan * w
                                val y0 = h - 16f - ((k0.value - currentProperty.minValue) / spanV) * (h - 32f)
                                val y1 = h - 16f - ((k1.value - currentProperty.minValue) / spanV) * (h - 32f)

                                val h1x = x0 + (x1 - x0) * k0.bezier.x1
                                val h1y = y0 + (y1 - y0) * k0.bezier.y1
                                val h2x = x0 + (x1 - x0) * k0.bezier.x2
                                val h2y = y0 + (y1 - y0) * k0.bezier.y2
                                if (hypot(start.x - h1x, start.y - h1y) <= 40f) {
                                    dragTarget = "h1:${k0.id}"
                                    return@detectDragGestures
                                }
                                if (hypot(start.x - h2x, start.y - h2y) <= 40f) {
                                    dragTarget = "h2:${k0.id}"
                                    return@detectDragGestures
                                }
                            }

                            // Check keyframe nodes
                            val hitKf = currentKeyframes.firstOrNull { kf ->
                                val sx = ((kf.timeMs.toFloat() / currentDurMs) - currentWindowStart) / currentWindowSpan * w
                                val sy = h - 16f - ((kf.value - currentProperty.minValue) / spanV) * (h - 32f)
                                hypot(start.x - sx, start.y - sy) <= 44f
                            }
                            if (hitKf != null) {
                                onSelectKeyframe(hitKf.id, false)
                                dragTarget = "kf:${hitKf.id}"
                            } else {
                                dragTarget = "pan"
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val target = dragTarget ?: return@detectDragGestures
                            when {
                                target == "pan" -> {
                                    val deltaNorm = (-dragAmount.x / w) * currentWindowSpan
                                    onPanChanged(deltaNorm)
                                }
                                target.startsWith("kf:") -> {
                                    val kfId = target.removePrefix("kf:")
                                    val newTime = toTimeMs(change.position.x, w)
                                    val newVal = toValue(change.position.y, h)
                                    onPreviewMoveKeyframe(kfId, newTime, newVal)
                                }
                                target.startsWith("h1:") || target.startsWith("h2:") -> {
                                    val isH1 = target.startsWith("h1:")
                                    val kfId = target.substringAfter(":")
                                    val idx = currentKeyframes.indexOfFirst { it.id == kfId }
                                    if (idx in 0 until currentKeyframes.lastIndex) {
                                        val k0 = currentKeyframes[idx]
                                        val k1 = currentKeyframes[idx + 1]
                                        val spanV = (currentProperty.maxValue - currentProperty.minValue).coerceAtLeast(0.01f)
                                        val x0 = ((k0.timeMs.toFloat() / currentDurMs) - currentWindowStart) / currentWindowSpan * w
                                        val x1 = ((k1.timeMs.toFloat() / currentDurMs) - currentWindowStart) / currentWindowSpan * w
                                        val y0 = h - 16f - ((k0.value - currentProperty.minValue) / spanV) * (h - 32f)
                                        val y1 = h - 16f - ((k1.value - currentProperty.minValue) / spanV) * (h - 32f)
                                        val dx = (x1 - x0).let { if (kotlin.math.abs(it) < 4f) 4f else it }
                                        val dy = (y1 - y0).let { if (kotlin.math.abs(it) < 4f) -4f else it }
                                        val nx = ((change.position.x - x0) / dx).coerceIn(0.02f, 0.98f)
                                        val ny = ((change.position.y - y0) / dy).coerceIn(-0.6f, 1.6f)
                                        val nextBezier = if (isH1) {
                                            k0.bezier.copy(x1 = nx, y1 = ny)
                                        } else {
                                            k0.bezier.copy(x2 = nx, y2 = ny)
                                        }
                                        onPreviewBezierHandle(kfId, nextBezier)
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (dragTarget != null && dragTarget != "pan") {
                                onCommitGesture("Edit Curve")
                            }
                            dragTarget = null
                        },
                        onDragCancel = { dragTarget = null },
                    )
                },
        ) {
            val w = size.width
            val h = size.height

            // Horizontal value grid lines (25%, 50%, 75%) & vertical time grid lines
            for (i in 1..3) {
                val gy = 16f + (h - 32f) * (i / 4f)
                drawLine(gridColor.copy(alpha = 0.55f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                val gx = w * (i / 4f)
                drawLine(gridColor.copy(alpha = 0.4f), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            }

            // Default property baseline
            val baseY = toScreenY(property.defaultValue, h)
            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(0f, baseY),
                end = Offset(w, baseY),
                strokeWidth = 1.2f,
            )

            // Playhead vertical line
            val playX = toScreenX(clipRelativeMs, w)
            if (playX in 0f..w) {
                drawLine(
                    color = primaryColor.copy(alpha = 0.85f),
                    start = Offset(playX, 0f),
                    end = Offset(playX, h),
                    strokeWidth = 2f,
                )
            }

            // Interpolated curve path
            if (sampledPoints.isNotEmpty()) {
                val path = Path()
                sampledPoints.forEachIndexed { idx, (tMs, v) ->
                    val px = toScreenX(tMs, w)
                    val py = toScreenY(v, h)
                    if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path = path,
                    color = if (keyframes.isEmpty()) primaryColor.copy(alpha = 0.45f) else secondaryColor,
                    style = Stroke(width = 3.2f, cap = StrokeCap.Round),
                )
            }

            // Bezier Tangent Handles for active Bezier segment
            if (activeBezierKfIndex != null) {
                val k0 = keyframes[activeBezierKfIndex]
                val k1 = keyframes[activeBezierKfIndex + 1]
                val x0 = toScreenX(k0.timeMs, w)
                val y0 = toScreenY(k0.value, h)
                val x1 = toScreenX(k1.timeMs, w)
                val y1 = toScreenY(k1.value, h)

                val h1 = Offset(x0 + (x1 - x0) * k0.bezier.x1, y0 + (y1 - y0) * k0.bezier.y1)
                val h2 = Offset(x0 + (x1 - x0) * k0.bezier.x2, y0 + (y1 - y0) * k0.bezier.y2)

                drawLine(emeraldColor.copy(alpha = 0.7f), Offset(x0, y0), h1, strokeWidth = 2f)
                drawLine(amberColor.copy(alpha = 0.7f), Offset(x1, y1), h2, strokeWidth = 2f)
                drawCircle(emeraldColor, radius = 7.dp.toPx(), center = h1)
                drawCircle(Color.White, radius = 7.dp.toPx(), center = h1, style = Stroke(2f))
                drawCircle(amberColor, radius = 7.dp.toPx(), center = h2)
                drawCircle(Color.White, radius = 7.dp.toPx(), center = h2, style = Stroke(2f))
            }

            // Keyframe Diamond Nodes
            keyframes.forEach { kf ->
                val cx = toScreenX(kf.timeMs, w)
                val cy = toScreenY(kf.value, h)
                val selected = kf.id in selectedKeyframeIds
                val r = if (selected) 8.5.dp.toPx() else 6.5.dp.toPx()
                val diamond = Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx + r, cy)
                    lineTo(cx, cy + r)
                    lineTo(cx - r, cy)
                    close()
                }
                drawPath(diamond, color = if (selected) amberColor else primaryColor)
                if (selected) {
                    drawPath(diamond, color = Color.White, style = Stroke(width = 2.2f))
                }
            }
        }
    }
}

private fun eligibleKeyframeProperties(
    clip: TimelineClipRef,
    asset: MediaAsset?,
): List<KeyframeProperty> = when {
    clip.clipKind == ClipKind.TEXT -> listOf(
        KeyframeProperty.POSITION_X,
        KeyframeProperty.POSITION_Y,
        KeyframeProperty.SCALE_X,
        KeyframeProperty.SCALE_Y,
        KeyframeProperty.ROTATION,
        KeyframeProperty.OPACITY,
        KeyframeProperty.ANCHOR_X,
        KeyframeProperty.ANCHOR_Y,
        KeyframeProperty.TEXT_FONT_SCALE,
    )
    clip.clipKind == ClipKind.ADJUSTMENT -> listOf(
        KeyframeProperty.BLUR,
        KeyframeProperty.SHADER_INTENSITY,
    )
    asset?.type == MediaType.AUDIO -> listOf(
        KeyframeProperty.VOLUME,
    )
    else -> listOf(
        KeyframeProperty.POSITION_X,
        KeyframeProperty.POSITION_Y,
        KeyframeProperty.SCALE_X,
        KeyframeProperty.SCALE_Y,
        KeyframeProperty.ROTATION,
        KeyframeProperty.OPACITY,
        KeyframeProperty.ANCHOR_X,
        KeyframeProperty.ANCHOR_Y,
        KeyframeProperty.VOLUME,
        KeyframeProperty.BLUR,
        KeyframeProperty.SHADER_INTENSITY,
        KeyframeProperty.MASK_FEATHER,
    )
}

private fun formatPropertyValue(property: KeyframeProperty, value: Float): String = when (property) {
    KeyframeProperty.SCALE_X, KeyframeProperty.SCALE_Y, KeyframeProperty.TEXT_FONT_SCALE ->
        "${String.format("%.2fx", value)}"
    KeyframeProperty.ROTATION -> "${value.roundToInt()}°"
    KeyframeProperty.BLUR -> "${String.format("%.1f", value)}px"
    else -> "${(value * 100).roundToInt()}%"
}
