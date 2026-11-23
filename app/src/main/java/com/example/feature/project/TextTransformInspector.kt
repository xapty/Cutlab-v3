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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.engine.project.ClipKind
import com.example.engine.project.LayerTransform
import com.example.engine.project.TextContentType
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TimelineClipRef
import com.example.engine.text.UnifiedTextEngine
import kotlin.math.abs
import kotlin.math.roundToInt

private data class AnchorPreset(
    val label: String,
    val ax: Float,
    val ay: Float,
)

private val ANCHOR_PRESETS = listOf(
    AnchorPreset("Center", 0.5f, 0.5f),
    AnchorPreset("Top", 0.5f, 0f),
    AnchorPreset("Bottom", 0.5f, 1f),
    AnchorPreset("Left", 0f, 0.5f),
    AnchorPreset("Right", 1f, 0.5f),
    AnchorPreset("Top-L", 0f, 0f),
    AnchorPreset("Bot-R", 1f, 1f),
)

/**
 * Shared transform inspector for ALL [ClipKind.TEXT] clips (Regular Text, Auto Caption, Lyric,
 * Normal & Invert Lyric) and visual media layers across both Quick Edit and Advanced Edit.
 *
 * Writes canonical [LayerTransform] values cleanly and normalizes any legacy `TextOverlaySpec`
 * offsets atomically via [TimelineEngine.updateClipTransform] without altering timed words.
 */
@Composable
fun UniversalTextTransformSection(
    clip: TimelineClipRef?,
    onUpdateTransform: (LayerTransform) -> Unit,
    onPreviewTransform: (LayerTransform) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (clip == null || clip.clipKind == ClipKind.ADJUSTMENT) {
        EmptyStudioState(
            icon = Icons.Default.OpenWith,
            title = "Select a Visual or Text Clip",
            subtitle = "Tap a text, caption, lyric, video, or photo clip to adjust position, scale, rotation, anchor, and opacity.",
        )
        return
    }

    val eff = UnifiedTextEngine.effectiveTransform(clip.textSpec, clip.transform)
    val isText = clip.clipKind == ClipKind.TEXT
    val badgeText = when {
        clip.textSpec?.renderMode == TextRenderMode.INVERT_VIDEO -> "INVERT LYRIC"
        clip.textSpec?.contentType == TextContentType.CAPTION -> "CAPTION"
        clip.textSpec?.contentType == TextContentType.LYRIC -> "LYRIC"
        isText -> "TEXT"
        else -> "LAYER"
    }

    val focusManager = LocalFocusManager.current
    var posXInput by remember(clip.clipId) { mutableStateOf((eff.positionX * 100f).roundToInt().toString()) }
    var posYInput by remember(clip.clipId) { mutableStateOf((eff.positionY * 100f).roundToInt().toString()) }
    var scaleXInput by remember(clip.clipId) { mutableStateOf(String.format("%.2f", eff.scaleX)) }
    var scaleYInput by remember(clip.clipId) { mutableStateOf(String.format("%.2f", eff.scaleY)) }
    var rotInput by remember(clip.clipId) { mutableStateOf(eff.rotationDeg.roundToInt().toString()) }

    LaunchedEffect(eff.positionX, eff.positionY, eff.scaleX, eff.scaleY, eff.rotationDeg) {
        posXInput = (eff.positionX * 100f).roundToInt().toString()
        posYInput = (eff.positionY * 100f).roundToInt().toString()
        scaleXInput = String.format("%.2f", eff.scaleX)
        scaleYInput = String.format("%.2f", eff.scaleY)
        rotInput = eff.rotationDeg.roundToInt().toString()
    }

    fun applyNumericFields() {
        val px = posXInput.toFloatOrNull()?.div(100f)?.coerceIn(-1.5f, 1.5f) ?: eff.positionX
        val py = posYInput.toFloatOrNull()?.div(100f)?.coerceIn(-1.5f, 1.5f) ?: eff.positionY
        val sx = scaleXInput.toFloatOrNull()?.coerceIn(0.1f, 4.0f) ?: eff.scaleX
        val sy = if (eff.uniformScale) {
            sx
        } else {
            scaleYInput.toFloatOrNull()?.coerceIn(0.1f, 4.0f) ?: eff.scaleY
        }
        val rot = rotInput.toFloatOrNull()?.coerceIn(-360f, 360f) ?: eff.rotationDeg
        val next = eff.copy(
            positionX = px,
            positionY = py,
            scaleX = sx,
            scaleY = sy,
            rotationDeg = rot,
        )
        if (next != eff) {
            onUpdateTransform(next)
        }
    }

    StudioPanel(modifier = modifier.fillMaxWidth()) {
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
                        text = if (isText) stringResource(R.string.text_transform_title) else "Layer Transform",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    StudioBadge(
                        text = badgeText,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                TextButton(
                    onClick = { onUpdateTransform(LayerTransform()) },
                    enabled = !eff.isDefault || (clip.textSpec?.offsetX ?: 0f) != 0f || (clip.textSpec?.offsetY ?: 0f) != 0f,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reset_transform_all_button"),
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset All")
                }
            }

            // Center & Reset Action Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = { onUpdateTransform(eff.copy(positionX = 0f)) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("center_h_button"),
                ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Center H")
                }
                OutlinedButton(
                    onClick = { onUpdateTransform(eff.copy(positionY = 0f)) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("center_v_button"),
                ) {
                    Text("Center V")
                }
                OutlinedButton(
                    onClick = { onUpdateTransform(eff.copy(positionX = 0f, positionY = 0f)) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reset_position_button"),
                ) {
                    Text("Reset Pos")
                }
                OutlinedButton(
                    onClick = { onUpdateTransform(eff.copy(scaleX = 1f, scaleY = 1f, uniformScale = true)) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reset_scale_button"),
                ) {
                    Text("Reset Scale")
                }
                OutlinedButton(
                    onClick = { onUpdateTransform(eff.copy(rotationDeg = 0f)) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reset_rotation_button"),
                ) {
                    Text("Reset Rot")
                }
            }

            // Numeric Direct Inputs (Position X%, Position Y%, Rotation°)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = posXInput,
                    onValueChange = { posXInput = it },
                    label = { Text("X (%)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        applyNumericFields()
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (!it.isFocused) applyNumericFields() }
                        .testTag("transform_pos_x_input"),
                )
                OutlinedTextField(
                    value = posYInput,
                    onValueChange = { posYInput = it },
                    label = { Text("Y (%)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        applyNumericFields()
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (!it.isFocused) applyNumericFields() }
                        .testTag("transform_pos_y_input"),
                )
                OutlinedTextField(
                    value = rotInput,
                    onValueChange = { rotInput = it },
                    label = { Text("Rot (°)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        applyNumericFields()
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (!it.isFocused) applyNumericFields() }
                        .testTag("transform_rotation_input"),
                )
            }

            // Position X & Y Sliders (-120% to +120% covers inside & slightly outside canvas)
            TransformSliderRow(
                label = "Position X",
                valueText = "${(eff.positionX * 100f).roundToInt()}%",
                value = eff.positionX,
                valueRange = -1.2f..1.2f,
                onValueChange = { onPreviewTransform(eff.copy(positionX = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Transform Position X") },
                testTag = "transform_pos_x_slider",
            )
            TransformSliderRow(
                label = "Position Y",
                valueText = "${(eff.positionY * 100f).roundToInt()}%",
                value = eff.positionY,
                valueRange = -1.2f..1.2f,
                onValueChange = { onPreviewTransform(eff.copy(positionY = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Transform Position Y") },
                testTag = "transform_pos_y_slider",
            )

            // Scale Controls (Uniform Lock + Independent X/Y + Numeric)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = eff.uniformScale,
                    onClick = {
                        val nextUniform = !eff.uniformScale
                        onUpdateTransform(
                            eff.copy(
                                uniformScale = nextUniform,
                                scaleY = if (nextUniform) eff.scaleX else eff.scaleY,
                            ),
                        )
                    },
                    label = { Text(if (eff.uniformScale) "Uniform Scale (Locked)" else "Independent X/Y Scale") },
                    modifier = Modifier.testTag("uniform_scale_chip"),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = scaleXInput,
                    onValueChange = { scaleXInput = it },
                    label = { Text(if (eff.uniformScale) "Scale (x)" else "Scale X") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        applyNumericFields()
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (!it.isFocused) applyNumericFields() }
                        .testTag("transform_scale_x_input"),
                )
                if (!eff.uniformScale) {
                    OutlinedTextField(
                        value = scaleYInput,
                        onValueChange = { scaleYInput = it },
                        label = { Text("Scale Y") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            applyNumericFields()
                            focusManager.clearFocus()
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { if (!it.isFocused) applyNumericFields() }
                            .testTag("transform_scale_y_input"),
                    )
                }
            }

            if (eff.uniformScale) {
                TransformSliderRow(
                    label = "Scale",
                    valueText = String.format("%.2fx", eff.scaleX),
                    value = eff.scaleX,
                    valueRange = 0.2f..3.0f,
                    onValueChange = { onPreviewTransform(eff.copy(scaleX = it, scaleY = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Transform Scale") },
                    testTag = "transform_scale_uniform_slider",
                )
            } else {
                TransformSliderRow(
                    label = "Scale X",
                    valueText = String.format("%.2fx", eff.scaleX),
                    value = eff.scaleX,
                    valueRange = 0.2f..3.0f,
                    onValueChange = { onPreviewTransform(eff.copy(scaleX = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Transform Scale X") },
                    testTag = "transform_scale_x_slider",
                )
                TransformSliderRow(
                    label = "Scale Y",
                    valueText = String.format("%.2fx", eff.scaleY),
                    value = eff.scaleY,
                    valueRange = 0.2f..3.0f,
                    onValueChange = { onPreviewTransform(eff.copy(scaleY = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Transform Scale Y") },
                    testTag = "transform_scale_y_slider",
                )
            }

            // Rotation Slider & Quick Steps (-15° / 0° / +15°)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        onUpdateTransform(eff.copy(rotationDeg = (eff.rotationDeg - 15f).coerceIn(-360f, 360f)))
                    },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(Icons.Default.RotateLeft, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("-15°")
                }
                OutlinedButton(
                    onClick = { onUpdateTransform(eff.copy(rotationDeg = 0f)) },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Text("0°")
                }
                OutlinedButton(
                    onClick = {
                        onUpdateTransform(eff.copy(rotationDeg = (eff.rotationDeg + 15f).coerceIn(-360f, 360f)))
                    },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("+15°")
                }
            }

            TransformSliderRow(
                label = "Rotation",
                valueText = "${eff.rotationDeg.roundToInt()}°",
                value = eff.rotationDeg,
                valueRange = -180f..180f,
                onValueChange = { onPreviewTransform(eff.copy(rotationDeg = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Transform Rotation") },
                testTag = "transform_rotation_slider",
            )

            TransformSliderRow(
                label = "Opacity",
                valueText = "${(eff.opacity * 100f).roundToInt()}%",
                value = eff.opacity,
                valueRange = 0f..1f,
                onValueChange = { onPreviewTransform(eff.copy(opacity = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Layer Opacity") },
                testTag = "transform_opacity_slider",
            )

            // Anchor / Pivot Controls
            Text(
                text = "Pivot / Anchor (${String.format("%.2f", eff.anchorX)}, ${String.format("%.2f", eff.anchorY)})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ANCHOR_PRESETS.forEach { preset ->
                    val isSelected = abs(eff.anchorX - preset.ax) < 0.04f && abs(eff.anchorY - preset.ay) < 0.04f
                    FilterChip(
                        selected = isSelected,
                        onClick = { onUpdateTransform(eff.copy(anchorX = preset.ax, anchorY = preset.ay)) },
                        label = { Text(preset.label) },
                        modifier = Modifier.testTag("anchor_preset_${preset.label.lowercase()}"),
                    )
                }
            }
            TransformSliderRow(
                label = "Anchor X",
                valueText = String.format("%.2f", eff.anchorX),
                value = eff.anchorX,
                valueRange = 0f..1f,
                onValueChange = { onPreviewTransform(eff.copy(anchorX = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Transform Anchor X") },
                testTag = "transform_anchor_x_slider",
            )
            TransformSliderRow(
                label = "Anchor Y",
                valueText = String.format("%.2f", eff.anchorY),
                value = eff.anchorY,
                valueRange = 0f..1f,
                onValueChange = { onPreviewTransform(eff.copy(anchorY = it)) },
                onValueChangeFinished = { onCommitSliderEdit("Transform Anchor Y") },
                testTag = "transform_anchor_y_slider",
            )
        }
    }
}

@Composable
private fun TransformSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    testTag: String,
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
            modifier = Modifier
                .height(34.dp)
                .testTag(testTag),
        )
    }
}
