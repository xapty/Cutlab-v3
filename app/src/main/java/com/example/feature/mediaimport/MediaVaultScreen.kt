package com.example.feature.mediaimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.EmptyStudioState
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.feature.project.MediaAssetRowCard
import com.example.ui.theme.LocalStudioColors

@Composable
fun MediaVaultScreen(
    viewModel: MediaVaultViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var inspectedAsset by remember { mutableStateOf<MediaAsset?>(null) }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.importStandaloneMedia(uris, MediaType.VIDEO) }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.importStandaloneMedia(uris, MediaType.IMAGE) }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importStandaloneMedia(uris, MediaType.AUDIO) }

    LaunchedEffect(viewModel) {
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
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Media Vault",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Managed video, image & audio assets across CutLab projects",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        videoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_import_video"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import Video")
                }
                OutlinedButton(
                    onClick = {
                        imageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_import_image"),
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import Photo")
                }
                OutlinedButton(
                    onClick = { audioLauncher.launch(arrayOf("audio/*")) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("vault_import_audio"),
                ) {
                    Icon(
                        Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = LocalStudioColors.current.audioAmber,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Import Audio")
                }
            }

            if (uiState.isImporting) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaType.entries.forEach { type ->
                    FilterChip(
                        selected = uiState.filter == type,
                        onClick = { viewModel.toggleFilter(type) },
                        label = { Text(type.label) },
                        modifier = Modifier.testTag("vault_filter_${type.name.lowercase()}"),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (uiState.assets.isEmpty()) {
                EmptyStudioState(
                    icon = Icons.Default.PermMedia,
                    title = "No Managed Media Yet",
                    subtitle = "Import video clips, still images, or audio stems using Android Photo Picker or Storage Access Framework.",
                    actionLabel = "Import Video",
                    actionTestTag = "vault_empty_import_button",
                    onAction = {
                        videoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(uiState.assets, key = { it.id }) { asset ->
                        MediaAssetRowCard(
                            asset = asset,
                            isSelected = inspectedAsset?.id == asset.id,
                            onSelect = { inspectedAsset = asset },
                            onDelete = { viewModel.deleteAsset(asset.id) },
                        )
                    }
                }
            }
        }
    }

    inspectedAsset?.let { asset ->
        AlertDialog(
            onDismissRequest = { inspectedAsset = null },
            title = { Text(asset.displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetadataLine("Type", asset.type.label)
                    MetadataLine("MIME", asset.mimeType)
                    if (asset.resolutionLabel.isNotEmpty()) {
                        MetadataLine("Dimensions", asset.resolutionLabel)
                    }
                    if (asset.durationMs > 0L) {
                        MetadataLine("Duration", TimeFormatting.formatDurationLabel(asset.durationMs))
                    }
                    if (asset.fps > 0f) {
                        MetadataLine("Frame Rate", "${asset.fps.toInt()} FPS")
                    }
                    MetadataLine("File Size", TimeFormatting.formatFileSize(asset.fileSizeBytes))
                    MetadataLine("Status", asset.status.label)
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectedAsset = null }) {
                    Text("Close")
                }
            },
        )
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
