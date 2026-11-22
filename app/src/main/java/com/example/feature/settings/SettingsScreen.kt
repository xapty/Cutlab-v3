package com.example.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.core.performance.BenchmarkRecord
import com.example.core.performance.PreviewQualityMode
import com.example.core.performance.ThermalTier
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.FrameRateOption
import com.example.ui.theme.LocalStudioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val studio = LocalStudioColors.current

    LaunchedEffect(Unit) {
        viewModel.refreshStorageFootprint()
        viewModel.refreshDiagnostics()
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Studio Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                val crashSummary = uiState.performance.lastCrashSummary
                AnimatedVisibility(visible = !crashSummary.isNullOrBlank()) {
                    StudioPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("crash_recovery_banner"),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.WarningAmber,
                                        contentDescription = "Crash diagnostic",
                                        tint = studio.audioAmber,
                                    )
                                    Text(
                                        text = "Previous Session Diagnostic",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = studio.audioAmber,
                                    )
                                }
                                TextButton(
                                    onClick = viewModel::dismissCrashReport,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("dismiss_crash_banner_button"),
                                ) {
                                    Text("Dismiss")
                                }
                            }
                            Text(
                                text = crashSummary.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                StudioPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "New Project Defaults",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Text(
                            text = "Default Aspect Ratio",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AspectRatioPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = uiState.preferences.defaultAspectRatio == preset,
                                    onClick = { viewModel.setDefaultAspectRatio(preset) },
                                    label = { Text(preset.label) },
                                    modifier = Modifier.testTag("settings_ratio_${preset.name.lowercase()}"),
                                )
                            }
                        }

                        Text(
                            text = "Default Frame Rate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FrameRateOption.entries.forEach { option ->
                                FilterChip(
                                    selected = uiState.preferences.defaultFps == option.fps,
                                    onClick = { viewModel.setDefaultFps(option.fps) },
                                    label = { Text(option.label) },
                                    modifier = Modifier.testTag("settings_fps_${option.fps}"),
                                )
                            }
                        }

                        Text(
                            text = "Default Editing Mode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EditorMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = uiState.preferences.defaultEditorMode == mode,
                                    onClick = { viewModel.setDefaultEditorMode(mode) },
                                    label = { Text(mode.label) },
                                    modifier = Modifier.testTag("settings_mode_${mode.name.lowercase()}"),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-assign imported media to foundation tracks",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Appends imported video/photo to V1 and audio to A1",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = uiState.preferences.autoAppendImportedToTimeline,
                                onCheckedChange = viewModel::setAutoAppendImported,
                                modifier = Modifier.testTag("settings_auto_append_switch"),
                            )
                        }
                    }
                }

                // Performance, Thermal & Cache Optimization (Phase 10)
                StudioPanel(modifier = Modifier.fillMaxWidth()) {
                    val perf = uiState.performance
                    val thermalColor = when (perf.thermalTier) {
                        ThermalTier.NOMINAL, ThermalTier.LIGHT -> studio.readyEmerald
                        ThermalTier.MODERATE -> studio.audioAmber
                        ThermalTier.SEVERE, ThermalTier.CRITICAL -> MaterialTheme.colorScheme.error
                    }
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Speed,
                                    contentDescription = "Performance diagnostics",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Performance & Thermal Engine",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = viewModel::refreshDiagnostics,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("refresh_diagnostics_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Refresh device telemetry",
                                )
                            }
                        }

                        Text(
                            text = "Adaptive Preview Resolution",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PreviewQualityMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = uiState.preferences.previewQualityMode == mode,
                                    onClick = { viewModel.setPreviewQualityMode(mode) },
                                    label = { Text(mode.label) },
                                    modifier = Modifier.testTag("settings_preview_quality_${mode.name.lowercase()}"),
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                        imageVector = Icons.Filled.Memory,
                                        contentDescription = "Memory footprint",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "JVM Heap Headroom",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "${perf.usedHeapMb} / ${perf.maxHeapMb} MB (${perf.heapUsagePercent}%)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (perf.heapUsagePercent / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                            )
                        }

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
                                    imageVector = Icons.Filled.Thermostat,
                                    contentDescription = "Thermal state",
                                    modifier = Modifier.size(16.dp),
                                    tint = thermalColor,
                                )
                                Text(
                                    text = if (perf.thermalApiSupported) {
                                        "Thermal State (OS Telemetry)"
                                    } else {
                                        "Thermal State (API 28 Safe Fallback)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StudioBadge(
                                text = "${perf.thermalTier.label} • ${(perf.effectivePreviewScale * 100).toInt()}%",
                                containerColor = thermalColor.copy(alpha = 0.16f),
                                contentColor = thermalColor,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Preview GL FPS: ${
                                    if (perf.previewFps > 0f) "%.1f FPS (%.1f ms)".format(perf.previewFps, perf.lastPreviewFrameMs) else "Idle"
                                }",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Trim events: ${perf.trimEventsHandled}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = viewModel::clearRuntimeCaches,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("clear_caches_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Trim Caches")
                            }
                            Button(
                                onClick = viewModel::runDeviceBenchmark,
                                enabled = !perf.isBenchmarkRunning,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("run_benchmark_button"),
                            ) {
                                if (perf.isBenchmarkRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Benchmarking...")
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Speed,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Run Benchmark")
                                }
                            }
                        }

                        if (uiState.benchmarks.isNotEmpty()) {
                            HorizontalDivider()
                            Text(
                                text = "Measured Device & Export Benchmarks",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.testTag("benchmark_results_list"),
                            ) {
                                uiState.benchmarks.take(5).forEach { rec ->
                                    BenchmarkResultRow(record = rec)
                                }
                            }
                        }
                    }
                }

                StudioPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Managed Media Storage",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Prefer 540p editing proxies in preview",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Exports always render from full-resolution originals",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = uiState.preferences.preferProxyPlayback,
                                onCheckedChange = viewModel::setPreferProxyPlayback,
                                modifier = Modifier.testTag("settings_prefer_proxy_switch"),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Private project vault & proxy footprint",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = TimeFormatting.formatFileSize(uiState.managedStorageBytes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        OutlinedButton(
                            onClick = viewModel::pruneOrphanedMedia,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("prune_storage_button"),
                        ) {
                            Text("Clean Orphaned Cache")
                        }
                    }
                }

                // Device Compatibility & Release Verification Card
                StudioPanel(modifier = Modifier.fillMaxWidth()) {
                    val caps = uiState.hardwareCapabilities
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Verified,
                                    contentDescription = "Release verified",
                                    tint = studio.readyEmerald,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "CutLab Native Video Studio",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            StudioBadge(
                                text = "v1.0.0",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text(
                            text = uiState.performance.deviceSummary,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = buildString {
                                append("Hardware Codecs: ")
                                append(if (caps.supportsAvc) "H.264/AVC" else "No AVC")
                                if (caps.supportsHevc) append(" • H.265/HEVC")
                                append(" • Max short-side ${caps.maxSupportedShortSidePx}px")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Multi-track GLSL compositor, Bezier keyframes, PCM mixer, dual-path Media3 + OpenGL ES exporter, 3D LUTs (.cube), Chroma Key, Speed Ramping, Motion Tracking, Stabilization, 540p editing proxies, and thermal/memory scaling (API 28–36).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun BenchmarkResultRow(record: BenchmarkRecord) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val studio = LocalStudioColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${record.kind.label} • ${record.renderPath}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StudioBadge(
                text = "${"%.1f".format(record.fps)} FPS",
                containerColor = if (record.validated) studio.readyEmerald.copy(alpha = 0.16f) else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (record.validated) studio.readyEmerald else MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Text(
            text = "${record.label} • ${record.durationMs}ms (${"%.2f".format(record.frameAvgMs)} ms/frame, ${"%.1f".format(record.throughputMbps)} Mbps, ${record.peakHeapMb}MB heap, ${record.thermalTier}) @ ${formatter.format(Date(record.timestampMs))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
