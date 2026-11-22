package com.example.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.EditorModePill
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioHeaderMark
import com.example.core.designsystem.StudioPanel
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.ProjectSummary
import com.example.ui.theme.LocalStudioColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenProject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var renamingProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var deletingProject by remember { mutableStateOf<ProjectSummary?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.OpenProject -> {
                    showCreateDialog = false
                    onOpenProject(event.projectId)
                }
                is HomeEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudioHeaderMark()
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("new_project_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Project")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                placeholder = { Text("Search projects...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.onSearchQueryChanged("") },
                            modifier = Modifier.testTag("clear_search_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("project_search_input"),
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditorMode.entries.forEach { mode ->
                    FilterChip(
                        selected = uiState.selectedModeFilter == mode,
                        onClick = { viewModel.onModeFilterSelected(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier.testTag("filter_mode_${mode.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                AspectRatioPreset.entries.forEach { ratio ->
                    FilterChip(
                        selected = uiState.selectedRatioFilter == ratio,
                        onClick = { viewModel.onRatioFilterSelected(ratio) },
                        label = {
                            Text(
                                text = ratio.label,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        modifier = Modifier.testTag("filter_ratio_${ratio.name.lowercase()}"),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!uiState.isLoading && uiState.projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val hasActiveFilters = uiState.searchQuery.isNotBlank() ||
                        uiState.selectedModeFilter != null ||
                        uiState.selectedRatioFilter != null
                    EmptyStudioState(
                        icon = Icons.Default.MovieCreation,
                        title = if (hasActiveFilters) {
                            "No matching projects"
                        } else {
                            "Your CutLab Studio is Ready"
                        },
                        subtitle = if (hasActiveFilters) {
                            "Try clearing your search or aspect-ratio filters."
                        } else {
                            "Create a non-destructive Quick Edit or Advanced Edit project and import video, photos, and audio."
                        },
                        actionLabel = if (hasActiveFilters) "Reset Filters" else "Create Project",
                        actionTestTag = "empty_state_action_button",
                        onAction = {
                            if (hasActiveFilters) {
                                viewModel.onSearchQueryChanged("")
                                uiState.selectedModeFilter?.let(viewModel::onModeFilterSelected)
                                uiState.selectedRatioFilter?.let(viewModel::onRatioFilterSelected)
                            } else {
                                showCreateDialog = true
                            }
                        },
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 290.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(uiState.projects, key = { it.document.id }) { summary ->
                        ProjectSummaryCard(
                            summary = summary,
                            onClick = { onOpenProject(summary.document.id) },
                            onRename = { renamingProject = summary },
                            onDuplicate = { viewModel.duplicateProject(summary.document.id) },
                            onSwitchMode = {
                                viewModel.switchProjectMode(
                                    summary.document.id,
                                    summary.document.editorMode,
                                )
                            },
                            onDelete = { deletingProject = summary },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            defaultAspectRatio = uiState.preferences.defaultAspectRatio,
            defaultFps = uiState.preferences.defaultFps,
            defaultEditorMode = uiState.preferences.defaultEditorMode,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, ratio, fps, mode ->
                viewModel.createProject(name, ratio, fps, mode)
            },
        )
    }

    renamingProject?.let { target ->
        RenameProjectDialog(
            currentName = target.document.name,
            onDismiss = { renamingProject = null },
            onConfirm = { newName ->
                viewModel.renameProject(target.document.id, newName)
                renamingProject = null
            },
        )
    }

    deletingProject?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingProject = null },
            title = { Text("Delete Project?") },
            text = {
                Text(
                    "\"${target.document.name}\" and its managed media copies will be permanently removed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProject(target.document.id)
                        deletingProject = null
                    },
                    modifier = Modifier.testTag("confirm_delete_project"),
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProject = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ProjectSummaryCard(
    summary: ProjectSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onSwitchMode: () -> Unit,
    onDelete: () -> Unit,
) {
    val studio = LocalStudioColors.current
    val doc = summary.document
    var menuExpanded by remember { mutableStateOf(false) }

    StudioPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .testTag("project_card_${doc.id}"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(142.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            studio.elevatedPanel,
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (summary.coverMediaUri != null) {
                AsyncImage(
                    model = summary.coverMediaUri,
                    contentDescription = "${doc.name} cover preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                                ),
                            ),
                        ),
                )
            } else {
                val frameRatio = (doc.canvasWidth.toFloat() / doc.canvasHeight.toFloat())
                    .coerceIn(0.45f, 2.3f)
                Box(
                    modifier = Modifier
                        .height(78.dp)
                        .aspectRatio(frameRatio)
                        .border(
                            width = 1.5.dp,
                            color = studio.hairlineBorder,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            RoundedCornerShape(6.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = doc.aspectRatio.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EditorModePill(mode = doc.editorMode)
                StudioBadge(
                    text = "${doc.aspectRatio.label} • ${doc.fps}FPS",
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onBackground,
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            ) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("project_menu_${doc.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Project options",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = {
                            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            val nextLabel = if (doc.editorMode == EditorMode.QUICK) {
                                "Switch to Advanced Edit"
                            } else {
                                "Switch to Quick Edit"
                            }
                            Text(nextLabel)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onSwitchMode()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = doc.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val assetDescriptor = if (summary.mediaCount == 0) {
                    "No media yet"
                } else {
                    buildString {
                        if (summary.videoCount > 0) append("${summary.videoCount}V ")
                        if (summary.imageCount > 0) append("${summary.imageCount}I ")
                        if (summary.audioCount > 0) append("${summary.audioCount}A")
                    }.trim() + " • " + TimeFormatting.formatDurationLabel(summary.totalMediaDurationMs)
                }
                Text(
                    text = assetDescriptor,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatTimestamp(doc.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RenameProjectDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Project") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Project Name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_project_input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("confirm_rename_project"),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    return sdf.format(Date(millis))
}
