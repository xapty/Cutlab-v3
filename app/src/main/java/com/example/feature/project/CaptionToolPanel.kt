package com.example.feature.project

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.engine.asr.AsrLanguageOption
import com.example.engine.asr.AsrModelAvailability
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.TextStylePreset
import com.example.ui.theme.LocalStudioColors
import kotlin.math.roundToInt

@Composable
fun CaptionToolPanel(
    captionState: AutoCaptionUiState,
    assets: List<MediaAsset>,
    fps: Int,
    onSelectSourceAsset: (String?) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectLanguage: (String) -> Unit = {},
    onDownloadModel: (String) -> Unit,
    onCancelModelDownload: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onStartTranscription: () -> Unit,
    onCancelTranscription: () -> Unit,
    onUpdateMaxCharsPerLine: (Int) -> Unit,
    onUpdateKaraokeMode: (KaraokeHighlightMode) -> Unit,
    onUpdateStylePreset: (TextStylePreset) -> Unit,
    onUpdateUseInvertEffect: (Boolean) -> Unit,
    onUpdateCueText: (String, String) -> Unit,
    onDeleteCue: (String) -> Unit,
    onCommitCaptionsToTimeline: () -> Unit,
) {
    val studio = LocalStudioColors.current
    val audioSources = assets.filter { it.type != MediaType.IMAGE && it.status == MediaStatus.READY }
    val selectedSource = captionState.selectedSourceAssetId?.let { id ->
        audioSources.firstOrNull { it.id == id }
    } ?: audioSources.firstOrNull()
    val model = captionState.selectedModel
    val availability = captionState.selectedModelAvailability
    val selectedLanguage = captionState.selectedLanguage

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1. Offline Whisper ASR Header & Source Media
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
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.auto_caption_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    StudioBadge(
                        text = "sherpa-onnx Whisper",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = studio.readyEmerald,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.auto_caption_privacy_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = "Source Audio / Video",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (audioSources.isEmpty()) {
                    EmptyStudioState(
                        icon = Icons.Default.GraphicEq,
                        title = "No Audio or Video Assets",
                        subtitle = "Import a video or audio clip into this project before running Whisper speech transcription.",
                    )
                } else {
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
                                modifier = Modifier.testTag("caption_source_${asset.id}"),
                            )
                        }
                    }
                }
            }
        }

        // 2. Decoupled Whisper Model & Language Selection + Provisioning
        StudioPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Offline Whisper Model",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    captionState.models.forEach { spec ->
                        val shortTitle = if (spec.id.contains("tiny")) "Whisper Tiny" else "Whisper Base"
                        FilterChip(
                            selected = spec.id == model.id,
                            onClick = { onSelectModel(spec.id) },
                            label = { Text("$shortTitle (~${spec.approximateSizeMb} MB)") },
                            modifier = Modifier.testTag("asr_model_${spec.id}"),
                        )
                    }
                }

                Text(
                    text = "Spoken Language",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AsrLanguageOption.entries.forEach { lang ->
                        FilterChip(
                            selected = selectedLanguage == lang,
                            onClick = { onSelectLanguage(lang.code) },
                            label = { Text(lang.nativeLabel) },
                            modifier = Modifier.testTag("asr_lang_${lang.code}"),
                        )
                    }
                }

                // Model installation status card
                Surface(
                    color = studio.elevatedPanel,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, studio.hairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val statusLabel = when (availability) {
                                        is AsrModelAvailability.Ready -> "READY"
                                        is AsrModelAvailability.Downloading -> "DOWNLOADING"
                                        is AsrModelAvailability.Error -> "FAILED"
                                        AsrModelAvailability.NotInstalled -> "NOT DOWNLOADED"
                                    }
                                    StudioBadge(
                                        text = statusLabel,
                                        containerColor = when (availability) {
                                            is AsrModelAvailability.Ready -> studio.readyEmeraldContainer
                                            is AsrModelAvailability.Error -> MaterialTheme.colorScheme.errorContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        contentColor = when (availability) {
                                            is AsrModelAvailability.Ready -> studio.readyEmerald
                                            is AsrModelAvailability.Error -> MaterialTheme.colorScheme.onErrorContainer
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Text(
                                    text = model.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when (availability) {
                                is AsrModelAvailability.Ready -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Model ready",
                                            tint = studio.readyEmerald,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = "${(availability.diskBytes / (1024 * 1024)).coerceAtLeast(1L)} MB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = studio.readyEmerald,
                                        )
                                        IconButton(
                                            onClick = { onDeleteModel(model.id) },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("delete_asr_model_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete offline Whisper model",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                is AsrModelAvailability.Downloading -> {
                                    TextButtonCancelDownload(
                                        onClick = { onCancelModelDownload(model.id) },
                                    )
                                }
                                is AsrModelAvailability.Error -> {
                                    Button(
                                        onClick = { onDownloadModel(model.id) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("retry_asr_model_download_button"),
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Retry")
                                    }
                                }
                                AsrModelAvailability.NotInstalled -> {
                                    Button(
                                        onClick = { onDownloadModel(model.id) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("download_asr_model_button"),
                                    ) {
                                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Download (${model.approximateSizeMb} MB)")
                                    }
                                }
                            }
                        }

                        if (availability is AsrModelAvailability.Downloading) {
                            LinearProgressIndicator(
                                progress = { availability.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val dlMb = availability.downloadedBytes / (1024f * 1024f)
                            val totMb = (availability.totalBytes.coerceAtLeast(1L)) / (1024f * 1024f)
                            Text(
                                text = "Downloading & extracting .tar.bz2: ${String.format("%.1f / %.1f MB", dlMb, totMb)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (availability is AsrModelAvailability.Error) {
                            Text(
                                text = availability.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                // Run / Cancel Transcription
                val isBusy = captionState.jobState is AutoCaptionJobState.LoadingModel ||
                    captionState.jobState is AutoCaptionJobState.Running
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onStartTranscription,
                        enabled = !isBusy &&
                            selectedSource != null &&
                            availability is AsrModelAvailability.Ready,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("start_auto_caption_button"),
                    ) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        val buttonText = when (captionState.jobState) {
                            is AutoCaptionJobState.LoadingModel -> "Loading Model..."
                            is AutoCaptionJobState.Running -> "Recognizing..."
                            is AutoCaptionJobState.Error -> "Retry Transcribe"
                            else -> "Transcribe (${selectedLanguage.nativeLabel})"
                        }
                        Text(buttonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    if (isBusy) {
                        OutlinedButton(
                            onClick = onCancelTranscription,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("cancel_auto_caption_button"),
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                when (val job = captionState.jobState) {
                    is AutoCaptionJobState.LoadingModel -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Loading ${job.modelName} into memory...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is AutoCaptionJobState.Running -> {
                        LinearProgressIndicator(
                            progress = { job.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = job.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is AutoCaptionJobState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = job.message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                if (availability is AsrModelAvailability.Ready && selectedSource != null) {
                                    OutlinedButton(
                                        onClick = onStartTranscription,
                                        modifier = Modifier.minimumInteractiveComponentSize(),
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
                    is AutoCaptionJobState.Completed -> {
                        Text(
                            text = "Completed: ${job.cueCount} cue(s) via ${job.engineName}. Review or edit before inserting.",
                            style = MaterialTheme.typography.labelSmall,
                            color = studio.readyEmerald,
                        )
                    }
                    AutoCaptionJobState.Cancelled -> {
                        Text(
                            text = "Transcription cancelled.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AutoCaptionJobState.Idle -> Unit
                }
            }
        }

        // 3. Caption Segmentation, Style & Transcript Review
        StudioPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Max Chars / Line",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${captionState.maxCharsPerLine} chars",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Slider(
                    value = captionState.maxCharsPerLine.toFloat(),
                    onValueChange = { onUpdateMaxCharsPerLine(it.roundToInt()) },
                    valueRange = 16f..52f,
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("caption_max_chars_slider"),
                )

                Text(
                    text = "Caption Style & Word Highlight",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextStylePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = captionState.stylePreset == preset,
                            onClick = { onUpdateStylePreset(preset) },
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
                            selected = captionState.karaokeMode == mode,
                            onClick = { onUpdateKaraokeMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Use Invert Lyric GPU Mask",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(
                        checked = captionState.useInvertEffect,
                        onCheckedChange = onUpdateUseInvertEffect,
                        modifier = Modifier.testTag("caption_invert_effect_switch"),
                    )
                }

                if (captionState.cues.isEmpty()) {
                    Text(
                        text = "Run offline Whisper transcription above to preview and edit multilingual caption cues.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Editable Transcript Cues (${captionState.cues.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    captionState.cues.forEach { cue ->
                        Surface(
                            color = studio.elevatedPanel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, studio.hairlineBorder),
                            modifier = Modifier.fillMaxWidth(),
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
                                            text = "${TimeFormatting.formatTimecode(cue.startMs, fps)} → ${TimeFormatting.formatTimecode(cue.endMs, fps)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (cue.words.isNotEmpty() || cue.isTimingEstimated) {
                                            StudioBadge(
                                                text = if (cue.isTimingEstimated) "EST" else "${cue.words.size}w",
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onDeleteCue(cue.id) },
                                        modifier = Modifier.minimumInteractiveComponentSize(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Delete caption cue",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = cue.text,
                                    onValueChange = { onUpdateCueText(cue.id, it) },
                                    singleLine = false,
                                    maxLines = 4,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("caption_cue_input_${cue.id}"),
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onCommitCaptionsToTimeline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("commit_captions_button"),
                    ) {
                        Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Insert ${captionState.cues.size} Caption(s) to Timeline")
                    }
                }
            }
        }
    }
}

@Composable
private fun TextButtonCancelDownload(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("cancel_asr_download_button"),
    ) {
        Text("Cancel")
    }
}
