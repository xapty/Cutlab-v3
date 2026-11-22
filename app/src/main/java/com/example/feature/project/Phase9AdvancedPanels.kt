package com.example.feature.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.core.media.ProxyJobState
import com.example.engine.animation.TrackedMotionPoint
import com.example.engine.project.ChromaKeySpec
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.LutPreset
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.TimelineClipRef
import com.example.ui.theme.LocalStudioColors
import kotlin.math.roundToInt

private data class ChromaSwatchOption(
    val id: String,
    val label: String,
    val argb: Long,
)

private val CHROMA_KEY_SWATCHES = listOf(
    ChromaSwatchOption("green", "Green", 0xFF00D84AL),
    ChromaSwatchOption("blue", "Blue", 0xFF0055FFL),
    ChromaSwatchOption("cyan", "Cyan", 0xFF00C2D1L),
    ChromaSwatchOption("magenta", "Magenta", 0xFFD946EFL),
    ChromaSwatchOption("red", "Red", 0xFFEF4444L),
)

@Composable
fun ChromaKeyToolPanel(
    clip: TimelineClipRef?,
    selectedAsset: MediaAsset?,
    onUpdateChromaKey: (ChromaKeySpec) -> Unit,
    onPreviewChromaKey: (ChromaKeySpec) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
) {
    if (clip == null || clip.clipKind != ClipKind.MEDIA || selectedAsset?.type == MediaType.AUDIO) {
        EmptyStudioState(
            icon = Icons.Default.Colorize,
            title = "Select a Video or Photo Clip",
            subtitle = "Select a video or overlay image clip to key out green screen or color backgrounds.",
        )
        return
    }
    val spec = clip.chromaKey

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
                        imageVector = Icons.Default.Colorize,
                        contentDescription = null,
                        tint = Color(spec.keyColorArgb.toInt()),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Chroma Key (YCbCr)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (spec.enabled) {
                        TextButton(
                            onClick = { onUpdateChromaKey(ChromaKeySpec()) },
                            modifier = Modifier.testTag("chroma_key_reset_button"),
                        ) {
                            Text("Reset")
                        }
                    }
                    Switch(
                        checked = spec.enabled,
                        onCheckedChange = { enabled ->
                            onUpdateChromaKey(spec.copy(enabled = enabled))
                        },
                        modifier = Modifier.testTag("chroma_key_enable_switch"),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CHROMA_KEY_SWATCHES.forEach { swatch ->
                    val selected = spec.keyColorArgb == swatch.argb
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onUpdateChromaKey(spec.copy(enabled = true, keyColorArgb = swatch.argb))
                            }
                            .testTag("chroma_color_${swatch.id}"),
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            LocalStudioColors.current.elevatedPanel
                        },
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else LocalStudioColors.current.hairlineBorder,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch.argb.toInt()))
                                    .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                            )
                            Text(
                                text = swatch.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (spec.enabled) {
                Phase9Slider(
                    label = "Color Similarity",
                    valueText = "${(spec.similarity * 100).roundToInt()}%",
                    value = spec.similarity,
                    valueRange = 0.05f..0.75f,
                    onValueChange = { onPreviewChromaKey(spec.copy(similarity = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Chroma Similarity") },
                )
                Phase9Slider(
                    label = "Edge Smoothness",
                    valueText = "${(spec.smoothness * 100).roundToInt()}%",
                    value = spec.smoothness,
                    valueRange = 0.02f..0.45f,
                    onValueChange = { onPreviewChromaKey(spec.copy(smoothness = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Chroma Smoothness") },
                )
                Phase9Slider(
                    label = "Spill Suppression",
                    valueText = "${(spec.spillSuppression * 100).roundToInt()}%",
                    value = spec.spillSuppression,
                    valueRange = 0f..1f,
                    onValueChange = { onPreviewChromaKey(spec.copy(spillSuppression = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Chroma Spill") },
                )
            }
        }
    }
}

@Composable
fun LutGradingToolPanel(
    clip: TimelineClipRef?,
    selectedAsset: MediaAsset?,
    onUpdateLut: (ClipLutSpec) -> Unit,
    onPreviewLut: (ClipLutSpec) -> Unit,
    onImportCubeLut: () -> Unit,
    onCommitSliderEdit: (String) -> Unit,
) {
    val canApplyLut = clip != null &&
        (clip.clipKind == ClipKind.ADJUSTMENT ||
            (clip.clipKind == ClipKind.MEDIA && selectedAsset?.type != MediaType.AUDIO))
    if (clip == null || !canApplyLut) {
        EmptyStudioState(
            icon = Icons.Default.Palette,
            title = "Select a Visual or Adj Clip",
            subtitle = "Apply cinema 3D LUTs or import Adobe .cube color grading files onto video, photo, or adjustment layers.",
        )
        return
    }
    val lut = clip.lutSpec

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
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "3D LUT Color Grade",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (lut.preset != LutPreset.NONE) {
                        TextButton(
                            onClick = { onUpdateLut(ClipLutSpec()) },
                            modifier = Modifier.testTag("lut_reset_button"),
                        ) {
                            Text("Clear")
                        }
                    }
                    OutlinedButton(
                        onClick = onImportCubeLut,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("lut_import_cube_button"),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import .cube")
                    }
                }
            }

            if (!lut.customCubeName.isNullOrBlank()) {
                StudioBadge(
                    text = "CUBE: ${lut.customCubeName}",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LutPreset.entries
                    .filter { it != LutPreset.CUSTOM_CUBE || !lut.customCubePath.isNullOrBlank() }
                    .forEach { preset ->
                        FilterChip(
                            selected = lut.preset == preset,
                            onClick = {
                                onUpdateLut(
                                    lut.copy(
                                        preset = preset,
                                        intensity = if (lut.intensity <= 0.05f) 0.85f else lut.intensity,
                                    ),
                                )
                            },
                            label = { Text(preset.label) },
                            modifier = Modifier.testTag("lut_preset_${preset.name.lowercase()}"),
                        )
                    }
            }

            if (lut.preset != LutPreset.NONE) {
                Phase9Slider(
                    label = "LUT Intensity",
                    valueText = "${(lut.intensity * 100).roundToInt()}%",
                    value = lut.intensity,
                    valueRange = 0f..1f,
                    onValueChange = { onPreviewLut(lut.copy(intensity = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("3D LUT Intensity") },
                )
            }
        }
    }
}

@Composable
fun StabilizationAndTrackToolPanel(
    clip: TimelineClipRef?,
    selectedAsset: MediaAsset?,
    isMotionAnalysisRunning: Boolean,
    lastTrackedPoints: List<TrackedMotionPoint>,
    onRunMotionTrack: (Float, Float) -> Unit,
    onRunStabilization: (Float) -> Unit,
    onDisableStabilization: () -> Unit,
) {
    if (clip == null || clip.clipKind != ClipKind.MEDIA || selectedAsset?.type != MediaType.VIDEO) {
        EmptyStudioState(
            icon = Icons.Default.CenterFocusStrong,
            title = "Select a Video Clip",
            subtitle = "Optical SAD motion tracking and camera stabilization analyze real sampled frames from a video clip.",
        )
        return
    }
    var targetX by rememberSaveable(clip.clipId) { mutableFloatStateOf(0.5f) }
    var targetY by rememberSaveable(clip.clipId) { mutableFloatStateOf(0.5f) }
    var stabStrength by rememberSaveable(clip.clipId) {
        mutableFloatStateOf(clip.stabilization.strength.coerceIn(0.15f, 1.0f))
    }
    val studio = LocalStudioColors.current

    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Stabilize & Motion Track",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (clip.stabilization.enabled) {
                    StudioBadge(
                        text = "Crop ${String.format("%.2fx", clip.stabilization.cropScale)}",
                        containerColor = studio.readyEmerald.copy(alpha = 0.2f),
                        contentColor = studio.readyEmerald,
                    )
                }
            }

            if (isMotionAnalysisRunning) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Analyzing optical video frames...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Camera Stabilization
            Phase9Slider(
                label = "Stabilization Smoothness",
                valueText = "${(stabStrength * 100).roundToInt()}%",
                value = stabStrength,
                valueRange = 0.15f..1.0f,
                onValueChange = { stabStrength = it },
                onValueChangeFinished = {},
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onRunStabilization(stabStrength) },
                    enabled = !isMotionAnalysisRunning,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("stabilize_clip_button"),
                ) {
                    Text(if (clip.stabilization.enabled) "Re-Stabilize" else "Stabilize Clip")
                }
                if (clip.stabilization.enabled) {
                    OutlinedButton(
                        onClick = onDisableStabilization,
                        enabled = !isMotionAnalysisRunning,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("clear_stabilize_button"),
                    ) {
                        Text("Clear")
                    }
                }
            }

            // Optical Patch Tracker
            Text(
                text = "Tap Crosshair Target for Motion Tracking",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(studio.elevatedPanel)
                    .border(1.dp, studio.hairlineBorder, RoundedCornerShape(10.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (size.width > 0 && size.height > 0) {
                                targetX = (offset.x / size.width.toFloat()).coerceIn(0.1f, 0.9f)
                                targetY = (offset.y / size.height.toFloat()).coerceIn(0.1f, 0.9f)
                            }
                        }
                    }
                    .testTag("motion_tracker_reticle_pad"),
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Rule-of-thirds guides
                    drawLine(
                        color = Color.White.copy(alpha = 0.1f),
                        start = Offset(size.width / 3f, 0f),
                        end = Offset(size.width / 3f, size.height),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.1f),
                        start = Offset(size.width * 2f / 3f, 0f),
                        end = Offset(size.width * 2f / 3f, size.height),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        color = Color.White.copy(alpha = 0.1f),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1f,
                    )
                    val cx = targetX * size.width
                    val cy = targetY * size.height
                    drawCircle(
                        color = primaryColor,
                        radius = 16.dp.toPx(),
                        center = Offset(cx, cy),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = 3.dp.toPx(),
                        center = Offset(cx, cy),
                    )
                    drawLine(
                        color = primaryColor.copy(alpha = 0.7f),
                        start = Offset(cx - 24.dp.toPx(), cy),
                        end = Offset(cx + 24.dp.toPx(), cy),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawLine(
                        color = primaryColor.copy(alpha = 0.7f),
                        start = Offset(cx, cy - 24.dp.toPx()),
                        end = Offset(cx, cy + 24.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
                Text(
                    text = "ROI (${(targetX * 100).roundToInt()}%, ${(targetY * 100).roundToInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                )
            }

            Button(
                onClick = { onRunMotionTrack(targetX, targetY) },
                enabled = !isMotionAnalysisRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("run_motion_track_button"),
            ) {
                Text("Track Object → Position Keyframes")
            }

            if (lastTrackedPoints.isNotEmpty()) {
                val avgConf = (lastTrackedPoints.map { it.confidence }.average() * 100.0).roundToInt()
                Text(
                    text = "Last track: ${lastTrackedPoints.size} keyframes generated • Avg confidence $avgConf%",
                    style = MaterialTheme.typography.labelSmall,
                    color = studio.readyEmerald,
                )
            }
        }
    }
}

@Composable
fun ProxyMediaPanel(
    selectedAsset: MediaAsset?,
    preferProxyPlayback: Boolean,
    proxyJobState: ProxyJobState,
    onSetPreferProxyPlayback: (Boolean) -> Unit,
    onGenerateProxy: () -> Unit,
    onCancelProxy: () -> Unit,
    onDeleteProxy: () -> Unit,
) {
    val studio = LocalStudioColors.current
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
                        imageVector = Icons.Default.SdStorage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "540p Proxy Media Workflow",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Switch(
                    checked = preferProxyPlayback,
                    onCheckedChange = onSetPreferProxyPlayback,
                    modifier = Modifier.testTag("workspace_prefer_proxy_switch"),
                )
            }

            Text(
                text = "Preview uses 540p H.264 proxies when enabled for smooth scrubbing; Exports always use full-resolution originals.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selectedAsset == null || selectedAsset.type != MediaType.VIDEO) {
                Text(
                    text = "Select a video clip on the timeline to build or manage its 540p proxy.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${selectedAsset.displayName} (${selectedAsset.resolutionLabel.ifBlank { "Video" }})",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StudioBadge(
                        text = if (selectedAsset.hasReadyProxy) "PROXY 540P" else "FULL-RES ONLY",
                        containerColor = if (selectedAsset.hasReadyProxy) {
                            studio.readyEmerald.copy(alpha = 0.2f)
                        } else {
                            studio.elevatedPanel
                        },
                        contentColor = if (selectedAsset.hasReadyProxy) {
                            studio.readyEmerald
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                when (proxyJobState) {
                    is ProxyJobState.Running -> {
                        LinearProgressIndicator(
                            progress = { proxyJobState.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Generating proxy ${(proxyJobState.progress * 100).roundToInt()}%...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(
                                onClick = onCancelProxy,
                                modifier = Modifier.testTag("cancel_proxy_button"),
                            ) {
                                Text("Cancel")
                            }
                        }
                    }

                    is ProxyJobState.Failed -> {
                        Text(
                            text = proxyJobState.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> Unit
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onGenerateProxy,
                        enabled = proxyJobState !is ProxyJobState.Running,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("generate_proxy_button"),
                    ) {
                        Text(if (selectedAsset.hasReadyProxy) "Rebuild 540p Proxy" else "Generate 540p Proxy")
                    }
                    if (selectedAsset.hasReadyProxy) {
                        OutlinedButton(
                            onClick = onDeleteProxy,
                            enabled = proxyJobState !is ProxyJobState.Running,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("delete_proxy_button"),
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Phase9Slider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.height(34.dp),
        )
    }
}
