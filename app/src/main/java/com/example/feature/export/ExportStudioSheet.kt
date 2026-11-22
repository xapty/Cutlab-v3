package com.example.feature.export

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.engine.export.ExportAudioBitrateOption
import com.example.engine.export.ExportJobState
import com.example.engine.export.ExportPlan
import com.example.engine.export.ExportRenderPath
import com.example.engine.export.ExportResolutionPreset
import com.example.engine.export.ExportVideoCodec
import com.example.engine.export.ExportedArtifact
import com.example.engine.export.HardwareExportCapabilities
import com.example.engine.project.ExportSettingsSpec
import com.example.engine.project.FrameRateOption
import com.example.engine.project.ProjectDocument
import com.example.ui.theme.LocalStudioColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportStudioSheet(
    project: ProjectDocument,
    plan: ExportPlan?,
    hardwareCapabilities: HardwareExportCapabilities,
    preferredPath: ExportRenderPath,
    jobState: ExportJobState,
    exportHistory: List<ExportedArtifact>,
    onUpdateSettings: (ExportSettingsSpec) -> Unit,
    onSelectRenderPath: (ExportRenderPath) -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onResetJobState: () -> Unit,
    onSaveToGallery: (ExportedArtifact) -> Unit,
    onSaveToDestinationUri: (ExportedArtifact, Uri) -> Unit,
    onCreateShareIntent: (ExportedArtifact) -> Intent?,
    onDeleteArtifact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val studio = LocalStudioColors.current
    var pendingSafArtifact by remember { mutableStateOf<ExportedArtifact?>(null) }

    val safSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4"),
    ) { destUri ->
        val artifact = pendingSafArtifact
        pendingSafArtifact = null
        if (destUri != null && artifact != null) {
            onSaveToDestinationUri(artifact, destUri)
        }
    }

    val saveArtifactAction: (ExportedArtifact) -> Unit = { artifact ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onSaveToGallery(artifact)
        } else {
            pendingSafArtifact = artifact
            safSaveLauncher.launch(artifact.fileName)
        }
    }

    val shareArtifactAction: (ExportedArtifact) -> Unit = { artifact ->
        onCreateShareIntent(artifact)?.let { sendIntent ->
            val chooser = Intent.createChooser(sendIntent, "Share ${artifact.fileName}")
            runCatching { context.startActivity(chooser) }
        }
    }

    val isJobActive = jobState is ExportJobState.Preparing ||
        jobState is ExportJobState.Exporting ||
        jobState is ExportJobState.Validating

    ModalBottomSheet(
        onDismissRequest = {
            if (!isJobActive) onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 620.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export Studio",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (plan != null) {
                    val isPathB = plan.selectedPath == ExportRenderPath.CUSTOM_GL_CODEC
                    StudioBadge(
                        text = plan.selectedPath.shortTag.uppercase(),
                        containerColor = if (isPathB) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (isPathB) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }

            // Active Job Progress / Result Card
            when (jobState) {
                is ExportJobState.Preparing,
                is ExportJobState.Exporting,
                is ExportJobState.Validating,
                -> {
                    val progress = when (jobState) {
                        is ExportJobState.Preparing -> 0.02f
                        is ExportJobState.Exporting -> jobState.progress
                        is ExportJobState.Validating -> 0.98f
                        else -> 0f
                    }
                    val message = when (jobState) {
                        is ExportJobState.Preparing -> "Preparing export pipeline..."
                        is ExportJobState.Exporting -> jobState.statusMessage
                        is ExportJobState.Validating -> "Validating MP4 container & audio tracks..."
                        else -> ""
                    }
                    StudioPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_progress_card"),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${(progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = studio.elevatedPanel,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = onCancelExport,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("cancel_export_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cancel export",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Cancel Export", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                is ExportJobState.Succeeded -> {
                    val artifact = jobState.artifact
                    val validation = jobState.validation
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = studio.readyEmerald.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, studio.readyEmerald.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_success_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Export verified",
                                    tint = studio.readyEmerald,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Export Verified & Ready",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "${validation.width}×${validation.height} • ${artifact.fps} FPS • ${artifact.codec} • ${formatMegabytes(validation.fileSizeBytes)} • ${TimeFormatting.formatDurationLabel(validation.durationMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                StudioBadge(
                                    text = if (validation.hasAudioTrack) "A/V OK" else "VIDEO OK",
                                    containerColor = studio.readyEmerald.copy(alpha = 0.2f),
                                    contentColor = studio.readyEmerald,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { saveArtifactAction(artifact) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("save_to_gallery_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SaveAlt,
                                        contentDescription = "Save video to gallery",
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Save to Gallery" else "Save MP4")
                                }
                                OutlinedButton(
                                    onClick = { shareArtifactAction(artifact) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .minimumInteractiveComponentSize()
                                        .testTag("share_export_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share video",
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = onResetJobState,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text("Export New Version")
                                }
                            }
                        }
                    }
                }

                is ExportJobState.Failed -> {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_failed_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Export error",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = jobState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(onClick = onResetJobState) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }

                is ExportJobState.Cancelled -> {
                    StudioPanel(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = jobState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onResetJobState) {
                                Text("Reset")
                            }
                        }
                    }
                }

                ExportJobState.Idle -> Unit
            }

            // Configuration Controls (shown when not actively exporting)
            if (!isJobActive) {
                val settings = project.exportSettings

                // Render Path Planning Summary
                if (plan != null) {
                    StudioPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Rendering Engine",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ExportRenderPath.entries.forEach { pathOption ->
                                    FilterChip(
                                        selected = preferredPath == pathOption,
                                        onClick = { onSelectRenderPath(pathOption) },
                                        label = { Text(pathOption.shortTag) },
                                        modifier = Modifier.testTag("export_path_${pathOption.name.lowercase()}"),
                                    )
                                }
                            }
                            Text(
                                text = plan.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Resolution Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Resolution",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExportResolutionPreset.entries.forEach { preset ->
                            val supported = hardwareCapabilities.supportsResolution(preset.shortSidePx)
                            FilterChip(
                                selected = settings.resolutionHeight == preset.shortSidePx,
                                enabled = supported,
                                onClick = {
                                    onUpdateSettings(settings.copy(resolutionHeight = preset.shortSidePx))
                                },
                                label = {
                                    Text(if (supported) preset.label else "${preset.label} (N/A)")
                                },
                                modifier = Modifier.testTag("export_res_${preset.shortSidePx}"),
                            )
                        }
                    }
                }

                // Frame Rate Selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Frame Rate",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FrameRateOption.entries.forEach { option ->
                            FilterChip(
                                selected = settings.fps == option.fps,
                                onClick = { onUpdateSettings(settings.copy(fps = option.fps)) },
                                label = { Text("${option.fps} FPS") },
                                modifier = Modifier.testTag("export_fps_${option.fps}"),
                            )
                        }
                    }
                }

                // Codec & Audio Bitrate Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Video Codec",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExportVideoCodec.entries.forEach { codec ->
                                val enabled = codec == ExportVideoCodec.H264 || hardwareCapabilities.supportsHevc
                                FilterChip(
                                    selected = ExportVideoCodec.fromLabel(settings.codec) == codec,
                                    enabled = enabled,
                                    onClick = { onUpdateSettings(settings.copy(codec = codec.label)) },
                                    label = { Text(codec.label) },
                                    modifier = Modifier.testTag("export_codec_${codec.name.lowercase()}"),
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Audio AAC",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ExportAudioBitrateOption.entries.forEach { opt ->
                                FilterChip(
                                    selected = settings.audioBitrateKbps == opt.kbps,
                                    onClick = { onUpdateSettings(settings.copy(audioBitrateKbps = opt.kbps)) },
                                    label = { Text("${opt.kbps}k") },
                                    modifier = Modifier.testTag("export_audio_${opt.kbps}"),
                                )
                            }
                        }
                    }
                }

                // Output Spec Summary Card & Primary Export CTA
                if (plan != null) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = studio.elevatedPanel,
                        border = BorderStroke(1.dp, studio.hairlineBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.HighQuality,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "${plan.outputWidth}×${plan.outputHeight} • ${plan.fps} FPS • ${plan.codec.label}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Text(
                                    text = "~${String.format(Locale.US, "%.1f", plan.estimatedFileSizeMb)} MB",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            val vMbps = String.format(Locale.US, "%.1f", plan.videoBitrateBps / 1_000_000f)
                            val aStr = if (plan.hasAudio) "AAC ${plan.audioBitrateBps / 1000} kbps" else "No Audio"
                            Text(
                                text = "Duration ${TimeFormatting.formatDurationLabel(plan.totalDurationMs)} • Video ${vMbps} Mbps • $aStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (plan.blockingError != null) {
                                Text(
                                    text = plan.blockingError,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = onStartExport,
                    enabled = plan?.isExportable == true,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("start_export_button"),
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Start export",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export MP4")
                }
            }

            // Recent Project Exports
            if (exportHistory.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Recent Exports (${exportHistory.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    exportHistory.take(5).forEach { artifact ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = studio.elevatedPanel,
                            border = BorderStroke(1.dp, studio.hairlineBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("export_history_${artifact.id}"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = artifact.fileName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${artifact.width}×${artifact.height} • ${artifact.fps}fps • ${artifact.codec} • ${formatMegabytes(artifact.fileSizeBytes)} • ${artifact.renderPathUsed}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = { saveArtifactAction(artifact) },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SaveAlt,
                                        contentDescription = "Save ${artifact.fileName}",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                IconButton(
                                    onClick = { shareArtifactAction(artifact) },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share ${artifact.fileName}",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteArtifact(artifact.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete ${artifact.fileName}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 0.1) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}
