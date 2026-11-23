package com.example.feature.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.ClipKind
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.LayerTransform
import com.example.engine.project.TextContentType
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TimedTextWord
import com.example.engine.project.TimelineClipRef
import com.example.engine.text.UnifiedTextEngine
import com.example.ui.theme.LocalStudioColors
import kotlin.math.roundToInt

/**
 * Shared Quick Edit & Advanced Edit controls for:
 * - Invert Lyric GPU Mask (TextRenderMode.INVERT_VIDEO, strength 0-100%, softness, background)
 * - Word-level Karaoke Highlighting (mode, colors, word spacing, estimated word timing)
 */
@Composable
fun InvertLyricAndKaraokeCard(
    selectedClip: TimelineClipRef?,
    onAddPresetTextClip: (TextOverlaySpec, String) -> Unit,
    onUpdateTextSpec: (TextOverlaySpec) -> Unit,
    onPreviewTextSpec: (TextOverlaySpec) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
    onOpenLyricStudioForClip: () -> Unit = {},
    onOpenAutoCaption: () -> Unit = {},
    showTransformControls: Boolean = false,
    onUpdateTransform: (LayerTransform) -> Unit = {},
    onPreviewTransform: (LayerTransform) -> Unit = {},
) {
    val studio = LocalStudioColors.current
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
                        imageVector = Icons.Default.InvertColors,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Invert Lyric & Karaoke Engine",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (spec != null) {
                    StudioBadge(
                        text = when (spec.contentType) {
                            TextContentType.TITLE -> "TITLE"
                            TextContentType.CAPTION -> "CAPTION"
                            TextContentType.LYRIC -> "LYRIC"
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // Quick launch / preset row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onAddPresetTextClip(
                            EffectsEngine.defaultInvertLyricSpec("INVERT LYRIC"),
                            "Add Invert Lyric",
                        )
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("quick_add_invert_lyric_button"),
                ) {
                    Icon(Icons.Default.InvertColors, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("+ Invert Lyric")
                }

                OutlinedButton(
                    onClick = {
                        val base = EffectsEngine.defaultKaraokeLyricSpec()
                        val estWords = UnifiedTextEngine.estimateUniformWordTimings(
                            text = base.text,
                            durationMs = 3_000L,
                        )
                        onAddPresetTextClip(
                            base.copy(timedWords = estWords, isTimingEstimated = true),
                            "Add Karaoke Lyric",
                        )
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("quick_add_karaoke_lyric_button"),
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("+ Karaoke")
                }

                OutlinedButton(
                    onClick = onOpenAutoCaption,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("quick_open_auto_caption_button"),
                ) {
                    Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Auto Caption")
                }
            }

            if (spec == null) {
                Text(
                    text = "Select a text, caption, or lyric clip on the timeline to configure GPU Invert Lyric masking and word-level karaoke highlighting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // 1. Render Mode: Normal vs GPU Invert Lyric
            Text(
                text = "GPU Render Mode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = spec.renderMode == TextRenderMode.NORMAL,
                    onClick = {
                        onUpdateTextSpec(spec.copy(renderMode = TextRenderMode.NORMAL))
                    },
                    label = { Text("Standard Text") },
                    modifier = Modifier.testTag("text_render_mode_normal"),
                )
                FilterChip(
                    selected = spec.renderMode == TextRenderMode.INVERT_VIDEO,
                    onClick = {
                        onUpdateTextSpec(
                            spec.copy(
                                renderMode = TextRenderMode.INVERT_VIDEO,
                                invertStrength = if (spec.invertStrength <= 0.01f) 1.0f else spec.invertStrength,
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.InvertColors, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("Invert Lyric (GPU RGB)") },
                    modifier = Modifier.testTag("text_render_mode_invert"),
                )
            }

            if (spec.renderMode == TextRenderMode.INVERT_VIDEO) {
                Text(
                    text = "Inverts underlying video RGB pixels inside text glyphs across live preview, scrubbing, and Path B export.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                InvertSliderRow(
                    label = "Invert Strength",
                    valueText = "${(spec.invertStrength * 100f).roundToInt()}%",
                    value = spec.invertStrength,
                    valueRange = 0f..1f,
                    onValueChange = { onPreviewTextSpec(spec.copy(invertStrength = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Invert Lyric Strength") },
                    testTag = "invert_strength_slider",
                )

                InvertSliderRow(
                    label = "Edge Softness",
                    valueText = "${(spec.invertEdgeSoftness * 100f).roundToInt()}%",
                    value = spec.invertEdgeSoftness,
                    valueRange = 0f..1f,
                    onValueChange = { onPreviewTextSpec(spec.copy(invertEdgeSoftness = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Invert Edge Softness") },
                    testTag = "invert_softness_slider",
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Invert Pill Background",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Default off: inverts only glyph strokes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = spec.invertIncludeBackground,
                        onCheckedChange = { checked ->
                            onUpdateTextSpec(spec.copy(invertIncludeBackground = checked))
                        },
                        modifier = Modifier.testTag("invert_include_bg_switch"),
                    )
                }
            }

            // 2. Karaoke Highlighting & Word Timing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Karaoke Word Highlight",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (spec.isTimingEstimated) {
                    StudioBadge(
                        text = "ESTIMATED TIMING",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KaraokeHighlightMode.entries.forEach { mode ->
                    FilterChip(
                        selected = spec.karaokeMode == mode,
                        onClick = { onUpdateTextSpec(spec.copy(karaokeMode = mode)) },
                        label = { Text(mode.label) },
                        modifier = Modifier.testTag("karaoke_mode_${mode.name.lowercase()}"),
                    )
                }
            }

            if (spec.karaokeMode != KaraokeHighlightMode.NONE) {
                val highlightSwatches = listOf(
                    0xFF38BDF8L, 0xFFF59E0BL, 0xFF34D399L,
                    0xFFF43F5EL, 0xFFA78BFAL, 0xFFFFFFFFL,
                )
                Text(
                    text = "Active Word Highlight Color",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    highlightSwatches.forEach { argb ->
                        val selected = spec.highlightColorArgb == argb
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(argb.toInt()))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else studio.hairlineBorder,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    onUpdateTextSpec(spec.copy(highlightColorArgb = argb))
                                },
                        )
                    }
                }

                InvertSliderRow(
                    label = "Word Spacing",
                    valueText = "${String.format("%.2fem", spec.wordSpacingEm)}",
                    value = spec.wordSpacingEm,
                    valueRange = 0f..0.45f,
                    onValueChange = { onPreviewTextSpec(spec.copy(wordSpacingEm = it)) },
                    onValueChangeFinished = { onCommitSliderEdit("Word Spacing") },
                    testTag = "word_spacing_slider",
                )
            }

            // Timed word summary & quick sync actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val est = UnifiedTextEngine.estimateUniformWordTimings(
                            text = spec.text,
                            durationMs = textClip.durationMs,
                        )
                        onUpdateTextSpec(
                            spec.copy(
                                timedWords = est,
                                isTimingEstimated = true,
                                karaokeMode = if (spec.karaokeMode == KaraokeHighlightMode.NONE) {
                                    KaraokeHighlightMode.PROGRESSIVE
                                } else {
                                    spec.karaokeMode
                                },
                            ),
                        )
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("estimate_clip_words_button"),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Estimate Words (Est.)")
                }

                OutlinedButton(
                    onClick = onOpenLyricStudioForClip,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("sync_clip_in_lyric_studio_button"),
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync in Lyric Studio")
                }
            }

            if (spec.timedWords.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    spec.timedWords.forEach { word ->
                        TimedWordPreviewChip(word = word)
                    }
                }
            }
        }
    }

    if (showTransformControls && textClip != null) {
        Spacer(Modifier.height(10.dp))
        UniversalTextTransformSection(
            clip = textClip,
            onUpdateTransform = onUpdateTransform,
            onPreviewTransform = onPreviewTransform,
            onCommitSliderEdit = onCommitSliderEdit,
        )
    }
}

@Composable
private fun TimedWordPreviewChip(word: TimedTextWord) {
    val studio = LocalStudioColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(studio.elevatedPanel)
            .border(BorderStroke(1.dp, studio.hairlineBorder), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (word.isEstimated) "${word.text} *" else word.text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${TimeFormatting.formatDurationLabel(word.startMs)}–${TimeFormatting.formatDurationLabel(word.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InvertSliderRow(
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
