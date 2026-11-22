package com.example.feature.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.StudioPanel
import com.example.core.media.ProxyJobState
import com.example.engine.animation.TrackedMotionPoint
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ChromaKeySpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MaskShape
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ShaderEffectType
import com.example.engine.project.TimelineClipRef
import com.example.ui.theme.LocalStudioColors
import kotlin.math.roundToInt

@Composable
fun CompositorOverviewContent(
    selectedClip: TimelineClipRef?,
    selectedAsset: MediaAsset?,
    preferProxyPlayback: Boolean = true,
    proxyJobState: ProxyJobState = ProxyJobState.Idle,
    isMotionAnalysisRunning: Boolean = false,
    lastTrackedPoints: List<TrackedMotionPoint> = emptyList(),
    onAddAdjustmentClip: () -> Unit,
    onUpdateTransform: (LayerTransform) -> Unit,
    onPreviewTransform: (LayerTransform) -> Unit,
    onUpdateBlendMode: (BlendModePreset) -> Unit,
    onUpdateMask: (LayerMask) -> Unit,
    onPreviewMask: (LayerMask) -> Unit,
    onUpdateShaderEffect: (ClipShaderEffect) -> Unit,
    onPreviewShaderEffect: (ClipShaderEffect) -> Unit,
    onUpdateAdjustments: (ClipAdjustments) -> Unit,
    onPreviewAdjustments: (ClipAdjustments) -> Unit,
    onApplyFilterToAll: () -> Unit,
    onUpdateChromaKey: (ChromaKeySpec) -> Unit = {},
    onPreviewChromaKey: (ChromaKeySpec) -> Unit = {},
    onUpdateLut: (ClipLutSpec) -> Unit = {},
    onPreviewLut: (ClipLutSpec) -> Unit = {},
    onImportCubeLut: () -> Unit = {},
    onRunMotionTrack: (Float, Float) -> Unit = { _, _ -> },
    onRunStabilization: (Float) -> Unit = {},
    onDisableStabilization: () -> Unit = {},
    onSetPreferProxyPlayback: (Boolean) -> Unit = {},
    onGenerateProxy: () -> Unit = {},
    onCancelProxy: () -> Unit = {},
    onDeleteProxy: () -> Unit = {},
    onCommitSliderEdit: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AdjustmentLayerToolPanel(
            selectedClip = selectedClip,
            onAddAdjustmentClip = onAddAdjustmentClip,
        )

        if (selectedClip != null && selectedClip.clipKind == ClipKind.ADJUSTMENT) {
            FilterToolPanel(
                clip = selectedClip,
                onUpdateAdjustments = onUpdateAdjustments,
                onPreviewAdjustments = onPreviewAdjustments,
                onCommitSliderEdit = onCommitSliderEdit,
                onApplyFilterToAll = onApplyFilterToAll,
            )
            LutGradingToolPanel(
                clip = selectedClip,
                selectedAsset = selectedAsset,
                onUpdateLut = onUpdateLut,
                onPreviewLut = onPreviewLut,
                onImportCubeLut = onImportCubeLut,
                onCommitSliderEdit = onCommitSliderEdit,
            )
            ShaderToolPanel(
                clip = selectedClip,
                onUpdateShaderEffect = onUpdateShaderEffect,
                onPreviewShaderEffect = onPreviewShaderEffect,
                onCommitSliderEdit = onCommitSliderEdit,
            )
        } else if (selectedClip != null && selectedAsset?.type != MediaType.AUDIO) {
            TransformToolPanel(
                clip = selectedClip,
                onUpdateTransform = onUpdateTransform,
                onPreviewTransform = onPreviewTransform,
                onCommitSliderEdit = onCommitSliderEdit,
            )
            BlendAndMaskToolPanel(
                clip = selectedClip,
                onUpdateBlendMode = onUpdateBlendMode,
                onUpdateMask = onUpdateMask,
                onPreviewMask = onPreviewMask,
                onCommitSliderEdit = onCommitSliderEdit,
            )
            if (selectedClip.clipKind == ClipKind.MEDIA) {
                ChromaKeyToolPanel(
                    clip = selectedClip,
                    selectedAsset = selectedAsset,
                    onUpdateChromaKey = onUpdateChromaKey,
                    onPreviewChromaKey = onPreviewChromaKey,
                    onCommitSliderEdit = onCommitSliderEdit,
                )
                LutGradingToolPanel(
                    clip = selectedClip,
                    selectedAsset = selectedAsset,
                    onUpdateLut = onUpdateLut,
                    onPreviewLut = onPreviewLut,
                    onImportCubeLut = onImportCubeLut,
                    onCommitSliderEdit = onCommitSliderEdit,
                )
                ShaderToolPanel(
                    clip = selectedClip,
                    onUpdateShaderEffect = onUpdateShaderEffect,
                    onPreviewShaderEffect = onPreviewShaderEffect,
                    onCommitSliderEdit = onCommitSliderEdit,
                )
                if (selectedAsset?.type == MediaType.VIDEO) {
                    StabilizationAndTrackToolPanel(
                        clip = selectedClip,
                        selectedAsset = selectedAsset,
                        isMotionAnalysisRunning = isMotionAnalysisRunning,
                        lastTrackedPoints = lastTrackedPoints,
                        onRunMotionTrack = onRunMotionTrack,
                        onRunStabilization = onRunStabilization,
                        onDisableStabilization = onDisableStabilization,
                    )
                }
            }
        } else {
            EmptyStudioState(
                icon = Icons.Default.Layers,
                title = "Select a Visual, Text, or Adj Clip",
                subtitle = "Select a visual or text clip to edit transform, blend mode, mask, LUTs, chroma key, or GLSL shaders, or add an Adjustment Layer above.",
            )
        }

        ProxyMediaPanel(
            selectedAsset = selectedAsset,
            preferProxyPlayback = preferProxyPlayback,
            proxyJobState = proxyJobState,
            onSetPreferProxyPlayback = onSetPreferProxyPlayback,
            onGenerateProxy = onGenerateProxy,
            onCancelProxy = onCancelProxy,
            onDeleteProxy = onDeleteProxy,
        )
    }
}

@Composable
fun AdjustmentLayerToolPanel(
    selectedClip: TimelineClipRef?,
    onAddAdjustmentClip: () -> Unit,
) {
    val studio = LocalStudioColors.current
    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = studio.readyEmerald,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Adjustment Layer",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Button(
                    onClick = onAddAdjustmentClip,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("add_adjustment_clip_button"),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Adj")
                }
            }
            Text(
                text = if (selectedClip?.clipKind == ClipKind.ADJUSTMENT) {
                    "Adjustment clip selected: any Filter, Color Grade, or GLSL Shader below cascades to all visual layers underneath."
                } else {
                    "Adjustment clips apply unified color grading and GPU shader effects across all lower video & overlay tracks."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TransformToolPanel(
    clip: TimelineClipRef?,
    onUpdateTransform: (LayerTransform) -> Unit,
    onPreviewTransform: (LayerTransform) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
) {
    if (clip == null || clip.clipKind == ClipKind.ADJUSTMENT) {
        EmptyStudioState(
            icon = Icons.Default.OpenWith,
            title = "Select a Visual or Text Clip",
            subtitle = "Tap a video, photo, or text clip to adjust position, scale, rotation, and opacity.",
        )
        return
    }
    val t = clip.transform

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
                        imageVector = Icons.Default.OpenWith,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Layer Transform",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = t.uniformScale,
                        onClick = { onUpdateTransform(t.copy(uniformScale = !t.uniformScale, scaleY = t.scaleX)) },
                        label = { Text("Lock XY") },
                    )
                    TextButton(
                        onClick = { onUpdateTransform(LayerTransform()) },
                        enabled = !t.isDefault,
                        modifier = Modifier.testTag("reset_transform_button"),
                    ) {
                        Text("Reset")
                    }
                }
            }

            CompositorSlider(
                label = "Position X",
                valueText = "${(t.positionX * 100).roundToInt()}%",
                value = t.positionX,
                valueRange = -0.8f..0.8f,
                onValueChange = { onPreviewTransform(t.copy(positionX = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Transform Position X") },
            )
            CompositorSlider(
                label = "Position Y",
                valueText = "${(t.positionY * 100).roundToInt()}%",
                value = t.positionY,
                valueRange = -0.8f..0.8f,
                onValueChange = { onPreviewTransform(t.copy(positionY = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Transform Position Y") },
            )

            if (t.uniformScale) {
                CompositorSlider(
                    label = "Scale",
                    valueText = "${String.format("%.2fx", t.scaleX)}",
                    value = t.scaleX,
                    valueRange = 0.2f..2.5f,
                    onValueChange = { onPreviewTransform(t.copy(scaleX = it, scaleY = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Transform Scale") },
                )
            } else {
                CompositorSlider(
                    label = "Scale X",
                    valueText = "${String.format("%.2fx", t.scaleX)}",
                    value = t.scaleX,
                    valueRange = 0.2f..2.5f,
                    onValueChange = { onPreviewTransform(t.copy(scaleX = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Transform Scale X") },
                )
                CompositorSlider(
                    label = "Scale Y",
                    valueText = "${String.format("%.2fx", t.scaleY)}",
                    value = t.scaleY,
                    valueRange = 0.2f..2.5f,
                    onValueChange = { onPreviewTransform(t.copy(scaleY = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Transform Scale Y") },
                )
            }

            CompositorSlider(
                label = "Rotation",
                valueText = "${t.rotationDeg.roundToInt()}°",
                value = t.rotationDeg,
                valueRange = -180f..180f,
                onValueChange = { onPreviewTransform(t.copy(rotationDeg = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Transform Rotation") },
            )
            CompositorSlider(
                label = "Opacity",
                valueText = "${(t.opacity * 100).roundToInt()}%",
                value = t.opacity,
                valueRange = 0f..1f,
                onValueChange = { onPreviewTransform(t.copy(opacity = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Layer Opacity") },
            )
        }
    }
}

@Composable
fun BlendAndMaskToolPanel(
    clip: TimelineClipRef?,
    onUpdateBlendMode: (BlendModePreset) -> Unit,
    onUpdateMask: (LayerMask) -> Unit,
    onPreviewMask: (LayerMask) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
) {
    if (clip == null || clip.clipKind == ClipKind.ADJUSTMENT) {
        EmptyStudioState(
            icon = Icons.Default.CropFree,
            title = "Select a Visual or Text Clip",
            subtitle = "Select an overlay, video, photo, or text clip to configure blending modes and SDF masks.",
        )
        return
    }
    val mask = clip.mask

    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CropFree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Blend Mode",
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
                BlendModePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = clip.blendMode == preset,
                        onClick = { onUpdateBlendMode(preset) },
                        label = { Text(preset.label) },
                        modifier = Modifier.testTag("blend_mode_${preset.name.lowercase()}"),
                    )
                }
            }

            if (clip.clipKind == ClipKind.MEDIA) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "GPU Alpha Mask",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (mask.isActive) {
                        TextButton(
                            onClick = { onUpdateMask(LayerMask()) },
                            modifier = Modifier.testTag("reset_mask_button"),
                        ) {
                            Text("Clear Mask")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MaskShape.entries.forEach { shape ->
                        FilterChip(
                            selected = mask.shape == shape,
                            onClick = { onUpdateMask(mask.copy(shape = shape)) },
                            label = { Text(shape.label) },
                            modifier = Modifier.testTag("mask_shape_${shape.name.lowercase()}"),
                        )
                    }
                }

                if (mask.isActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Invert Mask",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = mask.inverted,
                            onCheckedChange = { onUpdateMask(mask.copy(inverted = it)) },
                            modifier = Modifier.testTag("mask_invert_switch"),
                        )
                    }

                    CompositorSlider(
                        label = "Center X",
                        valueText = "${(mask.centerX * 100).roundToInt()}%",
                        value = mask.centerX,
                        valueRange = 0f..1f,
                        onValueChange = { onPreviewMask(mask.copy(centerX = it)) },
                        onValueChangeFinished = { onCommitSliderEdit("Mask Center X") },
                    )
                    CompositorSlider(
                        label = "Center Y",
                        valueText = "${(mask.centerY * 100).roundToInt()}%",
                        value = mask.centerY,
                        valueRange = 0f..1f,
                        onValueChange = { onPreviewMask(mask.copy(centerY = it)) },
                        onValueChangeFinished = { onCommitSliderEdit("Mask Center Y") },
                    )
                    if (mask.shape != MaskShape.LINEAR_SPLIT) {
                        CompositorSlider(
                            label = "Mask Width",
                            valueText = "${(mask.width * 100).roundToInt()}%",
                            value = mask.width,
                            valueRange = 0.1f..1.4f,
                            onValueChange = { onPreviewMask(mask.copy(width = it)) },
                            onValueChangeFinished = { onCommitSliderEdit("Mask Width") },
                        )
                        CompositorSlider(
                            label = "Mask Height",
                            valueText = "${(mask.height * 100).roundToInt()}%",
                            value = mask.height,
                            valueRange = 0.1f..1.4f,
                            onValueChange = { onPreviewMask(mask.copy(height = it)) },
                            onValueChangeFinished = { onCommitSliderEdit("Mask Height") },
                        )
                    }
                    CompositorSlider(
                        label = "Mask Rotation",
                        valueText = "${mask.rotationDeg.roundToInt()}°",
                        value = mask.rotationDeg,
                        valueRange = -180f..180f,
                        onValueChange = { onPreviewMask(mask.copy(rotationDeg = it)) },
                        onValueChangeFinished = { onCommitSliderEdit("Mask Rotation") },
                    )
                    CompositorSlider(
                        label = "Feather",
                        valueText = "${(mask.feather * 100).roundToInt()}%",
                        value = mask.feather,
                        valueRange = 0.005f..0.4f,
                        onValueChange = { onPreviewMask(mask.copy(feather = it)) },
                        onValueChangeFinished = { onCommitSliderEdit("Mask Feather") },
                    )
                    if (mask.shape == MaskShape.RECTANGLE) {
                        CompositorSlider(
                            label = "Corner Radius",
                            valueText = "${(mask.cornerRadius * 100).roundToInt()}%",
                            value = mask.cornerRadius,
                            valueRange = 0f..0.3f,
                            onValueChange = { onPreviewMask(mask.copy(cornerRadius = it)) },
                            onValueChangeFinished = { onCommitSliderEdit("Mask Corners") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShaderToolPanel(
    clip: TimelineClipRef?,
    onUpdateShaderEffect: (ClipShaderEffect) -> Unit,
    onPreviewShaderEffect: (ClipShaderEffect) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
) {
    if (clip == null || clip.clipKind == ClipKind.TEXT) {
        EmptyStudioState(
            icon = Icons.Default.BlurOn,
            title = "Select a Visual or Adjustment Clip",
            subtitle = "Tap a video, photo, or adjustment clip to apply real-time GLSL fragment shader effects.",
        )
        return
    }
    val fx = clip.shaderEffect

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
                        imageVector = Icons.Default.BlurOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "GLSL Shader FX",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (fx.isActive) {
                    TextButton(
                        onClick = { onUpdateShaderEffect(ClipShaderEffect()) },
                        modifier = Modifier.testTag("reset_shader_button"),
                    ) {
                        Text("Clear")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShaderEffectType.entries.forEach { shader ->
                    FilterChip(
                        selected = fx.type == shader,
                        onClick = { onUpdateShaderEffect(fx.copy(type = shader)) },
                        label = { Text(shader.label) },
                        modifier = Modifier.testTag("shader_fx_${shader.name.lowercase()}"),
                    )
                }
            }

            if (fx.type != ShaderEffectType.NONE) {
                CompositorSlider(
                    label = "Shader Intensity",
                    valueText = "${(fx.intensity * 100).roundToInt()}%",
                    value = fx.intensity,
                    valueRange = 0f..1f,
                    onValueChange = { onPreviewShaderEffect(fx.copy(intensity = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Shader Intensity") },
                )
            }
        }
    }
}

@Composable
private fun CompositorSlider(
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
