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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.engine.audio.WaveformEnvelope
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.TextStylePreset
import com.example.engine.text.LyricLineDraft
import com.example.ui.theme.LocalStudioColors

@Composable
fun LyricStudioPanel(
    lyricState: LyricStudioUiState,
    assets: List<MediaAsset>,
    waveformsByAssetId: Map<String, WaveformEnvelope>,
    playheadMs: Long,
    totalDurationMs: Long,
    fps: Int,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onSeekTo: (Long) -> Unit,
    onSelectSourceAsset: (String?) -> Unit,
    onImportLyricsText: (String) -> Unit,
    onRequestImportTxtFile: () -> Unit,
    onAddEmptyLine: () -> Unit,
    onSelectLine: (String) -> Unit,
    onSelectWord: (String, String) -> Unit,
    onEditLineText: (String, String) -> Unit,
    onSplitSelectedLine: () -> Unit,
    onMergeLineWithNext: (String) -> Unit,
    onMoveLine: (String, Int) -> Unit,
    onDeleteLine: (String) -> Unit,
    onAssignLineStartAtPlayhead: () -> Unit,
    onAssignLineEndAtPlayhead: () -> Unit,
    onAssignWordStartAtPlayhead: (String, String) -> Unit,
    onAssignWordEndAtPlayhead: (String, String) -> Unit,
    onStepWordSelection: (Int) -> Unit,
    onAdjustWordDuration: (Long) -> Unit,
    onEstimateWordTimings: () -> Unit,
    onShiftLyricsOffset: (Long, Boolean) -> Unit,
    onResetLyricTiming: (Boolean) -> Unit,
    onSetSnapToFrames: (Boolean) -> Unit,
    onSetSnapToBeats: (Boolean) -> Unit,
    onSetKaraokeMode: (KaraokeHighlightMode) -> Unit,
    onSetStylePreset: (TextStylePreset) -> Unit,
    onSetUseInvertEffect: (Boolean) -> Unit,
    onCommitLyricsToTimeline: () -> Unit,
) {
    val studio = LocalStudioColors.current
    var pasteBuffer by rememberSaveable { mutableStateOf("") }
    val audioSources = assets.filter { it.type != MediaType.IMAGE && it.status == MediaStatus.READY }
    val selectedSource = lyricState.selectedSourceAssetId?.let { id ->
        audioSources.firstOrNull { it.id == id }
    } ?: audioSources.firstOrNull()
    val waveform = selectedSource?.let { waveformsByAssetId[it.id] }
    val selectedLine = lyricState.selectedLine
    val selectedWord = lyricState.selectedWord

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1. Header & Honest Experimental Singing-Recognition Status
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
                            text = stringResource(R.string.lyric_studio_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    StudioBadge(
                        text = "${lyricState.lines.size} LINE(S)",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                // Experimental provider honesty disclosure
                Surface(
                    color = studio.elevatedPanel,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, studio.hairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${lyricState.recognitionAvailability.providerName} • Experimental",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = lyricState.recognitionAvailability.statusNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Audio source reference chips
                if (audioSources.isNotEmpty()) {
                    Text(
                        text = "Audio Waveform & Beat Reference",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        audioSources.forEach { asset ->
                            FilterChip(
                                selected = selectedSource?.id == asset.id,
                                onClick = { onSelectSourceAsset(asset.id) },
                                label = {
                                    Text(
                                        text = asset.displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }

                // Paste or Import .txt
                OutlinedTextField(
                    value = pasteBuffer,
                    onValueChange = { pasteBuffer = it },
                    label = { Text("Paste Lyrics (.txt or [mm:ss.xx] LRC lines)") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lyric_paste_input"),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (pasteBuffer.isNotBlank()) {
                                onImportLyricsText(pasteBuffer)
                            }
                        },
                        enabled = pasteBuffer.isNotBlank(),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("import_pasted_lyrics_button"),
                    ) {
                        Text("Load Lines")
                    }
                    OutlinedButton(
                        onClick = onRequestImportTxtFile,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("import_txt_lyrics_button"),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import .txt")
                    }
                    OutlinedButton(
                        onClick = onAddEmptyLine,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("add_empty_lyric_line_button"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Line")
                    }
                }
            }
        }

        // 2. Embedded Sync Transport, Waveform Scrubber & Beat/Frame Snap
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
                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("lyric_sync_play_pause"),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause preview" else "Play preview",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        OutlinedButton(
                            onClick = { onStepFrame(-1) },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous frame", modifier = Modifier.size(16.dp))
                            Text("-1F")
                        }
                        OutlinedButton(
                            onClick = { onStepFrame(1) },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Text("+1F")
                            Icon(Icons.Default.SkipNext, contentDescription = "Next frame", modifier = Modifier.size(16.dp))
                        }
                    }

                    Text(
                        text = TimeFormatting.formatTimecode(playheadMs, fps),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // Interactive Waveform & Beat Strip
                val scrubTotal = maxOf(totalDurationMs, waveform?.durationMs ?: 0L, 1_000L)
                val primaryColor = MaterialTheme.colorScheme.primary
                val beatColor = studio.audioAmber
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(studio.elevatedPanel)
                        .border(1.dp, studio.hairlineBorder, RoundedCornerShape(8.dp))
                        .pointerInput(scrubTotal) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                onSeekTo((frac * scrubTotal).toLong())
                            }
                        }
                        .testTag("lyric_waveform_scrubber"),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val peaks = waveform?.peaks.orEmpty()
                        if (peaks.isNotEmpty()) {
                            val step = size.width / peaks.size.toFloat()
                            val centerY = size.height / 2f
                            peaks.forEachIndexed { idx, amp ->
                                val x = idx * step + step * 0.5f
                                val h = (amp.coerceIn(0.05f, 1f) * size.height * 0.85f) / 2f
                                drawLine(
                                    color = primaryColor.copy(alpha = 0.45f),
                                    start = Offset(x, centerY - h),
                                    end = Offset(x, centerY + h),
                                    strokeWidth = maxOf(1.5f, step * 0.65f),
                                )
                            }
                        }
                        waveform?.beatTimestampsMs?.forEach { beatMs ->
                            val bx = (beatMs.toFloat() / scrubTotal.toFloat()).coerceIn(0f, 1f) * size.width
                            drawLine(
                                color = beatColor.copy(alpha = 0.65f),
                                start = Offset(bx, 0f),
                                end = Offset(bx, size.height),
                                strokeWidth = 1.5f,
                            )
                        }
                        val playX = (playheadMs.toFloat() / scrubTotal.toFloat()).coerceIn(0f, 1f) * size.width
                        drawLine(
                            color = primaryColor,
                            start = Offset(playX, 0f),
                            end = Offset(playX, size.height),
                            strokeWidth = 3f,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = lyricState.snapToFrames,
                        onClick = { onSetSnapToFrames(!lyricState.snapToFrames) },
                        label = { Text("Snap Frame (${fps}fps)") },
                    )
                    FilterChip(
                        selected = lyricState.snapToBeats,
                        onClick = { onSetSnapToBeats(!lyricState.snapToBeats) },
                        label = { Text("Snap Beats (${waveform?.beatTimestampsMs?.size ?: 0})") },
                    )
                }
            }
        }

        // 3. Synchronization Tools for Selected Line & Word
        if (selectedLine != null) {
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
                        Text(
                            text = "Tap-to-Sync Selected Line & Words",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (selectedLine.isEstimated) {
                            StudioBadge(
                                text = "ESTIMATED",
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
                        Button(
                            onClick = onAssignLineStartAtPlayhead,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("lyric_tap_line_start"),
                        ) {
                            Text("Tap Line Start")
                        }
                        Button(
                            onClick = onAssignLineEndAtPlayhead,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("lyric_tap_line_end"),
                        ) {
                            Text("Tap Line End")
                        }
                        OutlinedButton(
                            onClick = onEstimateWordTimings,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("lyric_estimate_words_button"),
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Estimate Words (Est.)")
                        }
                    }

                    // Word chips for per-word timing
                    if (selectedLine.words.isNotEmpty()) {
                        Text(
                            text = "Words (tap a word, then assign @ playhead):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            selectedLine.words.forEach { word ->
                                val isSel = word.id == selectedWord?.id
                                Surface(
                                    color = if (isSel) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        studio.elevatedPanel
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        width = if (isSel) 1.5.dp else 1.dp,
                                        color = if (isSel) MaterialTheme.colorScheme.primary else studio.hairlineBorder,
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSelectWord(selectedLine.id, word.id) }
                                        .testTag("lyric_word_chip_${word.id}"),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = if (word.isEstimated) "${word.text} *" else word.text,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (isSel) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        Text(
                                            text = if (word.startMs != null && word.endMs != null) {
                                                "${TimeFormatting.formatDurationLabel(word.startMs)}–${TimeFormatting.formatDurationLabel(word.endMs)}"
                                            } else {
                                                "Untimed"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            IconButton(
                                onClick = { onStepWordSelection(-1) },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous word")
                            }
                            Button(
                                onClick = {
                                    val w = selectedWord ?: selectedLine.words.first()
                                    onAssignWordStartAtPlayhead(selectedLine.id, w.id)
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("lyric_tap_word_start"),
                            ) {
                                Text("Word Start @ Playhead")
                            }
                            OutlinedButton(
                                onClick = {
                                    val w = selectedWord ?: selectedLine.words.first()
                                    onAssignWordEndAtPlayhead(selectedLine.id, w.id)
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("lyric_tap_word_end"),
                            ) {
                                Text("Word End")
                            }
                            IconButton(
                                onClick = { onStepWordSelection(1) },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next word")
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(onClick = { onAdjustWordDuration(-50L) }) { Text("Dur -50ms") }
                            OutlinedButton(onClick = { onAdjustWordDuration(50L) }) { Text("Dur +50ms") }
                            OutlinedButton(onClick = { onShiftLyricsOffset(-100L, false) }) { Text("Line -0.1s") }
                            OutlinedButton(onClick = { onShiftLyricsOffset(100L, false) }) { Text("Line +0.1s") }
                            OutlinedButton(onClick = { onShiftLyricsOffset(-100L, true) }) { Text("All -0.1s") }
                            OutlinedButton(onClick = { onShiftLyricsOffset(100L, true) }) { Text("All +0.1s") }
                            if (selectedLine.words.size >= 2) {
                                OutlinedButton(onClick = onSplitSelectedLine) {
                                    Icon(Icons.Default.CallSplit, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Split Line")
                                }
                            }
                            TextButton(onClick = { onResetLyricTiming(false) }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reset Line")
                            }
                        }
                    }
                }
            }
        }

        // 4. Editable Lyric Lines List
        if (lyricState.lines.isNotEmpty()) {
            StudioPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Lyric Lines (${lyricState.lines.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    lyricState.lines.forEachIndexed { idx, line ->
                        LyricLineCard(
                            index = idx,
                            line = line,
                            totalLines = lyricState.lines.size,
                            isSelected = line.id == selectedLine?.id,
                            fps = fps,
                            onSelect = { onSelectLine(line.id) },
                            onEditText = { onEditLineText(line.id, it) },
                            onMoveUp = { onMoveLine(line.id, -1) },
                            onMoveDown = { onMoveLine(line.id, 1) },
                            onMergeNext = { onMergeLineWithNext(line.id) },
                            onDelete = { onDeleteLine(line.id) },
                        )
                    }
                }
            }

            // 5. Style & Timeline Commit
            StudioPanel(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Timeline Lyric Style",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TextStylePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = lyricState.stylePreset == preset,
                                onClick = { onSetStylePreset(preset) },
                                label = { Text(preset.label) },
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
                                selected = lyricState.karaokeMode == mode,
                                onClick = { onSetKaraokeMode(mode) },
                                label = { Text(mode.label) },
                            )
                        }
                    }
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
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Render as Invert Lyric GPU Mask",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Switch(
                            checked = lyricState.useInvertEffect,
                            onCheckedChange = onSetUseInvertEffect,
                            modifier = Modifier.testTag("lyric_invert_effect_switch"),
                        )
                    }

                    Button(
                        onClick = onCommitLyricsToTimeline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("commit_lyrics_button"),
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Commit ${lyricState.lines.size} Lyric(s) to Timeline")
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricLineCard(
    index: Int,
    line: LyricLineDraft,
    totalLines: Int,
    isSelected: Boolean,
    fps: Int,
    onSelect: () -> Unit,
    onEditText: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMergeNext: () -> Unit,
    onDelete: () -> Unit,
) {
    val studio = LocalStudioColors.current
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f) else studio.elevatedPanel,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else studio.hairlineBorder,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("lyric_line_card_$index"),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (line.startMs != null && line.endMs != null) {
                            "${TimeFormatting.formatTimecode(line.startMs, fps)} → ${TimeFormatting.formatTimecode(line.endMs, fps)}"
                        } else {
                            "Untimed (auto-placed on commit)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (line.isEstimated) {
                        StudioBadge(
                            text = "EST",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMoveUp, enabled = index > 0) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move line up", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = index < totalLines - 1) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move line down", modifier = Modifier.size(16.dp))
                    }
                    if (index < totalLines - 1) {
                        IconButton(onClick = onMergeNext) {
                            Icon(Icons.Default.CallMerge, contentDescription = "Merge with next line", modifier = Modifier.size(16.dp))
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete line", modifier = Modifier.size(16.dp))
                    }
                }
            }

            OutlinedTextField(
                value = line.text,
                onValueChange = onEditText,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
