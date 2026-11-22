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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.StudioPanel
import com.example.engine.audio.MasterAudioMixState
import com.example.engine.audio.WaveformEnvelope
import com.example.engine.project.AudioEffectPreset
import com.example.engine.project.ClipAudioEffect
import com.example.engine.project.ClipKind
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineMarker
import com.example.ui.theme.LocalStudioColors
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun AudioWorkspaceContent(
    project: ProjectDocument,
    clip: TimelineClipRef?,
    asset: MediaAsset?,
    waveform: WaveformEnvelope?,
    mixState: MasterAudioMixState,
    playheadMs: Long,
    isAuditioningMix: Boolean,
    onToggleTrackMute: (String) -> Unit,
    onUpdateAudio: (Float, Boolean, Long, Long) -> Unit,
    onPreviewAudio: (Float, Boolean, Long, Long) -> Unit,
    onUpdateAudioEffect: (ClipAudioEffect) -> Unit,
    onPreviewAudioEffect: (ClipAudioEffect) -> Unit,
    onAddMarkerAtPlayhead: () -> Unit,
    onDetectBeatsForClip: () -> Unit,
    onRemoveMarker: (String) -> Unit,
    onClearMarkers: () -> Unit,
    onJumpToTime: (Long) -> Unit,
    onAuditionPcmMix: () -> Unit,
    onCommitGesture: (String) -> Unit,
) {
    val studio = LocalStudioColors.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1. Multi-Track Audio Mixer Console & Master L/R Peak Meter
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
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = studio.audioAmber,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Multi-Track Mixer (${mixState.activeSourceCount} active)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Button(
                        onClick = onAuditionPcmMix,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAuditioningMix) studio.audioAmber else MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("audition_pcm_mix_button"),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isAuditioningMix) "Stop Mix" else "Audition PCM")
                    }
                }

                StereoLevelMeterBar(label = "L", level = mixState.leftLevel)
                StereoLevelMeterBar(label = "R", level = mixState.rightLevel)

                mixState.trackMeters.forEach { trackMeter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = { onToggleTrackMute(trackMeter.trackId) },
                            modifier = Modifier
                                .size(30.dp)
                                .testTag("mixer_mute_${trackMeter.trackId}"),
                        ) {
                            Icon(
                                imageVector = if (trackMeter.isMuted) {
                                    Icons.AutoMirrored.Filled.VolumeOff
                                } else {
                                    Icons.AutoMirrored.Filled.VolumeUp
                                },
                                contentDescription = "Toggle ${trackMeter.trackName} mute",
                                tint = if (trackMeter.isMuted) {
                                    MaterialTheme.colorScheme.error
                                } else if (trackMeter.isAudioTrack) {
                                    studio.audioAmber
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = trackMeter.activeClipName?.let { "${trackMeter.trackName} • $it" }
                                    ?: trackMeter.trackName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (trackMeter.isMuted) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(studio.elevatedPanel),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(trackMeter.peakLevel.coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(
                                            if (trackMeter.isAudioTrack) studio.audioAmber else MaterialTheme.colorScheme.primary,
                                        ),
                                )
                            }
                        }
                        Text(
                            text = "${(trackMeter.peakLevel * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 2. Beat Markers & Timeline Sync Card
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
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = studio.audioAmber,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Beat Markers (${project.markers.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (project.markers.isNotEmpty()) {
                        TextButton(
                            onClick = onClearMarkers,
                            modifier = Modifier.testTag("clear_markers_button"),
                        ) {
                            Text("Clear")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onAddMarkerAtPlayhead,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("add_beat_marker_button"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ Beat @ Playhead")
                    }
                    OutlinedButton(
                        onClick = onDetectBeatsForClip,
                        enabled = clip != null && waveform != null && waveform.beatTimestampsMs.isNotEmpty(),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("detect_beats_button"),
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto-Detect Beats")
                    }
                }

                if (project.markers.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        project.markers.forEach { marker ->
                            MarkerChip(
                                marker = marker,
                                onJump = { onJumpToTime(marker.timeMs) },
                                onRemove = { onRemoveMarker(marker.id) },
                            )
                        }
                    }
                }
            }
        }

        // 3. Selected Clip Waveform, Volume/Fades & Audio FX DSP
        if (clip == null || clip.clipKind != ClipKind.MEDIA || asset?.type == MediaType.IMAGE) {
            EmptyStudioState(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Select an Audio or Video Clip",
                subtitle = "Tap an audio or video clip on the timeline to view its waveform, fade envelopes, stereo pan, EQ, and audio FX.",
            )
            return
        }

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
                    Text(
                        text = "Clip Waveform • ${asset?.displayName ?: "Audio"}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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

                ClipWaveformCanvas(
                    clip = clip,
                    waveform = waveform,
                    playheadMs = playheadMs,
                    onJumpToTime = onJumpToTime,
                )

                AudioParamSlider(
                    label = "Volume",
                    valueText = "${(clip.volume * 100).roundToInt()}%",
                    value = clip.volume,
                    valueRange = 0f..1.5f,
                    onValueChange = {
                        onPreviewAudio(it, clip.isMuted, clip.fadeInMs, clip.fadeOutMs)
                    },
                    onValueChangeFinished = { onCommitGesture("Clip Volume") },
                )

                val maxFade = clip.durationMs.coerceIn(0L, 4_000L).toFloat().coerceAtLeast(100f)
                AudioParamSlider(
                    label = "Fade In",
                    valueText = TimeFormatting.formatDurationLabel(clip.fadeInMs),
                    value = clip.fadeInMs.toFloat().coerceIn(0f, maxFade),
                    valueRange = 0f..maxFade,
                    onValueChange = {
                        onPreviewAudio(clip.volume, clip.isMuted, it.roundToLong(), clip.fadeOutMs)
                    },
                    onValueChangeFinished = { onCommitGesture("Audio Fade In") },
                )

                AudioParamSlider(
                    label = "Fade Out",
                    valueText = TimeFormatting.formatDurationLabel(clip.fadeOutMs),
                    value = clip.fadeOutMs.toFloat().coerceIn(0f, maxFade),
                    valueRange = 0f..maxFade,
                    onValueChange = {
                        onPreviewAudio(clip.volume, clip.isMuted, clip.fadeInMs, it.roundToLong())
                    },
                    onValueChangeFinished = { onCommitGesture("Audio Fade Out") },
                )

                // Audio Effects Presets + Stereo Pan + Bass/Treble EQ + Echo
                val fx = clip.audioEffect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Audio FX & EQ",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!fx.isDefault) {
                        TextButton(onClick = { onUpdateAudioEffect(ClipAudioEffect()) }) {
                            Text("Reset FX")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AudioEffectPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = fx.preset == preset,
                            onClick = { onUpdateAudioEffect(fx.copy(preset = preset)) },
                            label = { Text(preset.label) },
                            modifier = Modifier.testTag("audio_fx_${preset.name.lowercase()}"),
                        )
                    }
                }

                val panLabel = when {
                    fx.pan < -0.05f -> "L ${(-fx.pan * 100).roundToInt()}%"
                    fx.pan > 0.05f -> "R ${(fx.pan * 100).roundToInt()}%"
                    else -> "Center"
                }
                AudioParamSlider(
                    label = "Stereo Pan",
                    valueText = panLabel,
                    value = fx.pan,
                    valueRange = -1f..1f,
                    onValueChange = { onPreviewAudioEffect(fx.copy(pan = it)) },
                    onValueChangeFinished = { onCommitGesture("Stereo Pan") },
                )
                AudioParamSlider(
                    label = "Bass EQ",
                    valueText = "${if (fx.bassDb >= 0f) "+" else ""}${String.format("%.1f", fx.bassDb)} dB",
                    value = fx.bassDb,
                    valueRange = -12f..12f,
                    onValueChange = { onPreviewAudioEffect(fx.copy(bassDb = it)) },
                    onValueChangeFinished = { onCommitGesture("Bass EQ") },
                )
                AudioParamSlider(
                    label = "Treble EQ",
                    valueText = "${if (fx.trebleDb >= 0f) "+" else ""}${String.format("%.1f", fx.trebleDb)} dB",
                    value = fx.trebleDb,
                    valueRange = -12f..12f,
                    onValueChange = { onPreviewAudioEffect(fx.copy(trebleDb = it)) },
                    onValueChangeFinished = { onCommitGesture("Treble EQ") },
                )
                AudioParamSlider(
                    label = "Echo Mix",
                    valueText = "${(fx.echoMix * 100).roundToInt()}%",
                    value = fx.echoMix,
                    valueRange = 0f..0.75f,
                    onValueChange = { onPreviewAudioEffect(fx.copy(echoMix = it)) },
                    onValueChangeFinished = { onCommitGesture("Echo Mix") },
                )
            }
        }
    }
}

@Composable
private fun StereoLevelMeterBar(
    label: String,
    level: Float,
) {
    val studio = LocalStudioColors.current
    val clamped = level.coerceIn(0f, 1f)
    val barColor = when {
        clamped > 0.88f -> MaterialTheme.colorScheme.error
        clamped > 0.65f -> studio.audioAmber
        else -> studio.readyEmerald
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(12.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .background(barColor),
            )
        }
        Text(
            text = "${(clamped * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
        )
    }
}

@Composable
private fun ClipWaveformCanvas(
    clip: TimelineClipRef,
    waveform: WaveformEnvelope?,
    playheadMs: Long,
    onJumpToTime: (Long) -> Unit,
) {
    val studio = LocalStudioColors.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val amberColor = studio.audioAmber
    val emeraldColor = studio.readyEmerald

    val slicedPeaks = remember(waveform, clip.sourceStartMs, clip.durationMs, clip.speed) {
        waveform?.slicePeaks(
            sourceStartMs = clip.sourceStartMs,
            sourceSpanMs = (clip.durationMs * clip.speed).toLong().coerceAtLeast(100L),
            targetBars = 84,
        ).orEmpty()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, studio.hairlineBorder, RoundedCornerShape(8.dp))
            .clickable {
                onJumpToTime(clip.timelineStartMs)
            }
            .testTag("audio_waveform_preview"),
        contentAlignment = Alignment.Center,
    ) {
        if (slicedPeaks.isEmpty()) {
            Text(
                text = "Extracting PCM waveform...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
                val centerY = size.height * 0.5f
                val step = size.width / slicedPeaks.size.coerceAtLeast(1)
                val barW = (step * 0.56f).coerceAtLeast(2f)
                val gain = if (clip.isMuted) 0.25f else clip.volume.coerceIn(0.15f, 1.4f)

                slicedPeaks.forEachIndexed { idx, amp ->
                    val bx = idx * step + step * 0.2f
                    val halfH = (amp * gain * size.height * 0.45f).coerceIn(2f, size.height * 0.48f)
                    drawLine(
                        color = amberColor,
                        start = Offset(bx, centerY - halfH),
                        end = Offset(bx, centerY + halfH),
                        strokeWidth = barW,
                    )
                }

                // Detected beat ticks from WaveformEnvelope
                val clipSourceEnd = clip.sourceStartMs + (clip.durationMs * clip.speed).toLong()
                val srcSpan = (clipSourceEnd - clip.sourceStartMs).coerceAtLeast(1L).toFloat()
                waveform?.beatTimestampsMs
                    ?.filter { it in clip.sourceStartMs..clipSourceEnd }
                    ?.forEach { beatSrcMs ->
                        val bx = ((beatSrcMs - clip.sourceStartMs) / srcSpan) * size.width
                        drawLine(
                            color = emeraldColor.copy(alpha = 0.8f),
                            start = Offset(bx, 0f),
                            end = Offset(bx, size.height),
                            strokeWidth = 1.5f,
                        )
                    }

                // Playhead needle if inside clip
                if (playheadMs in clip.timelineStartMs..(clip.timelineStartMs + clip.durationMs)) {
                    val relFrac = (playheadMs - clip.timelineStartMs).toFloat() / clip.durationMs.coerceAtLeast(1L).toFloat()
                    val px = relFrac * size.width
                    drawLine(
                        color = primaryColor,
                        start = Offset(px, 0f),
                        end = Offset(px, size.height),
                        strokeWidth = 2.5f,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkerChip(
    marker: TimelineMarker,
    onJump: () -> Unit,
    onRemove: () -> Unit,
) {
    val studio = LocalStudioColors.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = studio.elevatedPanel,
        border = BorderStroke(1.dp, studio.audioAmber.copy(alpha = 0.5f)),
        modifier = Modifier.clickable(onClick = onJump),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${marker.label} ${TimeFormatting.formatDurationLabel(marker.timeMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = studio.audioAmber,
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove marker",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AudioParamSlider(
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
