package com.example.feature.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.StudioPanel
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipTransition
import com.example.engine.project.EasingPreset
import com.example.engine.project.FilterPreset
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.SpeedPoint
import com.example.engine.project.SpeedRampPreset
import com.example.engine.project.TextAlignMode
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TransitionType
import com.example.engine.timeline.SpeedRampEngine
import com.example.ui.theme.LocalStudioColors
import kotlin.math.abs
import kotlin.math.roundToLong

enum class QuickToolSheet {
    NONE,
    TEXT,
    FILTERS,
    TRANSITIONS,
    SPEED,
    AUDIO,
    RATIO,
    TRANSFORM,
    BLEND_MASK,
    SHADER_FX,
    ANIMATION,
    CHROMA_KEY,
    LUT_GRADE,
    STABILIZE_TRACK,
    PROXY_MEDIA,
}

@Composable
fun QuickToolsOverviewContent(
    project: ProjectDocument,
    selectedClip: TimelineClipRef?,
    selectedAsset: MediaAsset?,
    onAddTextClip: (TextStylePreset) -> Unit,
    onUpdateTextSpec: (TextOverlaySpec) -> Unit,
    onPreviewTextSpec: (TextOverlaySpec) -> Unit,
    onUpdateAdjustments: (ClipAdjustments) -> Unit,
    onPreviewAdjustments: (ClipAdjustments) -> Unit,
    onApplyFilterToAll: () -> Unit,
    onUpdateTransition: (ClipTransition?) -> Unit,
    onPreviewTransition: (ClipTransition?) -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onUpdateSpeedRamp: (SpeedRampPreset, List<SpeedPoint>) -> Unit = { _, _ -> },
    onToggleReverse: () -> Unit = {},
    onUpdateAudio: (Float, Boolean, Long, Long) -> Unit,
    onPreviewAudio: (Float, Boolean, Long, Long) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
    onUpdateCanvas: (AspectRatioPreset, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextToolPanel(
            selectedClip = selectedClip,
            onAddTextClip = onAddTextClip,
            onUpdateTextSpec = onUpdateTextSpec,
            onPreviewTextSpec = onPreviewTextSpec,
            onCommitSliderEdit = onCommitSliderEdit,
        )

        if (selectedClip != null &&
            (selectedClip.clipKind == ClipKind.ADJUSTMENT ||
                (selectedClip.clipKind == ClipKind.MEDIA && selectedAsset?.type != MediaType.AUDIO))
        ) {
            FilterToolPanel(
                clip = selectedClip,
                onUpdateAdjustments = onUpdateAdjustments,
                onPreviewAdjustments = onPreviewAdjustments,
                onCommitSliderEdit = onCommitSliderEdit,
                onApplyFilterToAll = onApplyFilterToAll,
            )
        }
        if (selectedClip != null && selectedClip.clipKind == ClipKind.MEDIA && selectedAsset?.type != MediaType.AUDIO) {
            TransitionToolPanel(
                clip = selectedClip,
                onUpdateTransition = onUpdateTransition,
                onPreviewTransition = onPreviewTransition,
                onCommitSliderEdit = onCommitSliderEdit,
            )
        }

        if (selectedClip != null && selectedClip.clipKind == ClipKind.MEDIA && selectedAsset?.type != MediaType.IMAGE) {
            SpeedToolPanel(
                clip = selectedClip,
                onUpdateSpeed = onUpdateSpeed,
                onUpdateSpeedRamp = onUpdateSpeedRamp,
                onToggleReverse = onToggleReverse,
            )
            AudioToolPanel(
                clip = selectedClip,
                onUpdateAudio = onUpdateAudio,
                onPreviewAudio = onPreviewAudio,
                onCommitSliderEdit = onCommitSliderEdit,
            )
        }

        RatioToolPanel(
            project = project,
            onUpdateCanvas = onUpdateCanvas,
        )
    }
}

@Composable
fun TextToolPanel(
    selectedClip: TimelineClipRef?,
    onAddTextClip: (TextStylePreset) -> Unit,
    onUpdateTextSpec: (TextOverlaySpec) -> Unit,
    onPreviewTextSpec: (TextOverlaySpec) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
) {
    val textClip = selectedClip?.takeIf { it.clipKind == ClipKind.TEXT }
    val spec = textClip?.textSpec

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
                        imageVector = Icons.Default.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Text & Caption Presets",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Button(
                    onClick = { onAddTextClip(TextStylePreset.BOLD_TITLE) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("add_text_clip_button"),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Text")
                }
            }

            // Quick add preset row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextStylePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = spec?.stylePreset == preset,
                        onClick = {
                            if (spec != null) {
                                val templated = EffectsEngine.defaultTextSpec(preset).copy(
                                    text = spec.text,
                                )
                                onUpdateTextSpec(templated)
                            } else {
                                onAddTextClip(preset)
                            }
                        },
                        label = { Text(preset.label) },
                        modifier = Modifier.testTag("text_preset_${preset.name.lowercase()}"),
                    )
                }
            }

            if (spec != null) {
                OutlinedTextField(
                    value = spec.text,
                    onValueChange = { onPreviewTextSpec(spec.copy(text = it)) },
                    label = { Text("Caption Text") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("text_overlay_input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onCommitSliderEdit("Edit Text") },
                        modifier = Modifier.testTag("text_apply_button"),
                    ) {
                        Text("Commit Text Edit")
                    }
                }

                Text(
                    text = "Animation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextAnimationPreset.entries.forEach { anim ->
                        FilterChip(
                            selected = spec.animationPreset == anim,
                            onClick = { onUpdateTextSpec(spec.copy(animationPreset = anim)) },
                            label = { Text(anim.label) },
                            modifier = Modifier.testTag("text_anim_${anim.name.lowercase()}"),
                        )
                    }
                }

                Text(
                    text = "Text Color & Pill Background",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val textSwatches = listOf(
                    0xFFFFFFFFL, 0xFF0E1118L, 0xFF38BDF8L, 0xFFF59E0BL,
                    0xFFF43F5EL, 0xFF34D399L, 0xFFA78BFAL, 0xFFFDE68AL,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    textSwatches.forEach { argb ->
                        val selected = spec.textColorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(argb.toInt()))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else LocalStudioColors.current.hairlineBorder,
                                    shape = CircleShape,
                                )
                                .clickable { onUpdateTextSpec(spec.copy(textColorArgb = argb)) },
                        )
                    }
                    FilterChip(
                        selected = spec.backgroundColorArgb != 0x00000000L,
                        onClick = {
                            val nextBg = if (spec.backgroundColorArgb == 0x00000000L) {
                                0xAA0B0E14L
                            } else {
                                0x00000000L
                            }
                            onUpdateTextSpec(spec.copy(backgroundColorArgb = nextBg))
                        },
                        label = { Text("Pill BG") },
                    )
                    FilterChip(
                        selected = spec.hasStroke,
                        onClick = { onUpdateTextSpec(spec.copy(hasStroke = !spec.hasStroke)) },
                        label = { Text("Outline") },
                    )
                }

                LabeledStudioSlider(
                    label = "Font Scale",
                    valueText = "${String.format("%.1fx", spec.fontScale)}",
                    value = spec.fontScale,
                    valueRange = 0.5f..2.4f,
                    onValueChange = { onPreviewTextSpec(spec.copy(fontScale = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Resize Text") },
                )

                LabeledStudioSlider(
                    label = "Vertical Position",
                    valueText = "${(spec.offsetY * 100).toInt()}%",
                    value = spec.offsetY,
                    valueRange = -0.40f..0.40f,
                    onValueChange = { onPreviewTextSpec(spec.copy(offsetY = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Position Text") },
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextAlignMode.entries.forEach { align ->
                        FilterChip(
                            selected = spec.alignment == align,
                            onClick = { onUpdateTextSpec(spec.copy(alignment = align)) },
                            label = { Text(align.label) },
                        )
                    }
                }
            } else {
                Text(
                    text = "Tap any style preset or Add Text to insert an editable title or caption at the playhead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun FilterToolPanel(
    clip: TimelineClipRef?,
    onUpdateAdjustments: (ClipAdjustments) -> Unit,
    onPreviewAdjustments: (ClipAdjustments) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
    onApplyFilterToAll: () -> Unit,
) {
    if (clip == null || clip.clipKind == ClipKind.TEXT) {
        EmptyStudioState(
            icon = Icons.Default.AutoFixHigh,
            title = "Select a Visual or Adjustment Clip",
            subtitle = "Tap a video, photo, or adjustment clip on the timeline to apply color filters.",
        )
        return
    }
    val adj = clip.adjustments

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
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Filters & Color Grade",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onApplyFilterToAll) {
                        Text("Apply All")
                    }
                    TextButton(
                        onClick = { onUpdateAdjustments(ClipAdjustments()) },
                        enabled = !adj.isDefault,
                    ) {
                        Text("Reset")
                    }
                }
            }

            // Preset swatches
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPreset.entries.forEach { preset ->
                    val selected = adj.filterPreset == preset
                    val swatchColor = Color(EffectsEngine.filterAccentColorArgb(preset).toInt())
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onUpdateAdjustments(adj.copy(filterPreset = preset))
                            }
                            .testTag("filter_preset_${preset.name.lowercase()}"),
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
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor),
                            )
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            LabeledStudioSlider(
                label = "Brightness",
                valueText = "${(adj.brightness * 100).toInt()}%",
                value = adj.brightness,
                valueRange = -0.6f..0.6f,
                onValueChange = { onPreviewAdjustments(adj.copy(brightness = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Adjust Brightness") },
            )
            LabeledStudioSlider(
                label = "Contrast",
                valueText = "${(adj.contrast * 100).toInt()}%",
                value = adj.contrast,
                valueRange = 0.5f..1.8f,
                onValueChange = { onPreviewAdjustments(adj.copy(contrast = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Adjust Contrast") },
            )
            LabeledStudioSlider(
                label = "Saturation",
                valueText = "${(adj.saturation * 100).toInt()}%",
                value = adj.saturation,
                valueRange = 0f..2.0f,
                onValueChange = { onPreviewAdjustments(adj.copy(saturation = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Adjust Saturation") },
            )
            LabeledStudioSlider(
                label = "Temperature",
                valueText = if (adj.temperature >= 0f) "+${(adj.temperature * 100).toInt()}" else "${(adj.temperature * 100).toInt()}",
                value = adj.temperature,
                valueRange = -1f..1f,
                onValueChange = { onPreviewAdjustments(adj.copy(temperature = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Adjust Temperature") },
            )
            LabeledStudioSlider(
                label = "Vignette",
                valueText = "${(adj.vignette * 100).toInt()}%",
                value = adj.vignette,
                valueRange = 0f..1f,
                onValueChange = { onPreviewAdjustments(adj.copy(vignette = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Adjust Vignette") },
            )
            LabeledStudioSlider(
                label = "Soft Blur",
                valueText = "${String.format("%.1f", adj.blurRadius)}",
                value = adj.blurRadius,
                valueRange = 0f..16f,
                onValueChange = { onPreviewAdjustments(adj.copy(blurRadius = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Adjust Blur") },
            )
        }
    }
}

@Composable
fun TransitionToolPanel(
    clip: TimelineClipRef?,
    onUpdateTransition: (ClipTransition?) -> Unit,
    onPreviewTransition: (ClipTransition?) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
) {
    if (clip == null) {
        EmptyStudioState(
            icon = Icons.Default.Tune,
            title = "Select Any Clip",
            subtitle = "Select a timeline clip to configure its entrance transition.",
        )
        return
    }
    val current = clip.transitionIn ?: ClipTransition(type = TransitionType.NONE, durationMs = 500L)

    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Entrance Transition (Clip In)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TransitionType.entries.forEach { type ->
                    val selected = current.type == type
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (type == TransitionType.NONE) {
                                onUpdateTransition(null)
                            } else {
                                onUpdateTransition(
                                    current.copy(
                                        type = type,
                                        durationMs = current.durationMs.coerceIn(200L, clip.durationMs.coerceAtLeast(200L)),
                                    ),
                                )
                            }
                        },
                        label = { Text(type.label) },
                        modifier = Modifier.testTag("transition_type_${type.name.lowercase()}"),
                    )
                }
            }

            if (current.type != TransitionType.NONE) {
                val maxDur = clip.durationMs.coerceIn(200L, 2_000L).toFloat()
                LabeledStudioSlider(
                    label = "Duration",
                    valueText = TimeFormatting.formatDurationLabel(current.durationMs),
                    value = current.durationMs.toFloat().coerceIn(150f, maxDur),
                    valueRange = 150f..maxDur.coerceAtLeast(200f),
                    onValueChange = {
                        onPreviewTransition(current.copy(durationMs = it.roundToLong()))
                    },
                    onValueChangeFinished = { onCommitSliderEdit("Transition Duration") },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    EasingPreset.entries.forEach { easing ->
                        FilterChip(
                            selected = current.easing == easing,
                            onClick = { onUpdateTransition(current.copy(easing = easing)) },
                            label = { Text(easing.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedToolPanel(
    clip: TimelineClipRef?,
    onUpdateSpeed: (Float) -> Unit,
    onUpdateSpeedRamp: (SpeedRampPreset, List<SpeedPoint>) -> Unit = { _, _ -> },
    onToggleReverse: () -> Unit = {},
) {
    if (clip == null) {
        EmptyStudioState(
            icon = Icons.Default.Speed,
            title = "Select a Video or Audio Clip",
            subtitle = "Tap a clip on the timeline to change playback speed, speed curves, or reverse direction.",
        )
        return
    }
    val presets = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)
    val effectivePoints = SpeedRampEngine.effectivePoints(clip)
    val avgSpeed = SpeedRampEngine.averageSpeed(clip)
    val studio = LocalStudioColors.current
    var selectedNodeIndex by rememberSaveable(clip.clipId) { mutableIntStateOf(0) }

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
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Speed, Ramps & Reverse",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "${String.format("%.2gx", avgSpeed)} • ${TimeFormatting.formatDurationLabel(clip.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = clip.isReversed,
                    onClick = onToggleReverse,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Replay,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(if (clip.isReversed) "Reverse Active (REV)" else "Reverse Playback") },
                    modifier = Modifier.testTag("speed_reverse_toggle"),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                presets.forEach { speed ->
                    val selected = clip.speedRampPreset == SpeedRampPreset.NONE &&
                        effectivePoints.isEmpty() &&
                        abs(clip.speed - speed) < 0.03f
                    FilterChip(
                        selected = selected,
                        onClick = { onUpdateSpeed(speed) },
                        label = { Text("${String.format("%.2gx", speed)}") },
                        modifier = Modifier.testTag("speed_preset_${speed}"),
                    )
                }
            }

            Text(
                text = "Speed Ramp Curves",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpeedRampPreset.entries.forEach { rampPreset ->
                    FilterChip(
                        selected = clip.speedRampPreset == rampPreset,
                        onClick = {
                            val pts = SpeedRampEngine.presetPoints(rampPreset)
                            selectedNodeIndex = 1.coerceAtMost((pts.size - 1).coerceAtLeast(0))
                            onUpdateSpeedRamp(rampPreset, pts)
                        },
                        label = { Text(rampPreset.label) },
                        modifier = Modifier.testTag("speed_ramp_preset_${rampPreset.name.lowercase()}"),
                    )
                }
            }

            if (effectivePoints.isNotEmpty()) {
                val curvePoints = effectivePoints
                val primaryColor = MaterialTheme.colorScheme.primary
                val secondaryColor = MaterialTheme.colorScheme.secondary
                val safeNodeIdx = selectedNodeIndex.coerceIn(0, curvePoints.lastIndex)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(studio.elevatedPanel)
                        .border(1.dp, studio.hairlineBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("speed_ramp_curve_canvas"),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val baselineY = size.height * (1f - ((1.0f - SpeedRampEngine.MIN_SPEED) / (SpeedRampEngine.MAX_SPEED - SpeedRampEngine.MIN_SPEED)))
                        drawLine(
                            color = Color.White.copy(alpha = 0.2f),
                            start = Offset(0f, baselineY),
                            end = Offset(size.width, baselineY),
                            strokeWidth = 1.2f,
                        )
                        val path = Path()
                        curvePoints.forEachIndexed { idx, pt ->
                            val px = pt.normalizedTime * size.width
                            val normSpd = ((pt.speed - SpeedRampEngine.MIN_SPEED) / (SpeedRampEngine.MAX_SPEED - SpeedRampEngine.MIN_SPEED)).coerceIn(0f, 1f)
                            val py = size.height * (1f - normSpd)
                            if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(
                            path = path,
                            color = primaryColor,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                        curvePoints.forEachIndexed { idx, pt ->
                            val px = pt.normalizedTime * size.width
                            val normSpd = ((pt.speed - SpeedRampEngine.MIN_SPEED) / (SpeedRampEngine.MAX_SPEED - SpeedRampEngine.MIN_SPEED)).coerceIn(0f, 1f)
                            val py = size.height * (1f - normSpd)
                            drawCircle(
                                color = if (idx == safeNodeIdx) secondaryColor else primaryColor,
                                radius = if (idx == safeNodeIdx) 5.dp.toPx() else 3.5.dp.toPx(),
                                center = Offset(px, py),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    curvePoints.forEachIndexed { idx, pt ->
                        FilterChip(
                            selected = idx == safeNodeIdx,
                            onClick = { selectedNodeIndex = idx },
                            label = {
                                Text("Pt ${idx + 1}: ${String.format("%.2gx", pt.speed)}")
                            },
                            modifier = Modifier.testTag("speed_ramp_node_$idx"),
                        )
                    }
                }

                val activePt = curvePoints[safeNodeIdx]
                LabeledStudioSlider(
                    label = "Node ${safeNodeIdx + 1} Speed",
                    valueText = "${String.format("%.2gx", activePt.speed)}",
                    value = activePt.speed,
                    valueRange = SpeedRampEngine.MIN_SPEED..SpeedRampEngine.MAX_SPEED,
                    onValueChange = { updatedSpd ->
                        val updatedList = curvePoints.mapIndexed { idx, pt ->
                            if (idx == safeNodeIdx) pt.copy(speed = updatedSpd) else pt
                        }
                        onUpdateSpeedRamp(clip.speedRampPreset, updatedList)
                    },
                    onValueChangeFinished = {},
                )
            }
        }
    }
}

@Composable
fun AudioToolPanel(
    clip: TimelineClipRef?,
    onUpdateAudio: (Float, Boolean, Long, Long) -> Unit,
    onPreviewAudio: (Float, Boolean, Long, Long) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
) {
    if (clip == null) {
        EmptyStudioState(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = "Select an Audio or Video Clip",
            subtitle = "Tap a clip to adjust volume, mute, fade in, and fade out.",
        )
        return
    }
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
                        imageVector = if (clip.isMuted) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = null,
                        tint = studio.audioAmber,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Clip Audio & Fades",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (clip.isMuted) "Muted" else "Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = clip.isMuted,
                        onCheckedChange = { muted ->
                            onUpdateAudio(clip.volume, muted, clip.fadeInMs, clip.fadeOutMs)
                        },
                        modifier = Modifier.testTag("clip_mute_switch"),
                    )
                }
            }

            LabeledStudioSlider(
                label = "Volume",
                valueText = "${(clip.volume * 100).toInt()}%",
                value = clip.volume,
                valueRange = 0f..1.5f,
                onValueChange = {
                    onPreviewAudio(it, clip.isMuted, clip.fadeInMs, clip.fadeOutMs)
                },
                onValueChangeFinished = { onCommitSliderEdit("Clip Volume") },
            )

            val maxFade = clip.durationMs.coerceIn(0L, 4_000L).toFloat().coerceAtLeast(100f)
            LabeledStudioSlider(
                label = "Fade In",
                valueText = TimeFormatting.formatDurationLabel(clip.fadeInMs),
                value = clip.fadeInMs.toFloat().coerceIn(0f, maxFade),
                valueRange = 0f..maxFade,
                onValueChange = {
                    onPreviewAudio(clip.volume, clip.isMuted, it.roundToLong(), clip.fadeOutMs)
                },
                onValueChangeFinished = { onCommitSliderEdit("Audio Fade In") },
            )

            LabeledStudioSlider(
                label = "Fade Out",
                valueText = TimeFormatting.formatDurationLabel(clip.fadeOutMs),
                value = clip.fadeOutMs.toFloat().coerceIn(0f, maxFade),
                valueRange = 0f..maxFade,
                onValueChange = {
                    onPreviewAudio(clip.volume, clip.isMuted, clip.fadeInMs, it.roundToLong())
                },
                onValueChangeFinished = { onCommitSliderEdit("Audio Fade Out") },
            )
        }
    }
}

@Composable
fun RatioToolPanel(
    project: ProjectDocument,
    onUpdateCanvas: (AspectRatioPreset, Int) -> Unit,
) {
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
                    imageVector = Icons.Default.AspectRatio,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Social Aspect Ratio",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AspectRatioPreset.entries.forEach { preset ->
                    val selected = project.aspectRatio == preset
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onUpdateCanvas(preset, project.fps) }
                            .testTag("quick_ratio_${preset.name.lowercase()}"),
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
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = preset.tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledStudioSlider(
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
