package com.example.feature.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.MediaTypeBadge
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.BezierCurveSpec
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ChromaKeySpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipAudioEffect
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.ClipTransition
import com.example.engine.project.EditorMode
import com.example.engine.project.FrameRateOption
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.MotionPreset
import com.example.engine.project.ProjectDocument
import com.example.engine.project.SpeedPoint
import com.example.engine.project.SpeedRampPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineTrack
import com.example.engine.timeline.TimelineOverlapRepairMode
import com.example.engine.timeline.TimelineOverlapRepairReport
import com.example.feature.export.ExportStudioSheet
import com.example.feature.timeline.TimelineEditorPane
import com.example.ui.theme.LocalStudioColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    viewModel: ProjectWorkspaceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showOverlapRepairDialog by rememberSaveable { mutableStateOf(false) }
    var selectedRepairMode by rememberSaveable {
        mutableStateOf(TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK)
    }
    var showMediaSheet by rememberSaveable { mutableStateOf(false) }
    var showInspectorSheet by rememberSaveable { mutableStateOf(false) }
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    var activeQuickTool by rememberSaveable { mutableStateOf(QuickToolSheet.NONE) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.importMedia(uris, MediaType.VIDEO) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.importMedia(uris, MediaType.IMAGE) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importMedia(uris, MediaType.AUDIO) }

    val anyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importMedia(uris, null) }

    val cubeLutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importCustomCubeLut(uri)
        }
    }

    val txtLyricPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importLyricsFromUri(uri)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val project = uiState.project

    val onImportVideo = {
        videoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
        )
    }
    val onImportImage = {
        imagePickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    val onImportAudio = {
        audioPickerLauncher.launch(arrayOf("audio/*"))
    }
    val onImportFile = {
        anyFilePickerLauncher.launch(arrayOf("video/*", "image/*", "audio/*"))
    }
    val onImportCubeLut = {
        cubeLutPickerLauncher.launch(arrayOf("*/*"))
    }
    val onImportTxtLyrics = {
        txtLyricPickerLauncher.launch(arrayOf("text/plain", "text/*"))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("workspace_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to projects",
                        )
                    }
                },
                title = {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = project != null && !uiState.isReadOnlyFutureVersion) {
                                showRenameDialog = true
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = project?.name ?: "Loading...",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (project != null) {
                            Text(
                                text = "${project.aspectRatio.label} • ${project.fps} FPS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::undo,
                        enabled = uiState.canUndo,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("editor_undo_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = uiState.undoLabel?.let { "Undo $it" } ?: "Undo",
                            tint = if (uiState.canUndo) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        )
                    }
                    IconButton(
                        onClick = viewModel::redo,
                        enabled = uiState.canRedo,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("editor_redo_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = uiState.redoLabel?.let { "Redo $it" } ?: "Redo",
                            tint = if (uiState.canRedo) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        )
                    }
                    if (project != null) {
                        WorkspaceModeSwitcher(
                            currentMode = project.editorMode,
                            onModeSelected = { mode ->
                                if (!uiState.isReadOnlyFutureVersion) {
                                    viewModel.switchEditorMode(mode)
                                }
                            },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Button(
                            onClick = {
                                viewModel.refreshExportHistory()
                                showExportSheet = true
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .height(36.dp)
                                .testTag("workspace_export_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "Export video",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Export",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (project == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.width(180.dp))
                } else {
                    EmptyStudioState(
                        icon = Icons.Default.Movie,
                        title = "Project Not Found",
                        subtitle = "This project may have been deleted.",
                        actionLabel = "Back to Library",
                        onAction = onNavigateBack,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (uiState.isReadOnlyFutureVersion) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("future_schema_readonly_banner"),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "Read-Only (Schema v${uiState.detectedSchemaVersion}): Newer CutLab format detected. Editing and saving are disabled to protect unknown project data.",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                uiState.pendingOverlapRepair?.let { repairReport ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("legacy_overlap_repair_banner"),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Overlaps detected (${repairReport.affectedClipCount} clip(s) on ${repairReport.affectedTrackCount} track(s)). Timing preserved until reviewed.",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    selectedRepairMode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK
                                    showOverlapRepairDialog = true
                                },
                                modifier = Modifier.testTag("repair_overlaps_button"),
                            ) {
                                Text("Review & Repair")
                            }
                        }
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                val isLandscapeWorkspace = maxWidth >= 700.dp

                if (isLandscapeWorkspace) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(0.50f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TimelinePreviewMonitor(
                                project = project,
                                activeFrame = uiState.activeFrame,
                                assetsById = uiState.assetsById,
                                playheadMs = uiState.playheadMs,
                                totalDurationMs = uiState.totalDurationMs,
                                isPlaying = uiState.isPlaying,
                                preferProxyPlayback = uiState.preferProxyPlayback,
                                stageHeight = 148.dp,
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onStepFrame = viewModel::stepFrame,
                                onJumpStart = viewModel::jumpToStart,
                                onJumpEnd = viewModel::jumpToEnd,
                                modifier = Modifier
                                    .weight(0.56f)
                                    .fillMaxHeight(),
                            )
                            SideInspectorPanel(
                                viewModel = viewModel,
                                uiState = uiState,
                                project = project,
                                onSelectTab = viewModel::setInspectorTab,
                                onFilterToggle = viewModel::toggleMediaFilter,
                                onImportVideo = onImportVideo,
                                onImportImage = onImportImage,
                                onImportAudio = onImportAudio,
                                onImportFile = onImportFile,
                                onImportTxtLyrics = onImportTxtLyrics,
                                onInsertAsset = { assetId ->
                                    viewModel.insertAssetToTimeline(assetId, atPlayhead = false)
                                },
                                onDeleteAsset = viewModel::deleteAsset,
                                onSplitAtPlayhead = viewModel::splitAtPlayhead,
                                onDuplicateClip = viewModel::duplicateSelectedClip,
                                onRemoveSelectedClips = viewModel::removeSelectedClips,
                                onRippleDeleteClip = viewModel::rippleDeleteSelectedClip,
                                onNudgeTrimLeft = viewModel::nudgeTrimLeft,
                                onNudgeTrimRight = viewModel::nudgeTrimRight,
                                onJumpToClip = { clip -> viewModel.seekTo(clip.timelineStartMs) },
                                onToggleTrackMute = viewModel::toggleTrackMute,
                                onToggleTrackLock = viewModel::toggleTrackLock,
                                onReorderTrack = viewModel::reorderTrack,
                                onRemoveTrack = viewModel::removeTrack,
                                onUpdateCanvas = viewModel::updateCanvasSpecs,
                                onAddTextClip = { preset -> viewModel.addTextClip(preset, atPlayhead = true) },
                                onAddPresetTextClip = { spec, label ->
                                    viewModel.addCustomTextClip(spec, label, atPlayhead = true)
                                },
                                onUpdateTextSpec = viewModel::updateSelectedTextSpec,
                                onPreviewTextSpec = viewModel::previewSelectedTextSpec,
                                onUpdateAdjustments = viewModel::updateSelectedClipAdjustments,
                                onPreviewAdjustments = viewModel::previewSelectedClipAdjustments,
                                onApplyFilterToAll = viewModel::applySelectedAdjustmentsToAll,
                                onUpdateTransition = viewModel::updateSelectedClipTransition,
                                onPreviewTransition = viewModel::previewSelectedClipTransition,
                                onUpdateSpeed = viewModel::updateSelectedClipSpeed,
                                onUpdateSpeedRamp = viewModel::updateSelectedClipSpeedRamp,
                                onToggleReverse = viewModel::toggleSelectedClipReverse,
                                onUpdateAudio = viewModel::updateSelectedClipAudio,
                                onPreviewAudio = viewModel::previewSelectedClipAudio,
                                onUpdateAudioEffect = viewModel::updateSelectedClipAudioEffect,
                                onPreviewAudioEffect = viewModel::previewSelectedClipAudioEffect,
                                onAddMarkerAtPlayhead = viewModel::addBeatMarkerAtPlayhead,
                                onDetectBeatsForClip = viewModel::detectBeatsForSelectedClip,
                                onRemoveMarker = viewModel::removeTimelineMarker,
                                onClearMarkers = viewModel::clearAllTimelineMarkers,
                                onJumpToTime = viewModel::seekTo,
                                onAuditionPcmMix = viewModel::toggleAuditionPcmMix,
                                onAddAdjustmentClip = { viewModel.addAdjustmentClip(atPlayhead = true) },
                                onUpdateTransform = viewModel::updateSelectedClipTransform,
                                onPreviewTransform = viewModel::previewSelectedClipTransform,
                                onUpdateBlendMode = viewModel::updateSelectedClipBlendMode,
                                onUpdateMask = viewModel::updateSelectedClipMask,
                                onPreviewMask = viewModel::previewSelectedClipMask,
                                onUpdateShaderEffect = viewModel::updateSelectedClipShaderEffect,
                                onPreviewShaderEffect = viewModel::previewSelectedClipShaderEffect,
                                onUpdateChromaKey = viewModel::updateSelectedClipChromaKey,
                                onPreviewChromaKey = viewModel::previewSelectedClipChromaKey,
                                onUpdateLut = viewModel::updateSelectedClipLut,
                                onPreviewLut = viewModel::previewSelectedClipLut,
                                onImportCubeLut = onImportCubeLut,
                                onRunMotionTrack = viewModel::runMotionTrackingOnSelectedClip,
                                onRunStabilization = viewModel::runStabilizationOnSelectedClip,
                                onDisableStabilization = viewModel::disableStabilizationOnSelectedClip,
                                onSetPreferProxyPlayback = viewModel::setPreferProxyPlayback,
                                onGenerateProxy = viewModel::generateProxyForSelectedAsset,
                                onCancelProxy = viewModel::cancelActiveProxyJob,
                                onDeleteProxy = viewModel::deleteProxyForSelectedAsset,
                                onSelectKeyframeProperty = viewModel::selectKeyframeProperty,
                                onSelectKeyframe = viewModel::selectKeyframe,
                                onJumpToClipRelativeTime = viewModel::jumpToClipRelativeTime,
                                onToggleKeyframeAtPlayhead = viewModel::toggleKeyframeAtPlayhead,
                                onPreviewValueAtPlayhead = viewModel::previewValueAtPlayhead,
                                onPreviewMoveKeyframe = viewModel::previewMoveKeyframe,
                                onPreviewBezierHandle = viewModel::previewBezierHandle,
                                onUpdateKeyframesInterpolation = viewModel::updateSelectedKeyframesInterpolation,
                                onDeleteSelectedKeyframes = viewModel::deleteSelectedKeyframes,
                                onClearPropertyKeyframes = viewModel::clearPropertyKeyframes,
                                onApplyMotionPreset = viewModel::applyMotionPreset,
                                onCommitSliderEdit = viewModel::commitClipGesture,
                                modifier = Modifier
                                    .weight(0.44f)
                                    .fillMaxHeight(),
                            )
                        }

                        TimelineEditorPane(
                            project = project,
                            assetsById = uiState.assetsById,
                            waveformsByAssetId = uiState.waveformsByAssetId,
                            playheadMs = uiState.playheadMs,
                            totalDurationMs = uiState.totalDurationMs,
                            selectedClipIds = uiState.selectedClipIds,
                            selectedTrackId = uiState.selectedTrack?.id,
                            timelineZoom = uiState.timelineZoom,
                            snappingEnabled = uiState.snappingEnabled,
                            activeSnapGuideMs = uiState.activeSnapGuideMs,
                            onSeekMs = viewModel::seekTo,
                            onSelectClip = viewModel::selectClip,
                            onSelectTrack = viewModel::selectTrack,
                            onToggleTrackMute = viewModel::toggleTrackMute,
                            onToggleTrackLock = viewModel::toggleTrackLock,
                            onReorderTrack = viewModel::reorderTrack,
                            onAddTrack = viewModel::addTrack,
                            onZoomChange = viewModel::setTimelineZoom,
                            onToggleSnapping = viewModel::toggleSnapping,
                            onBeginClipGesture = viewModel::beginClipGesture,
                            onPreviewTrimLeft = viewModel::previewTrimLeft,
                            onPreviewTrimRight = viewModel::previewTrimRight,
                            onPreviewMoveClip = viewModel::previewMoveClip,
                            onCommitClipGesture = viewModel::commitClipGesture,
                            onCancelClipGesture = viewModel::cancelClipGesture,
                            modifier = Modifier
                                .weight(0.50f)
                                .fillMaxWidth(),
                        )
                    }
                } else {
                    // Portrait Workspace
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TimelinePreviewMonitor(
                            project = project,
                            activeFrame = uiState.activeFrame,
                            assetsById = uiState.assetsById,
                            playheadMs = uiState.playheadMs,
                            totalDurationMs = uiState.totalDurationMs,
                            isPlaying = uiState.isPlaying,
                            preferProxyPlayback = uiState.preferProxyPlayback,
                            stageHeight = 186.dp,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onStepFrame = viewModel::stepFrame,
                            onJumpStart = viewModel::jumpToStart,
                            onJumpEnd = viewModel::jumpToEnd,
                        )

                        TimelineEditorPane(
                            project = project,
                            assetsById = uiState.assetsById,
                            waveformsByAssetId = uiState.waveformsByAssetId,
                            playheadMs = uiState.playheadMs,
                            totalDurationMs = uiState.totalDurationMs,
                            selectedClipIds = uiState.selectedClipIds,
                            selectedTrackId = uiState.selectedTrack?.id,
                            timelineZoom = uiState.timelineZoom,
                            snappingEnabled = uiState.snappingEnabled,
                            activeSnapGuideMs = uiState.activeSnapGuideMs,
                            onSeekMs = viewModel::seekTo,
                            onSelectClip = viewModel::selectClip,
                            onSelectTrack = viewModel::selectTrack,
                            onToggleTrackMute = viewModel::toggleTrackMute,
                            onToggleTrackLock = viewModel::toggleTrackLock,
                            onReorderTrack = viewModel::reorderTrack,
                            onAddTrack = viewModel::addTrack,
                            onZoomChange = viewModel::setTimelineZoom,
                            onToggleSnapping = viewModel::toggleSnapping,
                            onBeginClipGesture = viewModel::beginClipGesture,
                            onPreviewTrimLeft = viewModel::previewTrimLeft,
                            onPreviewTrimRight = viewModel::previewTrimRight,
                            onPreviewMoveClip = viewModel::previewMoveClip,
                            onCommitClipGesture = viewModel::commitClipGesture,
                            onCancelClipGesture = viewModel::cancelClipGesture,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )

                        PortraitBottomToolbar(
                            mediaCount = uiState.allAssets.size,
                            selectedClipIds = uiState.selectedClipIds,
                            selectedClip = uiState.selectedClip,
                            canSplitAtPlayhead = uiState.canSplitAtPlayhead,
                            onOpenMediaSheet = { showMediaSheet = true },
                            onOpenInspectorSheet = { showInspectorSheet = true },
                            onOpenQuickTool = { activeQuickTool = it },
                            onAddAdjustmentClip = { viewModel.addAdjustmentClip(atPlayhead = true) },
                            onSplitAtPlayhead = viewModel::splitAtPlayhead,
                            onDuplicateClip = viewModel::duplicateSelectedClip,
                            onRippleDeleteClip = viewModel::rippleDeleteSelectedClip,
                            onRemoveSelectedClips = viewModel::removeSelectedClips,
                        )
                    }
                }
            }
        }
    }

    if (showMediaSheet && project != null) {
        ModalBottomSheet(
            onDismissRequest = { showMediaSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Project Media Bin (${uiState.allAssets.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ImportActionStrip(
                    onImportVideo = onImportVideo,
                    onImportImage = onImportImage,
                    onImportAudio = onImportAudio,
                    onImportFile = onImportFile,
                )
                MediaFilterChipsRow(
                    activeFilter = uiState.mediaFilter,
                    onFilterToggle = viewModel::toggleMediaFilter,
                )
                if (uiState.filteredAssets.isEmpty()) {
                    EmptyStudioState(
                        icon = Icons.Default.PermMedia,
                        title = "No Media Assets",
                        subtitle = "Import videos, photos, or audio above and tap any asset to place it on the timeline.",
                        actionLabel = "Import Video",
                        onAction = onImportVideo,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.filteredAssets, key = { it.id }) { asset ->
                            MediaAssetRowCard(
                                asset = asset,
                                isSelected = uiState.selectedClipAsset?.id == asset.id,
                                onSelect = {
                                    viewModel.insertAssetToTimeline(asset.id, atPlayhead = false)
                                    showMediaSheet = false
                                },
                                onDelete = { viewModel.deleteAsset(asset.id) },
                                actionLabel = "+ Timeline",
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInspectorSheet && project != null) {
        ModalBottomSheet(
            onDismissRequest = { showInspectorSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InspectorDetailsContent(
                    uiState = uiState,
                    project = project,
                    onSplitAtPlayhead = viewModel::splitAtPlayhead,
                    onDuplicateClip = viewModel::duplicateSelectedClip,
                    onRemoveSelectedClips = {
                        viewModel.removeSelectedClips()
                        showInspectorSheet = false
                    },
                    onRippleDeleteClip = {
                        viewModel.rippleDeleteSelectedClip()
                        showInspectorSheet = false
                    },
                    onNudgeTrimLeft = viewModel::nudgeTrimLeft,
                    onNudgeTrimRight = viewModel::nudgeTrimRight,
                    onJumpToClip = { clip ->
                        viewModel.seekTo(clip.timelineStartMs)
                        showInspectorSheet = false
                    },
                    onToggleTrackMute = viewModel::toggleTrackMute,
                    onToggleTrackLock = viewModel::toggleTrackLock,
                    onReorderTrack = viewModel::reorderTrack,
                    onRemoveTrack = viewModel::removeTrack,
                    onUpdateCanvas = viewModel::updateCanvasSpecs,
                )
            }
        }
    }

    if (activeQuickTool != QuickToolSheet.NONE && project != null) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.commitClipGesture("Quick Edit")
                activeQuickTool = QuickToolSheet.NONE
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (activeQuickTool) {
                    QuickToolSheet.TEXT -> TextToolPanel(
                        selectedClip = uiState.selectedClip,
                        onAddTextClip = { preset -> viewModel.addTextClip(preset, atPlayhead = true) },
                        onAddPresetTextClip = { spec, label ->
                            viewModel.addCustomTextClip(spec, label, atPlayhead = true)
                        },
                        onUpdateTextSpec = viewModel::updateSelectedTextSpec,
                        onPreviewTextSpec = viewModel::previewSelectedTextSpec,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                        onOpenLyricStudioForClip = {
                            viewModel.loadSelectedClipIntoLyricStudio()
                            activeQuickTool = QuickToolSheet.LYRIC_STUDIO
                        },
                        onOpenAutoCaption = {
                            activeQuickTool = QuickToolSheet.AUTO_CAPTION
                        },
                    )
                    QuickToolSheet.AUTO_CAPTION -> CaptionToolPanel(
                        captionState = uiState.autoCaptionState,
                        assets = uiState.allAssets,
                        fps = project.fps,
                        onSelectSourceAsset = viewModel::selectCaptionSourceAsset,
                        onSelectModel = viewModel::selectAsrModel,
                        onDownloadModel = viewModel::downloadSelectedAsrModel,
                        onCancelModelDownload = viewModel::cancelAsrModelDownload,
                        onDeleteModel = viewModel::deleteAsrModel,
                        onStartTranscription = viewModel::startAutoCaptionTranscription,
                        onCancelTranscription = viewModel::cancelAutoCaptionTranscription,
                        onUpdateMaxCharsPerLine = viewModel::updateCaptionMaxCharsPerLine,
                        onUpdateKaraokeMode = viewModel::updateCaptionKaraokeMode,
                        onUpdateStylePreset = viewModel::updateCaptionStylePreset,
                        onUpdateUseInvertEffect = viewModel::updateCaptionUseInvertEffect,
                        onUpdateCueText = viewModel::updateCaptionCueText,
                        onDeleteCue = viewModel::deleteCaptionCue,
                        onCommitCaptionsToTimeline = {
                            viewModel.commitCaptionsToTimeline()
                            activeQuickTool = QuickToolSheet.NONE
                        },
                    )
                    QuickToolSheet.LYRIC_STUDIO -> LyricStudioPanel(
                        lyricState = uiState.lyricStudioState,
                        assets = uiState.allAssets,
                        waveformsByAssetId = uiState.waveformsByAssetId,
                        playheadMs = uiState.playheadMs,
                        totalDurationMs = uiState.totalDurationMs,
                        fps = project.fps,
                        isPlaying = uiState.isPlaying,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onStepFrame = viewModel::stepFrame,
                        onSeekTo = viewModel::seekTo,
                        onSelectSourceAsset = viewModel::selectLyricSourceAsset,
                        onImportLyricsText = { text -> viewModel.importLyricsText(text, replaceExisting = true) },
                        onRequestImportTxtFile = onImportTxtLyrics,
                        onAddEmptyLine = viewModel::addEmptyLyricLine,
                        onSelectLine = viewModel::selectLyricLine,
                        onSelectWord = viewModel::selectLyricWord,
                        onEditLineText = viewModel::editLyricLineText,
                        onSplitSelectedLine = viewModel::splitSelectedLyricLine,
                        onMergeLineWithNext = viewModel::mergeLyricLineWithNext,
                        onMoveLine = viewModel::moveLyricLine,
                        onDeleteLine = viewModel::deleteLyricLine,
                        onAssignLineStartAtPlayhead = viewModel::assignLyricLineStartAtPlayhead,
                        onAssignLineEndAtPlayhead = viewModel::assignLyricLineEndAtPlayhead,
                        onAssignWordStartAtPlayhead = viewModel::assignLyricWordStartAtPlayhead,
                        onAssignWordEndAtPlayhead = viewModel::assignLyricWordEndAtPlayhead,
                        onStepWordSelection = viewModel::stepLyricWordSelection,
                        onAdjustWordDuration = viewModel::adjustSelectedLyricWordDuration,
                        onEstimateWordTimings = viewModel::distributeSelectedLineWordsAsEstimated,
                        onShiftLyricsOffset = viewModel::shiftLyricsOffset,
                        onResetLyricTiming = viewModel::resetLyricTiming,
                        onSetSnapToFrames = viewModel::setLyricSnapToFrames,
                        onSetSnapToBeats = viewModel::setLyricSnapToBeats,
                        onSetKaraokeMode = viewModel::setLyricKaraokeMode,
                        onSetStylePreset = viewModel::setLyricStylePreset,
                        onSetUseInvertEffect = viewModel::setLyricUseInvertEffect,
                        onCommitLyricsToTimeline = {
                            viewModel.commitLyricsToTimeline()
                            activeQuickTool = QuickToolSheet.NONE
                        },
                    )
                    QuickToolSheet.INVERT_LYRIC -> InvertLyricAndKaraokeCard(
                        selectedClip = uiState.selectedClip,
                        onAddPresetTextClip = { spec, label ->
                            viewModel.addCustomTextClip(spec, label, atPlayhead = true)
                        },
                        onUpdateTextSpec = viewModel::updateSelectedTextSpec,
                        onPreviewTextSpec = viewModel::previewSelectedTextSpec,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                        onOpenLyricStudioForClip = {
                            viewModel.loadSelectedClipIntoLyricStudio()
                            activeQuickTool = QuickToolSheet.LYRIC_STUDIO
                        },
                        onOpenAutoCaption = {
                            activeQuickTool = QuickToolSheet.AUTO_CAPTION
                        },
                    )
                    QuickToolSheet.FILTERS -> FilterToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateAdjustments = viewModel::updateSelectedClipAdjustments,
                        onPreviewAdjustments = viewModel::previewSelectedClipAdjustments,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                        onApplyFilterToAll = viewModel::applySelectedAdjustmentsToAll,
                    )
                    QuickToolSheet.TRANSITIONS -> TransitionToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateTransition = viewModel::updateSelectedClipTransition,
                        onPreviewTransition = viewModel::previewSelectedClipTransition,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.SPEED -> SpeedToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateSpeed = viewModel::updateSelectedClipSpeed,
                        onUpdateSpeedRamp = viewModel::updateSelectedClipSpeedRamp,
                        onToggleReverse = viewModel::toggleSelectedClipReverse,
                    )
                    QuickToolSheet.AUDIO -> AudioWorkspaceContent(
                        project = project,
                        clip = uiState.selectedClip,
                        asset = uiState.selectedClipAsset,
                        waveform = uiState.selectedClip?.let { uiState.waveformsByAssetId[it.assetId] },
                        mixState = uiState.audioMixState,
                        playheadMs = uiState.playheadMs,
                        isAuditioningMix = uiState.isAuditioningMix,
                        onToggleTrackMute = viewModel::toggleTrackMute,
                        onUpdateAudio = viewModel::updateSelectedClipAudio,
                        onPreviewAudio = viewModel::previewSelectedClipAudio,
                        onUpdateAudioEffect = viewModel::updateSelectedClipAudioEffect,
                        onPreviewAudioEffect = viewModel::previewSelectedClipAudioEffect,
                        onAddMarkerAtPlayhead = viewModel::addBeatMarkerAtPlayhead,
                        onDetectBeatsForClip = viewModel::detectBeatsForSelectedClip,
                        onRemoveMarker = viewModel::removeTimelineMarker,
                        onClearMarkers = viewModel::clearAllTimelineMarkers,
                        onJumpToTime = viewModel::seekTo,
                        onAuditionPcmMix = viewModel::toggleAuditionPcmMix,
                        onCommitGesture = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.RATIO -> RatioToolPanel(
                        project = project,
                        onUpdateCanvas = viewModel::updateCanvasSpecs,
                    )
                    QuickToolSheet.TRANSFORM -> TransformToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateTransform = viewModel::updateSelectedClipTransform,
                        onPreviewTransform = viewModel::previewSelectedClipTransform,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.BLEND_MASK -> BlendAndMaskToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateBlendMode = viewModel::updateSelectedClipBlendMode,
                        onUpdateMask = viewModel::updateSelectedClipMask,
                        onPreviewMask = viewModel::previewSelectedClipMask,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.SHADER_FX -> ShaderToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateShaderEffect = viewModel::updateSelectedClipShaderEffect,
                        onPreviewShaderEffect = viewModel::previewSelectedClipShaderEffect,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.CHROMA_KEY -> ChromaKeyToolPanel(
                        clip = uiState.selectedClip,
                        selectedAsset = uiState.selectedClipAsset,
                        onUpdateChromaKey = viewModel::updateSelectedClipChromaKey,
                        onPreviewChromaKey = viewModel::previewSelectedClipChromaKey,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.LUT_GRADE -> LutGradingToolPanel(
                        clip = uiState.selectedClip,
                        selectedAsset = uiState.selectedClipAsset,
                        onUpdateLut = viewModel::updateSelectedClipLut,
                        onPreviewLut = viewModel::previewSelectedClipLut,
                        onImportCubeLut = onImportCubeLut,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.STABILIZE_TRACK -> StabilizationAndTrackToolPanel(
                        clip = uiState.selectedClip,
                        selectedAsset = uiState.selectedClipAsset,
                        isMotionAnalysisRunning = uiState.isMotionAnalysisRunning,
                        lastTrackedPoints = uiState.lastMotionTrackPoints,
                        onRunMotionTrack = viewModel::runMotionTrackingOnSelectedClip,
                        onRunStabilization = viewModel::runStabilizationOnSelectedClip,
                        onDisableStabilization = viewModel::disableStabilizationOnSelectedClip,
                    )
                    QuickToolSheet.PROXY_MEDIA -> ProxyMediaPanel(
                        selectedAsset = uiState.selectedClipAsset,
                        preferProxyPlayback = uiState.preferProxyPlayback,
                        proxyJobState = uiState.proxyJobState,
                        onSetPreferProxyPlayback = viewModel::setPreferProxyPlayback,
                        onGenerateProxy = viewModel::generateProxyForSelectedAsset,
                        onCancelProxy = viewModel::cancelActiveProxyJob,
                        onDeleteProxy = viewModel::deleteProxyForSelectedAsset,
                    )
                    QuickToolSheet.ANIMATION -> AnimationOverviewContent(
                        clip = uiState.selectedClip,
                        asset = uiState.selectedClipAsset,
                        playheadMs = uiState.playheadMs,
                        selectedProperty = uiState.selectedKeyframeProperty,
                        selectedKeyframeIds = uiState.selectedKeyframeIds,
                        onSelectProperty = viewModel::selectKeyframeProperty,
                        onSelectKeyframe = viewModel::selectKeyframe,
                        onJumpToClipRelativeTime = viewModel::jumpToClipRelativeTime,
                        onToggleKeyframeAtPlayhead = viewModel::toggleKeyframeAtPlayhead,
                        onPreviewValueAtPlayhead = viewModel::previewValueAtPlayhead,
                        onPreviewMoveKeyframe = viewModel::previewMoveKeyframe,
                        onPreviewBezierHandle = viewModel::previewBezierHandle,
                        onUpdateKeyframesInterpolation = viewModel::updateSelectedKeyframesInterpolation,
                        onDeleteSelectedKeyframes = viewModel::deleteSelectedKeyframes,
                        onClearPropertyKeyframes = viewModel::clearPropertyKeyframes,
                        onApplyMotionPreset = viewModel::applyMotionPreset,
                        onCommitGesture = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.NONE -> Unit
                }
            }
        }
    }

    if (showRenameDialog && project != null) {
        var name by rememberSaveable(project.name) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameProject(name)
                        showRenameDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showOverlapRepairDialog && project != null) {
        val previewReport: TimelineOverlapRepairReport? = remember(project, selectedRepairMode) {
            viewModel.inspectOverlapRepairReport(selectedRepairMode)
        }
        if (previewReport != null) {
            AlertDialog(
                onDismissRequest = { showOverlapRepairDialog = false },
                modifier = Modifier.testTag("overlap_repair_confirmation_dialog"),
                title = { Text("Confirm Legacy Overlap Repair") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = if (previewReport.timingPreserved) {
                                "Timing will NOT change: overlapping clips move onto compatible overflow track(s) at their exact timestamps."
                            } else {
                                "Timing WILL change: overlapping clips shift forward sequentially on their original track(s)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (previewReport.timingPreserved) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            FilterChip(
                                selected = selectedRepairMode == TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
                                onClick = {
                                    selectedRepairMode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK
                                },
                                label = { Text("Overflow Track (Default)") },
                                modifier = Modifier.testTag("repair_mode_overflow_chip"),
                            )
                            FilterChip(
                                selected = selectedRepairMode == TimelineOverlapRepairMode.SHIFT_FORWARD_ON_TRACK,
                                onClick = {
                                    selectedRepairMode = TimelineOverlapRepairMode.SHIFT_FORWARD_ON_TRACK
                                },
                                label = { Text("Shift Forward") },
                                modifier = Modifier.testTag("repair_mode_shift_chip"),
                            )
                        }

                        previewReport.affectedClips.forEach { entry ->
                            val clipLabel = uiState.assetsById[entry.assetId]?.displayName
                                ?: "${entry.clipKind.name} (${entry.clipId.take(6)})"
                            val origRange = "${TimeFormatting.formatTimecode(entry.originalStartMs, project.fps)} – " +
                                TimeFormatting.formatTimecode(entry.originalEndMs, project.fps)
                            val proposedRange = "${TimeFormatting.formatTimecode(entry.proposedStartMs, project.fps)} – " +
                                TimeFormatting.formatTimecode(entry.proposedEndMs, project.fps)
                            val timingSummary = if (entry.timingChanged) {
                                "Yes — shifts to $proposedRange"
                            } else {
                                "No — timing preserved"
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(
                                        text = "Affected clip: $clipLabel",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Affected track: ${entry.sourceTrackName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "Original position: $origRange",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "Destination track: ${entry.proposedTrackName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "Timing changes: $timingSummary",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.timingChanged) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.repairLegacyOverlaps(selectedRepairMode)
                            showOverlapRepairDialog = false
                        },
                        modifier = Modifier.testTag("confirm_overlap_repair_button"),
                    ) {
                        Text("Apply Repair")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showOverlapRepairDialog = false },
                        modifier = Modifier.testTag("cancel_overlap_repair_button"),
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
    }

    if (showExportSheet && project != null) {
        ExportStudioSheet(
            project = project,
            plan = uiState.exportPlan,
            hardwareCapabilities = uiState.hardwareCapabilities,
            preferredPath = uiState.preferredExportPath,
            jobState = uiState.exportJobState,
            exportHistory = uiState.projectExports,
            onUpdateSettings = viewModel::updateExportSettings,
            onSelectRenderPath = viewModel::setPreferredExportPath,
            onStartExport = viewModel::startExport,
            onCancelExport = viewModel::cancelExport,
            onResetJobState = viewModel::resetExportState,
            onSaveToGallery = viewModel::saveExportToGallery,
            onSaveToDestinationUri = viewModel::saveExportToUri,
            onCreateShareIntent = viewModel::createShareIntent,
            onDeleteArtifact = viewModel::deleteExportArtifact,
            onDismiss = { showExportSheet = false },
        )
    }
}

@Composable
private fun SideInspectorPanel(
    viewModel: ProjectWorkspaceViewModel,
    uiState: ProjectWorkspaceUiState,
    project: ProjectDocument,
    onSelectTab: (InspectorTab) -> Unit,
    onFilterToggle: (MediaType?) -> Unit,
    onImportVideo: () -> Unit,
    onImportImage: () -> Unit,
    onImportAudio: () -> Unit,
    onImportFile: () -> Unit,
    onImportTxtLyrics: () -> Unit,
    onInsertAsset: (String) -> Unit,
    onDeleteAsset: (String) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDuplicateClip: () -> Unit,
    onRemoveSelectedClips: () -> Unit,
    onRippleDeleteClip: () -> Unit,
    onNudgeTrimLeft: (Long) -> Unit,
    onNudgeTrimRight: (Long) -> Unit,
    onJumpToClip: (TimelineClipRef) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onReorderTrack: (String, Int) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onUpdateCanvas: (AspectRatioPreset, Int) -> Unit,
    onAddTextClip: (TextStylePreset) -> Unit,
    onAddPresetTextClip: (TextOverlaySpec, String) -> Unit,
    onUpdateTextSpec: (TextOverlaySpec) -> Unit,
    onPreviewTextSpec: (TextOverlaySpec) -> Unit,
    onUpdateAdjustments: (ClipAdjustments) -> Unit,
    onPreviewAdjustments: (ClipAdjustments) -> Unit,
    onApplyFilterToAll: () -> Unit,
    onUpdateTransition: (ClipTransition?) -> Unit,
    onPreviewTransition: (ClipTransition?) -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onUpdateSpeedRamp: (SpeedRampPreset, List<SpeedPoint>) -> Unit,
    onToggleReverse: () -> Unit,
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
    onAddAdjustmentClip: () -> Unit,
    onUpdateTransform: (LayerTransform) -> Unit,
    onPreviewTransform: (LayerTransform) -> Unit,
    onUpdateBlendMode: (BlendModePreset) -> Unit,
    onUpdateMask: (LayerMask) -> Unit,
    onPreviewMask: (LayerMask) -> Unit,
    onUpdateShaderEffect: (ClipShaderEffect) -> Unit,
    onPreviewShaderEffect: (ClipShaderEffect) -> Unit,
    onUpdateChromaKey: (ChromaKeySpec) -> Unit,
    onPreviewChromaKey: (ChromaKeySpec) -> Unit,
    onUpdateLut: (ClipLutSpec) -> Unit,
    onPreviewLut: (ClipLutSpec) -> Unit,
    onImportCubeLut: () -> Unit,
    onRunMotionTrack: (Float, Float) -> Unit,
    onRunStabilization: (Float) -> Unit,
    onDisableStabilization: () -> Unit,
    onSetPreferProxyPlayback: (Boolean) -> Unit,
    onGenerateProxy: () -> Unit,
    onCancelProxy: () -> Unit,
    onDeleteProxy: () -> Unit,
    onSelectKeyframeProperty: (KeyframeProperty) -> Unit,
    onSelectKeyframe: (String?, Boolean) -> Unit,
    onJumpToClipRelativeTime: (Long) -> Unit,
    onToggleKeyframeAtPlayhead: (KeyframeProperty) -> Unit,
    onPreviewValueAtPlayhead: (KeyframeProperty, Float) -> Unit,
    onPreviewMoveKeyframe: (KeyframeProperty, String, Long, Float) -> Unit,
    onPreviewBezierHandle: (KeyframeProperty, String, BezierCurveSpec) -> Unit,
    onUpdateKeyframesInterpolation: (KeyframeProperty, KeyframeInterpolation, BezierCurveSpec?) -> Unit,
    onDeleteSelectedKeyframes: (KeyframeProperty) -> Unit,
    onClearPropertyKeyframes: (KeyframeProperty) -> Unit,
    onApplyMotionPreset: (MotionPreset) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, studio.hairlineBorder),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = uiState.inspectorTab.ordinal,
                edgePadding = 8.dp,
                containerColor = studio.elevatedPanel,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                InspectorTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.inspectorTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.testTag("inspector_tab_${tab.name.lowercase()}"),
                    )
                }
            }

            when (uiState.inspectorTab) {
                InspectorTab.MEDIA -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ImportActionStrip(
                            onImportVideo = onImportVideo,
                            onImportImage = onImportImage,
                            onImportAudio = onImportAudio,
                            onImportFile = onImportFile,
                        )
                        MediaFilterChipsRow(
                            activeFilter = uiState.mediaFilter,
                            onFilterToggle = onFilterToggle,
                        )
                        if (uiState.filteredAssets.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Import video, photo, or audio above",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(uiState.filteredAssets, key = { it.id }) { asset ->
                                    MediaAssetRowCard(
                                        asset = asset,
                                        isSelected = uiState.selectedClipAsset?.id == asset.id,
                                        onSelect = { onInsertAsset(asset.id) },
                                        onDelete = { onDeleteAsset(asset.id) },
                                        actionLabel = "+ Track",
                                    )
                                }
                            }
                        }
                    }
                }

                InspectorTab.INSPECTOR -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InspectorDetailsContent(
                            uiState = uiState,
                            project = project,
                            onSplitAtPlayhead = onSplitAtPlayhead,
                            onDuplicateClip = onDuplicateClip,
                            onRemoveSelectedClips = onRemoveSelectedClips,
                            onRippleDeleteClip = onRippleDeleteClip,
                            onNudgeTrimLeft = onNudgeTrimLeft,
                            onNudgeTrimRight = onNudgeTrimRight,
                            onJumpToClip = onJumpToClip,
                            onToggleTrackMute = onToggleTrackMute,
                            onToggleTrackLock = onToggleTrackLock,
                            onReorderTrack = onReorderTrack,
                            onRemoveTrack = onRemoveTrack,
                            onUpdateCanvas = onUpdateCanvas,
                        )
                    }
                }

                InspectorTab.TOOLS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        QuickToolsOverviewContent(
                            project = project,
                            selectedClip = uiState.selectedClip,
                            selectedAsset = uiState.selectedClipAsset,
                            onAddTextClip = onAddTextClip,
                            onAddPresetTextClip = onAddPresetTextClip,
                            onUpdateTextSpec = onUpdateTextSpec,
                            onPreviewTextSpec = onPreviewTextSpec,
                            onOpenLyricStudioForClip = {
                                viewModel.loadSelectedClipIntoLyricStudio()
                                onSelectTab(InspectorTab.LYRICS)
                            },
                            onOpenAutoCaption = { onSelectTab(InspectorTab.CAPTIONS) },
                            onUpdateAdjustments = onUpdateAdjustments,
                            onPreviewAdjustments = onPreviewAdjustments,
                            onApplyFilterToAll = onApplyFilterToAll,
                            onUpdateTransition = onUpdateTransition,
                            onPreviewTransition = onPreviewTransition,
                            onUpdateSpeed = onUpdateSpeed,
                            onUpdateSpeedRamp = onUpdateSpeedRamp,
                            onToggleReverse = onToggleReverse,
                            onUpdateAudio = onUpdateAudio,
                            onPreviewAudio = onPreviewAudio,
                            onCommitSliderEdit = onCommitSliderEdit,
                            onUpdateCanvas = onUpdateCanvas,
                        )
                    }
                }

                InspectorTab.CAPTIONS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CaptionToolPanel(
                            captionState = uiState.autoCaptionState,
                            assets = uiState.allAssets,
                            fps = project.fps,
                            onSelectSourceAsset = viewModel::selectCaptionSourceAsset,
                            onSelectModel = viewModel::selectAsrModel,
                            onDownloadModel = viewModel::downloadSelectedAsrModel,
                            onCancelModelDownload = viewModel::cancelAsrModelDownload,
                            onDeleteModel = viewModel::deleteAsrModel,
                            onStartTranscription = viewModel::startAutoCaptionTranscription,
                            onCancelTranscription = viewModel::cancelAutoCaptionTranscription,
                            onUpdateMaxCharsPerLine = viewModel::updateCaptionMaxCharsPerLine,
                            onUpdateKaraokeMode = viewModel::updateCaptionKaraokeMode,
                            onUpdateStylePreset = viewModel::updateCaptionStylePreset,
                            onUpdateUseInvertEffect = viewModel::updateCaptionUseInvertEffect,
                            onUpdateCueText = viewModel::updateCaptionCueText,
                            onDeleteCue = viewModel::deleteCaptionCue,
                            onCommitCaptionsToTimeline = viewModel::commitCaptionsToTimeline,
                        )
                    }
                }

                InspectorTab.LYRICS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LyricStudioPanel(
                            lyricState = uiState.lyricStudioState,
                            assets = uiState.allAssets,
                            waveformsByAssetId = uiState.waveformsByAssetId,
                            playheadMs = uiState.playheadMs,
                            totalDurationMs = uiState.totalDurationMs,
                            fps = project.fps,
                            isPlaying = uiState.isPlaying,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onStepFrame = viewModel::stepFrame,
                            onSeekTo = viewModel::seekTo,
                            onSelectSourceAsset = viewModel::selectLyricSourceAsset,
                            onImportLyricsText = { text -> viewModel.importLyricsText(text, replaceExisting = true) },
                            onRequestImportTxtFile = onImportTxtLyrics,
                            onAddEmptyLine = viewModel::addEmptyLyricLine,
                            onSelectLine = viewModel::selectLyricLine,
                            onSelectWord = viewModel::selectLyricWord,
                            onEditLineText = viewModel::editLyricLineText,
                            onSplitSelectedLine = viewModel::splitSelectedLyricLine,
                            onMergeLineWithNext = viewModel::mergeLyricLineWithNext,
                            onMoveLine = viewModel::moveLyricLine,
                            onDeleteLine = viewModel::deleteLyricLine,
                            onAssignLineStartAtPlayhead = viewModel::assignLyricLineStartAtPlayhead,
                            onAssignLineEndAtPlayhead = viewModel::assignLyricLineEndAtPlayhead,
                            onAssignWordStartAtPlayhead = viewModel::assignLyricWordStartAtPlayhead,
                            onAssignWordEndAtPlayhead = viewModel::assignLyricWordEndAtPlayhead,
                            onStepWordSelection = viewModel::stepLyricWordSelection,
                            onAdjustWordDuration = viewModel::adjustSelectedLyricWordDuration,
                            onEstimateWordTimings = viewModel::distributeSelectedLineWordsAsEstimated,
                            onShiftLyricsOffset = viewModel::shiftLyricsOffset,
                            onResetLyricTiming = viewModel::resetLyricTiming,
                            onSetSnapToFrames = viewModel::setLyricSnapToFrames,
                            onSetSnapToBeats = viewModel::setLyricSnapToBeats,
                            onSetKaraokeMode = viewModel::setLyricKaraokeMode,
                            onSetStylePreset = viewModel::setLyricStylePreset,
                            onSetUseInvertEffect = viewModel::setLyricUseInvertEffect,
                            onCommitLyricsToTimeline = viewModel::commitLyricsToTimeline,
                        )
                    }
                }

                InspectorTab.COMPOSITOR -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CompositorOverviewContent(
                            selectedClip = uiState.selectedClip,
                            selectedAsset = uiState.selectedClipAsset,
                            preferProxyPlayback = uiState.preferProxyPlayback,
                            proxyJobState = uiState.proxyJobState,
                            isMotionAnalysisRunning = uiState.isMotionAnalysisRunning,
                            lastTrackedPoints = uiState.lastMotionTrackPoints,
                            onAddAdjustmentClip = onAddAdjustmentClip,
                            onUpdateTransform = onUpdateTransform,
                            onPreviewTransform = onPreviewTransform,
                            onUpdateBlendMode = onUpdateBlendMode,
                            onUpdateMask = onUpdateMask,
                            onPreviewMask = onPreviewMask,
                            onUpdateShaderEffect = onUpdateShaderEffect,
                            onPreviewShaderEffect = onPreviewShaderEffect,
                            onUpdateAdjustments = onUpdateAdjustments,
                            onPreviewAdjustments = onPreviewAdjustments,
                            onApplyFilterToAll = onApplyFilterToAll,
                            onUpdateChromaKey = onUpdateChromaKey,
                            onPreviewChromaKey = onPreviewChromaKey,
                            onUpdateLut = onUpdateLut,
                            onPreviewLut = onPreviewLut,
                            onImportCubeLut = onImportCubeLut,
                            onRunMotionTrack = onRunMotionTrack,
                            onRunStabilization = onRunStabilization,
                            onDisableStabilization = onDisableStabilization,
                            onSetPreferProxyPlayback = onSetPreferProxyPlayback,
                            onGenerateProxy = onGenerateProxy,
                            onCancelProxy = onCancelProxy,
                            onDeleteProxy = onDeleteProxy,
                            onAddPresetTextClip = onAddPresetTextClip,
                            onUpdateTextSpec = onUpdateTextSpec,
                            onPreviewTextSpec = onPreviewTextSpec,
                            onOpenLyricStudioForClip = {
                                viewModel.loadSelectedClipIntoLyricStudio()
                                onSelectTab(InspectorTab.LYRICS)
                            },
                            onOpenAutoCaption = { onSelectTab(InspectorTab.CAPTIONS) },
                            onCommitSliderEdit = onCommitSliderEdit,
                        )
                    }
                }

                InspectorTab.ANIMATION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AnimationOverviewContent(
                            clip = uiState.selectedClip,
                            asset = uiState.selectedClipAsset,
                            playheadMs = uiState.playheadMs,
                            selectedProperty = uiState.selectedKeyframeProperty,
                            selectedKeyframeIds = uiState.selectedKeyframeIds,
                            onSelectProperty = onSelectKeyframeProperty,
                            onSelectKeyframe = onSelectKeyframe,
                            onJumpToClipRelativeTime = onJumpToClipRelativeTime,
                            onToggleKeyframeAtPlayhead = onToggleKeyframeAtPlayhead,
                            onPreviewValueAtPlayhead = onPreviewValueAtPlayhead,
                            onPreviewMoveKeyframe = onPreviewMoveKeyframe,
                            onPreviewBezierHandle = onPreviewBezierHandle,
                            onUpdateKeyframesInterpolation = onUpdateKeyframesInterpolation,
                            onDeleteSelectedKeyframes = onDeleteSelectedKeyframes,
                            onClearPropertyKeyframes = onClearPropertyKeyframes,
                            onApplyMotionPreset = onApplyMotionPreset,
                            onCommitGesture = onCommitSliderEdit,
                        )
                    }
                }

                InspectorTab.AUDIO -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AudioWorkspaceContent(
                            project = project,
                            clip = uiState.selectedClip,
                            asset = uiState.selectedClipAsset,
                            waveform = uiState.selectedClip?.let { uiState.waveformsByAssetId[it.assetId] },
                            mixState = uiState.audioMixState,
                            playheadMs = uiState.playheadMs,
                            isAuditioningMix = uiState.isAuditioningMix,
                            onToggleTrackMute = onToggleTrackMute,
                            onUpdateAudio = onUpdateAudio,
                            onPreviewAudio = onPreviewAudio,
                            onUpdateAudioEffect = onUpdateAudioEffect,
                            onPreviewAudioEffect = onPreviewAudioEffect,
                            onAddMarkerAtPlayhead = onAddMarkerAtPlayhead,
                            onDetectBeatsForClip = onDetectBeatsForClip,
                            onRemoveMarker = onRemoveMarker,
                            onClearMarkers = onClearMarkers,
                            onJumpToTime = onJumpToTime,
                            onAuditionPcmMix = onAuditionPcmMix,
                            onCommitGesture = onCommitSliderEdit,
                        )
                    }
                }

                InspectorTab.CANVAS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CanvasSpecsEditor(
                            project = project,
                            onUpdateCanvas = onUpdateCanvas,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitBottomToolbar(
    mediaCount: Int,
    selectedClipIds: Set<String>,
    selectedClip: TimelineClipRef?,
    canSplitAtPlayhead: Boolean,
    onOpenMediaSheet: () -> Unit,
    onOpenInspectorSheet: () -> Unit,
    onOpenQuickTool: (QuickToolSheet) -> Unit,
    onAddAdjustmentClip: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDuplicateClip: () -> Unit,
    onRippleDeleteClip: () -> Unit,
    onRemoveSelectedClips: () -> Unit,
) {
    val studio = LocalStudioColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, studio.hairlineBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOpenMediaSheet,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_media_bin_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Default.PermMedia, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Media ($mediaCount)")
            }

            OutlinedButton(
                onClick = onSplitAtPlayhead,
                enabled = canSplitAtPlayhead,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_split_button"),
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Split")
            }

            if (selectedClip != null) {
                OutlinedButton(
                    onClick = onDuplicateClip,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toolbar_duplicate_button"),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Duplicate")
                }

                if (selectedClipIds.size == 1) {
                    OutlinedButton(
                        onClick = onRippleDeleteClip,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toolbar_ripple_delete_button"),
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Ripple delete",
                            tint = studio.audioAmber,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Ripple", color = studio.audioAmber)
                    }
                }

                OutlinedButton(
                    onClick = onRemoveSelectedClips,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toolbar_remove_clip_button"),
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete selected",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (selectedClipIds.size > 1) "Delete (${selectedClipIds.size})" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.TEXT) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_text_button"),
            ) {
                Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Text")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.AUTO_CAPTION) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_captions_button"),
            ) {
                Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Captions")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.LYRIC_STUDIO) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_lyrics_button"),
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Lyrics")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.INVERT_LYRIC) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_invert_lyric_button"),
            ) {
                Icon(Icons.Default.InvertColors, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Invert")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.FILTERS) },
                enabled = selectedClip != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_filter_button"),
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Filters")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.TRANSITIONS) },
                enabled = selectedClip != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_transition_button"),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Transition")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.SPEED) },
                enabled = selectedClip != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_speed_button"),
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Speed")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.AUDIO) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_audio_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Audio")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.TRANSFORM) },
                enabled = selectedClip != null && selectedClip.clipKind != ClipKind.ADJUSTMENT,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_transform_button"),
            ) {
                Icon(Icons.Default.OpenWith, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Transform")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.BLEND_MASK) },
                enabled = selectedClip != null && selectedClip.clipKind != ClipKind.ADJUSTMENT,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_blend_mask_button"),
            ) {
                Icon(Icons.Default.CropFree, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Blend/Mask")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.SHADER_FX) },
                enabled = selectedClip != null && selectedClip.clipKind != ClipKind.TEXT,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_shader_button"),
            ) {
                Icon(Icons.Default.BlurOn, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Shader")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.LUT_GRADE) },
                enabled = selectedClip != null && selectedClip.clipKind != ClipKind.TEXT,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_lut_button"),
            ) {
                Icon(Icons.Default.Palette, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("LUT")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.CHROMA_KEY) },
                enabled = selectedClip != null && selectedClip.clipKind == ClipKind.MEDIA,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_chroma_button"),
            ) {
                Icon(Icons.Default.Colorize, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Chroma")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.STABILIZE_TRACK) },
                enabled = selectedClip != null && selectedClip.clipKind == ClipKind.MEDIA,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_track_stabilize_button"),
            ) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Track/Stab")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.PROXY_MEDIA) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_proxy_button"),
            ) {
                Icon(Icons.Default.SdStorage, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Proxy")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.ANIMATION) },
                enabled = selectedClip != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_animate_button"),
            ) {
                Icon(Icons.Default.Animation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Animate")
            }

            OutlinedButton(
                onClick = onAddAdjustmentClip,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_add_adjustment_button"),
            ) {
                Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("+ Adj")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.RATIO) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_ratio_button"),
            ) {
                Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ratio")
            }

            OutlinedButton(
                onClick = onOpenInspectorSheet,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_inspector_button"),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (selectedClip != null) "Clip" else "Inspector")
            }
        }
    }
}

@Composable
private fun InspectorDetailsContent(
    uiState: ProjectWorkspaceUiState,
    project: ProjectDocument,
    onSplitAtPlayhead: () -> Unit,
    onDuplicateClip: () -> Unit,
    onRemoveSelectedClips: () -> Unit,
    onRippleDeleteClip: () -> Unit,
    onNudgeTrimLeft: (Long) -> Unit,
    onNudgeTrimRight: (Long) -> Unit,
    onJumpToClip: (TimelineClipRef) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onReorderTrack: (String, Int) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onUpdateCanvas: (AspectRatioPreset, Int) -> Unit,
) {
    val selectedClip = uiState.selectedClip
    val selectedAsset = uiState.selectedClipAsset
    val selectedTrack = uiState.selectedTrack

    if (selectedClip != null) {
        StudioPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (uiState.selectedClipIds.size > 1) {
                        "${uiState.selectedClipIds.size} Clips Selected"
                    } else {
                        "Selected Clip"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = when {
                        selectedClip.clipKind == ClipKind.TEXT -> "Text: ${selectedClip.textSpec?.text?.ifBlank { "Untitled" } ?: "Untitled"}"
                        selectedClip.clipKind == ClipKind.ADJUSTMENT -> "Adjustment Layer"
                        else -> selectedAsset?.displayName ?: "Clip ${selectedClip.clipId.take(6)}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Start: ${TimeFormatting.formatTimecode(selectedClip.timelineStartMs, project.fps)} • Dur: ${TimeFormatting.formatDurationLabel(selectedClip.durationMs)} • In: ${TimeFormatting.formatDurationLabel(selectedClip.sourceStartMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = onSplitAtPlayhead,
                        enabled = uiState.canSplitAtPlayhead,
                    ) {
                        Text("Split")
                    }
                    OutlinedButton(onClick = onDuplicateClip) {
                        Text("Duplicate")
                    }
                    OutlinedButton(onClick = onRippleDeleteClip) {
                        Text("Ripple Del")
                    }
                    OutlinedButton(
                        onClick = onRemoveSelectedClips,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }

                Text(
                    text = "Precision Trim Nudge",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = { onNudgeTrimLeft(-250L) }) { Text("In -0.25s") }
                    OutlinedButton(onClick = { onNudgeTrimLeft(250L) }) { Text("In +0.25s") }
                    OutlinedButton(onClick = { onNudgeTrimRight(-250L) }) { Text("Out -0.25s") }
                    OutlinedButton(onClick = { onNudgeTrimRight(250L) }) { Text("Out +0.25s") }
                }

                OutlinedButton(onClick = { onJumpToClip(selectedClip) }) {
                    Text("Jump Playhead to Start")
                }
            }
        }
    }

    if (selectedTrack != null) {
        val sortedTracks = project.tracks.sortedBy { it.orderIndex }
        val trackIdx = sortedTracks.indexOfFirst { it.id == selectedTrack.id }
        TrackInspectorCard(
            track = selectedTrack,
            canMoveUp = trackIdx > 0,
            canMoveDown = trackIdx in 0 until sortedTracks.lastIndex,
            canRemove = project.tracks.size > 1,
            onToggleMute = { onToggleTrackMute(selectedTrack.id) },
            onToggleLock = { onToggleTrackLock(selectedTrack.id) },
            onMoveUp = { onReorderTrack(selectedTrack.id, -1) },
            onMoveDown = { onReorderTrack(selectedTrack.id, 1) },
            onRemoveTrack = { onRemoveTrack(selectedTrack.id) },
        )
    }

    CanvasSpecsEditor(project = project, onUpdateCanvas = onUpdateCanvas)
}

@Composable
private fun TrackInspectorCard(
    track: TimelineTrack,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemoveTrack: () -> Unit,
) {
    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Track: ${track.name}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${track.type.label} • ${track.clips.size} clip(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = track.isMuted,
                    onClick = onToggleMute,
                    label = { Text(if (track.isMuted) "Muted" else "Active") },
                )
                FilterChip(
                    selected = track.isLocked,
                    onClick = onToggleLock,
                    label = { Text(if (track.isLocked) "Locked" else "Unlocked") },
                )
                OutlinedButton(onClick = onMoveUp, enabled = canMoveUp) { Text("Up") }
                OutlinedButton(onClick = onMoveDown, enabled = canMoveDown) { Text("Down") }
                if (canRemove) {
                    OutlinedButton(
                        onClick = onRemoveTrack,
                        enabled = !track.isLocked,
                    ) {
                        Text("Delete Track")
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasSpecsEditor(
    project: ProjectDocument,
    onUpdateCanvas: (AspectRatioPreset, Int) -> Unit,
) {
    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Project Canvas & Timebase",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AspectRatioPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = project.aspectRatio == preset,
                        onClick = { onUpdateCanvas(preset, project.fps) },
                        label = { Text(preset.label) },
                    )
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FrameRateOption.entries.forEach { option ->
                    FilterChip(
                        selected = project.fps == option.fps,
                        onClick = { onUpdateCanvas(project.aspectRatio, option.fps) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceModeSwitcher(
    currentMode: EditorMode,
    onModeSelected: (EditorMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(studio.elevatedPanel)
            .border(1.dp, studio.hairlineBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EditorMode.entries.forEach { mode ->
            val selected = currentMode == mode
            val bg = when {
                !selected -> Color.Transparent
                mode == EditorMode.QUICK -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
            val fg = when {
                !selected -> MaterialTheme.colorScheme.onSurfaceVariant
                mode == EditorMode.QUICK -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(bg)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("workspace_mode_${mode.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (mode == EditorMode.QUICK) Icons.Default.Bolt else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (mode == EditorMode.QUICK) "Quick" else "Adv",
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun ImportActionStrip(
    onImportVideo: () -> Unit,
    onImportImage: () -> Unit,
    onImportAudio: () -> Unit,
    onImportFile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImportButton(
            label = "Video",
            icon = Icons.Default.Movie,
            tint = MaterialTheme.colorScheme.primary,
            onClick = onImportVideo,
            testTag = "import_video_button",
        )
        ImportButton(
            label = "Photo",
            icon = Icons.Default.Image,
            tint = MaterialTheme.colorScheme.secondary,
            onClick = onImportImage,
            testTag = "import_image_button",
        )
        ImportButton(
            label = "Audio",
            icon = Icons.Default.AudioFile,
            tint = LocalStudioColors.current.audioAmber,
            onClick = onImportAudio,
            testTag = "import_audio_button",
        )
        ImportButton(
            label = "Files",
            icon = Icons.Default.FolderOpen,
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = onImportFile,
            testTag = "import_files_button",
        )
    }
}

@Composable
private fun ImportButton(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    testTag: String,
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, LocalStudioColors.current.hairlineBorder),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Import $label",
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MediaFilterChipsRow(
    activeFilter: MediaType?,
    onFilterToggle: (MediaType?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaType.entries.forEach { type ->
            FilterChip(
                selected = activeFilter == type,
                onClick = { onFilterToggle(type) },
                label = { Text(type.label) },
                modifier = Modifier.testTag("bin_filter_${type.name.lowercase()}"),
            )
        }
    }
}

@Composable
fun MediaAssetRowCard(
    asset: MediaAsset,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    actionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else studio.hairlineBorder
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onSelect)
            .testTag("media_asset_${asset.id}"),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(studio.elevatedPanel),
                contentAlignment = Alignment.Center,
            ) {
                if (asset.type == MediaType.AUDIO) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = studio.audioAmber,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    AsyncImage(
                        model = asset.playbackUri,
                        contentDescription = asset.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = asset.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaTypeBadge(type = asset.type)
                    if (asset.status != MediaStatus.READY) {
                        StudioBadge(
                            text = asset.status.label.uppercase(),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                val specs = buildString {
                    if (asset.durationMs > 0L) {
                        append(TimeFormatting.formatDurationLabel(asset.durationMs))
                    }
                    if (asset.resolutionLabel.isNotEmpty()) {
                        if (isNotEmpty()) append(" • ")
                        append(asset.resolutionLabel)
                    }
                }
                Text(
                    text = specs.ifBlank { asset.mimeType },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (actionLabel != null) {
                StudioBadge(
                    text = actionLabel,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_asset_${asset.id}"),
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove ${asset.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
