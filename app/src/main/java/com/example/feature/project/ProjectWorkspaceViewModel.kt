package com.example.feature.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.common.TimelineTimebase
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.media.ProxyJobState
import com.example.core.media.ProxyMediaManager
import com.example.engine.animation.AnimationEngine
import com.example.engine.animation.TrackedMotionPoint
import com.example.engine.audio.MasterAudioMixState
import com.example.engine.audio.MixedAudioPreviewPlayer
import com.example.engine.audio.MultiTrackAudioMixer
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.audio.WaveformEnvelope
import com.example.engine.effects.EffectsEngine
import com.example.engine.export.CutLabExportCoordinator
import com.example.engine.export.ExportJobState
import com.example.engine.export.ExportOutputManager
import com.example.engine.export.ExportPlan
import com.example.engine.export.ExportPlanner
import com.example.engine.export.ExportRenderPath
import com.example.engine.export.ExportedArtifact
import com.example.engine.export.HardwareExportCapabilities
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.BezierCurveSpec
import com.example.engine.project.BlendModePreset
import com.example.engine.project.CURRENT_PROJECT_SCHEMA_VERSION
import com.example.engine.project.ChromaKeySpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipAudioEffect
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.ClipTransition
import com.example.engine.project.EditorMode
import com.example.engine.project.ExportSettingsSpec
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.MotionPreset
import com.example.engine.project.ProjectDocument
import com.example.engine.project.ProjectRepository
import com.example.engine.project.ProjectRepositoryLoadState
import com.example.engine.project.ProjectSaveOutcome
import com.example.engine.project.SpeedPoint
import com.example.engine.project.SpeedRampPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineTrack
import com.example.engine.project.TrackType
import com.example.engine.timeline.ActiveTimelineFrame
import com.example.engine.timeline.TimelineEngine
import com.example.engine.timeline.TimelineOverlapRepairMode
import com.example.engine.timeline.TimelineOverlapRepairReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class InspectorTab(val label: String) {
    MEDIA("Media"),
    INSPECTOR("Inspector"),
    TOOLS("FX & Text"),
    COMPOSITOR("Compositor"),
    ANIMATION("Animate"),
    AUDIO("Audio"),
    CANVAS("Canvas"),
}

private data class EditorInteractionState(
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val selectedClipIds: Set<String> = emptySet(),
    val selectedTrackId: String? = null,
    val timelineZoom: Float = 1.0f,
    val snappingEnabled: Boolean = true,
    val activeSnapGuideMs: Long? = null,
    val inspectorTab: InspectorTab = InspectorTab.MEDIA,
    val mediaFilter: MediaType? = null,
    val isImporting: Boolean = false,
    val historyVersion: Int = 0,
    val selectedKeyframeProperty: KeyframeProperty = KeyframeProperty.SCALE_X,
    val selectedKeyframeIds: Set<String> = emptySet(),
)

private data class InteractionExtrasState(
    val mediaFilter: MediaType?,
    val isImporting: Boolean,
    val historyVersion: Int,
    val selectedKeyframeProperty: KeyframeProperty,
    val selectedKeyframeIds: Set<String>,
)

private data class InteractionControlsState(
    val timelineZoom: Float,
    val snappingEnabled: Boolean,
    val activeSnapGuideMs: Long?,
    val inspectorTab: InspectorTab,
    val extras: InteractionExtrasState,
)

private data class ExportUiBundle(
    val preferredExportPath: ExportRenderPath,
    val hardwareCapabilities: HardwareExportCapabilities,
    val exportJobState: ExportJobState,
    val projectExports: List<ExportedArtifact>,
)

private data class MediaProxyStateBundle(
    val preferProxyPlayback: Boolean,
    val proxyJobState: ProxyJobState,
    val isMotionAnalysisRunning: Boolean,
    val lastMotionTrackPoints: List<TrackedMotionPoint>,
)

private data class AudioAndExportBundle(
    val waveformsByAssetId: Map<String, WaveformEnvelope>,
    val isAuditioningMix: Boolean,
    val exportUi: ExportUiBundle,
    val mediaProxy: MediaProxyStateBundle,
)

data class ProjectWorkspaceUiState(
    val project: ProjectDocument? = null,
    val allAssets: List<MediaAsset> = emptyList(),
    val filteredAssets: List<MediaAsset> = emptyList(),
    val assetsById: Map<String, MediaAsset> = emptyMap(),
    val playheadMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val activeFrame: ActiveTimelineFrame = ActiveTimelineFrame(0L, emptyList(), emptyList()),
    val selectedClipIds: Set<String> = emptySet(),
    val selectedClip: TimelineClipRef? = null,
    val selectedClipAsset: MediaAsset? = null,
    val selectedTrack: TimelineTrack? = null,
    val timelineZoom: Float = 1.0f,
    val snappingEnabled: Boolean = true,
    val activeSnapGuideMs: Long? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val canSplitAtPlayhead: Boolean = false,
    val inspectorTab: InspectorTab = InspectorTab.MEDIA,
    val mediaFilter: MediaType? = null,
    val isImporting: Boolean = false,
    val selectedKeyframeProperty: KeyframeProperty = KeyframeProperty.SCALE_X,
    val selectedKeyframeIds: Set<String> = emptySet(),
    val waveformsByAssetId: Map<String, WaveformEnvelope> = emptyMap(),
    val audioMixState: MasterAudioMixState = MasterAudioMixState(),
    val isAuditioningMix: Boolean = false,
    val preferredExportPath: ExportRenderPath = ExportRenderPath.AUTO,
    val hardwareCapabilities: HardwareExportCapabilities = HardwareExportCapabilities(),
    val exportPlan: ExportPlan? = null,
    val exportJobState: ExportJobState = ExportJobState.Idle,
    val projectExports: List<ExportedArtifact> = emptyList(),
    val preferProxyPlayback: Boolean = true,
    val proxyJobState: ProxyJobState = ProxyJobState.Idle,
    val isMotionAnalysisRunning: Boolean = false,
    val lastMotionTrackPoints: List<TrackedMotionPoint> = emptyList(),
    val isReadOnlyFutureVersion: Boolean = false,
    val detectedSchemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
    val unsupportedFutureKeys: Set<String> = emptySet(),
    val pendingOverlapRepair: TimelineOverlapRepairReport? = null,
    val isLoading: Boolean = true,
)

class ProjectWorkspaceViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: CutLabPreferencesRepository,
    private val animationEngine: AnimationEngine = AnimationEngine(),
    private val timelineEngine: TimelineEngine = TimelineEngine(animationEngine),
    private val pcmAudioExtractor: PcmAudioExtractor? = null,
    private val mixedAudioPreviewPlayer: MixedAudioPreviewPlayer? = null,
    private val audioMixer: MultiTrackAudioMixer = MultiTrackAudioMixer(animationEngine),
    private val exportPlanner: ExportPlanner = ExportPlanner(),
    private val exportOutputManager: ExportOutputManager? = null,
    private val exportCoordinator: CutLabExportCoordinator? = null,
    private val proxyMediaManager: ProxyMediaManager? = null,
    private val appContext: Context? = null,
) : ViewModel() {

    private val playheadMs = MutableStateFlow(0L)
    private val isPlaying = MutableStateFlow(false)
    private val selectedClipIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedTrackId = MutableStateFlow<String?>(null)
    private val timelineZoom = MutableStateFlow(1.0f)
    private val snappingEnabled = MutableStateFlow(true)
    private val activeSnapGuideMs = MutableStateFlow<Long?>(null)
    private val inspectorTab = MutableStateFlow(InspectorTab.MEDIA)
    private val mediaFilter = MutableStateFlow<MediaType?>(null)
    private val isImporting = MutableStateFlow(false)
    private val selectedKeyframeProperty = MutableStateFlow(KeyframeProperty.SCALE_X)
    private val selectedKeyframeIds = MutableStateFlow<Set<String>>(emptySet())

    private var playbackJob: Job? = null

    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    private val editSession = WorkspaceEditSession()
    private val mediaAnalysisCoordinator = WorkspaceMediaAnalysisCoordinator(
        scope = viewModelScope,
        timelineEngine = timelineEngine,
        pcmAudioExtractor = pcmAudioExtractor,
        proxyMediaManager = proxyMediaManager,
        appContext = appContext,
        emitMessage = { _messages.emit(it) },
    )
    private val audioCoordinator = WorkspaceAudioCoordinator(
        scope = viewModelScope,
        pcmAudioExtractor = pcmAudioExtractor,
        mixedAudioPreviewPlayer = mixedAudioPreviewPlayer,
        audioMixer = audioMixer,
        emitMessage = { _messages.emit(it) },
    )
    private val exportCoordinatorDelegate = WorkspaceExportCoordinator(
        scope = viewModelScope,
        projectId = projectId,
        exportPlanner = exportPlanner,
        exportOutputManager = exportOutputManager,
        exportCoordinator = exportCoordinator,
        emitMessage = { _messages.emit(it) },
    )

    init {
        exportCoordinatorDelegate.initialize()
        mediaAnalysisCoordinator.observeAssetsForWaveforms(
            projectRepository.observeProjectAssets(projectId),
        )
    }

    private val interactionBase = combine(
        playheadMs,
        isPlaying,
        selectedClipIds,
        selectedTrackId,
    ) { pos, playing, clipIds, trackId ->
        EditorInteractionState(
            playheadMs = pos,
            isPlaying = playing,
            selectedClipIds = clipIds,
            selectedTrackId = trackId,
        )
    }

    private val interactionExtras = combine(
        mediaFilter,
        isImporting,
        editSession.historyVersion,
        selectedKeyframeProperty,
        selectedKeyframeIds,
    ) { f, imp, h, kfProp, kfIds ->
        InteractionExtrasState(
            mediaFilter = f,
            isImporting = imp,
            historyVersion = h,
            selectedKeyframeProperty = kfProp,
            selectedKeyframeIds = kfIds,
        )
    }

    private val interactionControls = combine(
        timelineZoom,
        snappingEnabled,
        activeSnapGuideMs,
        inspectorTab,
        interactionExtras,
    ) { zoom, snap, guide, tab, extra ->
        InteractionControlsState(
            timelineZoom = zoom,
            snappingEnabled = snap,
            activeSnapGuideMs = guide,
            inspectorTab = tab,
            extras = extra,
        )
    }

    private val interactionState = combine(interactionBase, interactionControls) { base, ctrl ->
        base.copy(
            timelineZoom = ctrl.timelineZoom,
            snappingEnabled = ctrl.snappingEnabled,
            activeSnapGuideMs = ctrl.activeSnapGuideMs,
            inspectorTab = ctrl.inspectorTab,
            mediaFilter = ctrl.extras.mediaFilter,
            isImporting = ctrl.extras.isImporting,
            historyVersion = ctrl.extras.historyVersion,
            selectedKeyframeProperty = ctrl.extras.selectedKeyframeProperty,
            selectedKeyframeIds = ctrl.extras.selectedKeyframeIds,
        )
    }

    private val exportUiState = combine(
        exportCoordinatorDelegate.preferredExportPath,
        exportCoordinatorDelegate.hardwareCapabilities,
        exportCoordinatorDelegate.exportJobState,
        exportCoordinatorDelegate.projectExports,
    ) { path, caps, job, history ->
        ExportUiBundle(
            preferredExportPath = path,
            hardwareCapabilities = caps,
            exportJobState = job,
            projectExports = history,
        )
    }

    private val mediaProxyAuxState = combine(
        preferencesRepository.preferencesFlow,
        mediaAnalysisCoordinator.proxyJobState,
        mediaAnalysisCoordinator.isMotionAnalysisRunning,
        mediaAnalysisCoordinator.lastMotionTrackPoints,
    ) { prefs, proxyState, trackingBusy, trackPoints ->
        MediaProxyStateBundle(
            preferProxyPlayback = prefs.preferProxyPlayback,
            proxyJobState = proxyState,
            isMotionAnalysisRunning = trackingBusy,
            lastMotionTrackPoints = trackPoints,
        )
    }

    private val audioAndExportState = combine(
        mediaAnalysisCoordinator.waveformsByAssetId,
        audioCoordinator.isAuditioningMix,
        exportUiState,
        mediaProxyAuxState,
    ) { waveforms, auditioning, exportBundle, proxyBundle ->
        AudioAndExportBundle(
            waveformsByAssetId = waveforms,
            isAuditioningMix = auditioning,
            exportUi = exportBundle,
            mediaProxy = proxyBundle,
        )
    }

    val uiState: StateFlow<ProjectWorkspaceUiState> = combine(
        projectRepository.observeProjectLoadState(projectId),
        projectRepository.observeProjectAssets(projectId),
        editSession.draftDocument,
        interactionState,
        audioAndExportState,
    ) { loadState, assets, draft, interaction, extra ->
        val wfMap = extra.waveformsByAssetId
        val auditioning = extra.isAuditioningMix
        val prefPath = extra.exportUi.preferredExportPath
        val hwCaps = extra.exportUi.hardwareCapabilities
        val jobState = extra.exportUi.exportJobState
        val exportHistory = extra.exportUi.projectExports
        val preferProxy = extra.mediaProxy.preferProxyPlayback
        val proxyState = extra.mediaProxy.proxyJobState
        val motionBusy = extra.mediaProxy.isMotionAnalysisRunning
        val motionPoints = extra.mediaProxy.lastMotionTrackPoints

        val persistedDoc = when (loadState) {
            is ProjectRepositoryLoadState.Ready -> loadState.document
            is ProjectRepositoryLoadState.UnsupportedFutureVersion -> loadState.previewDocument
            is ProjectRepositoryLoadState.Corrupt -> loadState.fallbackDocument
            null -> null
        }
        val isReadOnlyFuture = loadState is ProjectRepositoryLoadState.UnsupportedFutureVersion
        val detectedSchema = when (loadState) {
            is ProjectRepositoryLoadState.Ready -> loadState.sourceSchemaVersion
            is ProjectRepositoryLoadState.UnsupportedFutureVersion -> loadState.detectedSchemaVersion
            is ProjectRepositoryLoadState.Corrupt -> persistedDoc?.schemaVersion ?: CURRENT_PROJECT_SCHEMA_VERSION
            null -> CURRENT_PROJECT_SCHEMA_VERSION
        }
        val futureKeys = (loadState as? ProjectRepositoryLoadState.UnsupportedFutureVersion)
            ?.unknownTopLevelKeys
            .orEmpty()

        val doc = if (isReadOnlyFuture) persistedDoc else (draft ?: persistedDoc)
        val pendingOverlapReport = if (!isReadOnlyFuture && doc != null) {
            if (draft != null) {
                timelineEngine.inspectTrackOverlaps(draft).takeIf { it.hasOverlaps }
            } else {
                (loadState as? ProjectRepositoryLoadState.Ready)?.overlapRepairReport
            }
        } else {
            null
        }
        val assetsById = assets.associateBy { it.id }
        val filteredAssets = if (interaction.mediaFilter == null) {
            assets
        } else {
            assets.filter { it.type == interaction.mediaFilter }
        }
        val totalDuration = doc?.let { timelineEngine.calculateTimelineDurationMs(it) } ?: 0L
        val clampedPlayhead = interaction.playheadMs.coerceIn(0L, totalDuration.coerceAtLeast(0L))
        val activeFrame = doc?.let { timelineEngine.evaluateFrame(it, clampedPlayhead) }
            ?: ActiveTimelineFrame(clampedPlayhead, emptyList(), emptyList())

        val allClips = doc?.tracks?.flatMap { it.clips }.orEmpty()
        val validSelectedIds = interaction.selectedClipIds.filter { id ->
            allClips.any { it.clipId == id }
        }.toSet()
        val primarySelectedClip = if (validSelectedIds.size == 1) {
            allClips.firstOrNull { it.clipId == validSelectedIds.first() }
        } else {
            validSelectedIds.firstOrNull()?.let { id -> allClips.firstOrNull { it.clipId == id } }
        }
        val selectedClipAsset = primarySelectedClip?.let { assetsById[it.assetId] }
        val selectedTrack = doc?.tracks?.firstOrNull { it.id == interaction.selectedTrackId }
            ?: primarySelectedClip?.let { clip -> doc?.tracks?.firstOrNull { it.id == clip.trackId } }

        val validKeyframeIds = primarySelectedClip?.keyframeTracks
            ?.firstOrNull { it.property == interaction.selectedKeyframeProperty }
            ?.keyframes
            ?.map { it.id }
            ?.toSet()
            ?.let { existing -> interaction.selectedKeyframeIds.intersect(existing) }
            ?: emptySet()

        val canSplit = !isReadOnlyFuture &&
            doc != null &&
            findSplittableClip(doc, clampedPlayhead, validSelectedIds) != null
        val audioMixState = doc?.let {
            audioMixer.computeLiveMeters(it, activeFrame, wfMap, assetsById)
        } ?: MasterAudioMixState()
        val exportPlan = doc?.let {
            exportPlanner.buildPlan(
                document = it,
                assetsById = assetsById,
                settings = it.exportSettings,
                preferredPath = prefPath,
                capabilities = hwCaps,
            )
        }

        ProjectWorkspaceUiState(
            project = doc,
            allAssets = assets,
            filteredAssets = filteredAssets,
            assetsById = assetsById,
            playheadMs = clampedPlayhead,
            totalDurationMs = totalDuration,
            isPlaying = interaction.isPlaying,
            activeFrame = activeFrame,
            selectedClipIds = validSelectedIds,
            selectedClip = primarySelectedClip,
            selectedClipAsset = selectedClipAsset,
            selectedTrack = selectedTrack,
            timelineZoom = interaction.timelineZoom,
            snappingEnabled = interaction.snappingEnabled,
            activeSnapGuideMs = interaction.activeSnapGuideMs,
            canUndo = !isReadOnlyFuture && editSession.canUndo,
            canRedo = !isReadOnlyFuture && editSession.canRedo,
            undoLabel = editSession.undoLabel,
            redoLabel = editSession.redoLabel,
            canSplitAtPlayhead = canSplit,
            inspectorTab = interaction.inspectorTab,
            mediaFilter = interaction.mediaFilter,
            isImporting = interaction.isImporting,
            selectedKeyframeProperty = interaction.selectedKeyframeProperty,
            selectedKeyframeIds = validKeyframeIds,
            waveformsByAssetId = wfMap,
            audioMixState = audioMixState,
            isAuditioningMix = auditioning,
            preferredExportPath = prefPath,
            hardwareCapabilities = hwCaps,
            exportPlan = exportPlan,
            exportJobState = jobState,
            projectExports = exportHistory,
            preferProxyPlayback = preferProxy,
            proxyJobState = proxyState,
            isMotionAnalysisRunning = motionBusy,
            lastMotionTrackPoints = motionPoints,
            isReadOnlyFutureVersion = isReadOnlyFuture,
            detectedSchemaVersion = detectedSchema,
            unsupportedFutureKeys = futureKeys,
            pendingOverlapRepair = pendingOverlapReport,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProjectWorkspaceUiState(),
    )

    fun togglePlayPause() {
        if (isPlaying.value) pausePlayback() else startPlayback()
    }

    fun startPlayback() {
        stopPcmAudition()
        val total = uiState.value.totalDurationMs
        if (total <= 0L) {
            viewModelScope.launch { _messages.emit("Add media to the timeline to preview playback") }
            return
        }
        if (playheadMs.value >= total) {
            playheadMs.value = 0L
        }
        playbackJob?.cancel()
        isPlaying.value = true
        playbackJob = viewModelScope.launch {
            val playbackStartMs = playheadMs.value
            val playbackAnchorNs = System.nanoTime()
            while (isActive && isPlaying.value) {
                delay(16L)
                val elapsedMs = (System.nanoTime() - playbackAnchorNs).coerceAtLeast(0L) / 1_000_000L
                val currentTotal = uiState.value.totalDurationMs
                val nextPos = playbackStartMs + elapsedMs
                if (nextPos >= currentTotal) {
                    playheadMs.value = currentTotal
                    isPlaying.value = false
                } else {
                    playheadMs.value = nextPos
                }
            }
        }
    }

    fun pausePlayback() {
        isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    fun seekTo(ms: Long) {
        val total = uiState.value.totalDurationMs.coerceAtLeast(0L)
        playheadMs.value = ms.coerceIn(0L, total)
    }

    fun stepFrame(deltaFrames: Int) {
        pausePlayback()
        val fps = uiState.value.project?.fps?.coerceAtLeast(1) ?: 30
        val total = uiState.value.totalDurationMs
        seekTo(TimelineTimebase.stepFrameMillis(playheadMs.value, deltaFrames, fps, total))
    }

    fun jumpToStart() {
        pausePlayback()
        playheadMs.value = 0L
    }

    fun jumpToEnd() {
        pausePlayback()
        playheadMs.value = uiState.value.totalDurationMs
    }

    fun selectClip(clipId: String?, multiSelect: Boolean = false) {
        if (clipId == null) {
            selectedClipIds.value = emptySet()
            return
        }
        if (multiSelect) {
            val current = selectedClipIds.value
            selectedClipIds.value = if (clipId in current) current - clipId else current + clipId
        } else {
            selectedClipIds.value = setOf(clipId)
        }
        val ownerTrack = uiState.value.project?.tracks?.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        }
        if (ownerTrack != null) {
            selectedTrackId.value = ownerTrack.id
        }
    }

    fun selectTrack(trackId: String?) {
        selectedTrackId.value = trackId
    }

    fun setTimelineZoom(zoom: Float) {
        timelineZoom.value = zoom.coerceIn(0.4f, 4.0f)
    }

    fun toggleSnapping() {
        snappingEnabled.value = !snappingEnabled.value
        val enabled = snappingEnabled.value
        viewModelScope.launch {
            _messages.emit(if (enabled) "Magnetic snapping ON" else "Magnetic snapping OFF")
        }
    }

    fun setInspectorTab(tab: InspectorTab) {
        inspectorTab.value = tab
    }

    fun toggleMediaFilter(type: MediaType?) {
        mediaFilter.value = if (mediaFilter.value == type) null else type
    }

    private fun guardWritableOrEmit(): Boolean {
        if (uiState.value.isReadOnlyFutureVersion) {
            val ver = uiState.value.detectedSchemaVersion
            viewModelScope.launch {
                _messages.emit("Read-only: project schema v$ver exceeds CutLab v$CURRENT_PROJECT_SCHEMA_VERSION")
            }
            return false
        }
        return true
    }

    fun inspectOverlapRepairReport(
        mode: TimelineOverlapRepairMode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
    ): TimelineOverlapRepairReport? {
        if (uiState.value.isReadOnlyFutureVersion) return null
        val doc = uiState.value.project ?: return null
        return timelineEngine.inspectTrackOverlaps(doc, mode).takeIf { it.hasOverlaps }
    }

    fun repairLegacyOverlaps(
        mode: TimelineOverlapRepairMode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
    ) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val outcome = timelineEngine.repairOverlappingTracks(doc, mode)
        if (!outcome.report.hasOverlaps && outcome.document == doc) {
            viewModelScope.launch { _messages.emit("No track overlaps found") }
            return
        }
        commitDocumentEdit("Repair Track Overlaps", doc, outcome.document)
        val summary = if (outcome.report.timingPreserved) {
            "Moved ${outcome.report.affectedClipCount} overlapping clip(s) to overflow track(s); timing preserved"
        } else {
            "Shifted ${outcome.report.affectedClipCount} overlapping clip(s) cleanly"
        }
        viewModelScope.launch { _messages.emit(summary) }
    }

    fun undo() {
        if (!guardWritableOrEmit()) return
        cancelClipGesture()
        val outcome = editSession.undo(selectedClipIds.value) ?: return
        selectedClipIds.value = outcome.filteredSelectedClipIds
        viewModelScope.launch {
            when (val res = projectRepository.saveDocument(outcome.document)) {
                is ProjectSaveOutcome.Saved -> _messages.emit("Undid ${outcome.label}")
                is ProjectSaveOutcome.BlockedUnsupportedFutureVersion ->
                    _messages.emit("Blocked save: schema v${res.detectedSchemaVersion} is read-only")
                is ProjectSaveOutcome.BlockedCorruptData,
                is ProjectSaveOutcome.ProjectNotFound -> Unit
            }
        }
    }

    fun redo() {
        if (!guardWritableOrEmit()) return
        cancelClipGesture()
        val outcome = editSession.redo(selectedClipIds.value) ?: return
        selectedClipIds.value = outcome.filteredSelectedClipIds
        viewModelScope.launch {
            when (val res = projectRepository.saveDocument(outcome.document)) {
                is ProjectSaveOutcome.Saved -> _messages.emit("Redid ${outcome.label}")
                is ProjectSaveOutcome.BlockedUnsupportedFutureVersion ->
                    _messages.emit("Blocked save: schema v${res.detectedSchemaVersion} is read-only")
                is ProjectSaveOutcome.BlockedCorruptData,
                is ProjectSaveOutcome.ProjectNotFound -> Unit
            }
        }
    }

    private fun commitDocumentEdit(
        label: String,
        before: ProjectDocument,
        after: ProjectDocument,
    ) {
        if (uiState.value.isReadOnlyFutureVersion) {
            viewModelScope.launch {
                _messages.emit(
                    "Read-only: project schema v${uiState.value.detectedSchemaVersion} cannot be modified",
                )
            }
            return
        }
        if (editSession.recordCommittedEdit(label, before, after)) {
            viewModelScope.launch {
                val outcome = projectRepository.saveDocument(after)
                if (outcome is ProjectSaveOutcome.BlockedUnsupportedFutureVersion) {
                    _messages.emit(
                        "Save blocked to protect future schema v${outcome.detectedSchemaVersion}",
                    )
                }
            }
        }
    }

    fun switchEditorMode(mode: EditorMode) {
        if (!guardWritableOrEmit()) return
        viewModelScope.launch {
            if (projectRepository.switchEditorMode(projectId, mode) is ProjectSaveOutcome.Saved) {
                projectRepository.observeProject(projectId).first()?.let { updated ->
                    editSession.rebaseMetadata(updated)
                }
            }
        }
    }

    fun renameProject(newName: String) {
        if (!guardWritableOrEmit()) return
        viewModelScope.launch {
            when (val res = projectRepository.renameProject(projectId, newName)) {
                is ProjectSaveOutcome.Saved -> {
                    editSession.rebaseMetadata(res.document)
                    _messages.emit("Project renamed")
                }
                is ProjectSaveOutcome.BlockedUnsupportedFutureVersion ->
                    _messages.emit("Read-only: project schema v${res.detectedSchemaVersion} cannot be renamed")
                else -> Unit
            }
        }
    }

    fun updateCanvasSpecs(aspectRatio: AspectRatioPreset, fps: Int) {
        if (!guardWritableOrEmit()) return
        viewModelScope.launch {
            when (val res = projectRepository.updateProjectCanvas(projectId, aspectRatio, fps)) {
                is ProjectSaveOutcome.Saved -> {
                    editSession.rebaseMetadata(res.document)
                    _messages.emit("Canvas updated to ${aspectRatio.label} • ${fps} FPS")
                }
                is ProjectSaveOutcome.BlockedUnsupportedFutureVersion ->
                    _messages.emit("Read-only: project schema v${res.detectedSchemaVersion} cannot be modified")
                else -> Unit
            }
        }
    }

    fun addTrack(type: TrackType) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val (updated, created) = timelineEngine.addTrack(doc, type)
        val finalDoc = if (doc.editorMode == EditorMode.QUICK &&
            (type == TrackType.OVERLAY || type == TrackType.TEXT || type == TrackType.ADJUSTMENT)
        ) {
            updated.copy(editorMode = EditorMode.ADVANCED)
        } else {
            updated
        }
        selectedTrackId.value = created.id
        commitDocumentEdit("Add ${created.name}", doc, finalDoc)
        viewModelScope.launch { _messages.emit("Added ${created.name}") }
    }

    fun reorderTrack(trackId: String, delta: Int) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.reorderTrack(doc, trackId, delta)
        commitDocumentEdit("Reorder Track", doc, updated)
    }

    fun toggleTrackMute(trackId: String) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.toggleTrackMute(doc, trackId)
        commitDocumentEdit("Toggle Track Mute", doc, updated)
    }

    fun toggleTrackLock(trackId: String) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.toggleTrackLock(doc, trackId)
        commitDocumentEdit("Toggle Track Lock", doc, updated)
    }

    fun removeTrack(trackId: String) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val track = doc.tracks.firstOrNull { it.id == trackId } ?: return
        if (track.isLocked) {
            viewModelScope.launch { _messages.emit("Unlock ${track.name} before removing") }
            return
        }
        val updated = timelineEngine.removeTrack(doc, trackId)
        if (updated != doc) {
            if (selectedTrackId.value == trackId) selectedTrackId.value = null
            commitDocumentEdit("Remove ${track.name}", doc, updated)
            viewModelScope.launch { _messages.emit("Removed ${track.name}") }
        }
    }

    fun insertAssetToTimeline(assetId: String, atPlayhead: Boolean = false) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val asset = uiState.value.assetsById[assetId] ?: return
        val startMs = if (atPlayhead) playheadMs.value else null
        val (updated, newClip) = timelineEngine.insertAssetAsClip(
            document = doc,
            asset = asset,
            targetTrackId = selectedTrackId.value,
            atTimelineMs = startMs,
        )
        if (newClip != null) {
            selectedClipIds.value = setOf(newClip.clipId)
            selectedTrackId.value = newClip.trackId
            playheadMs.value = newClip.timelineStartMs
            commitDocumentEdit("Insert Clip", doc, updated)
            viewModelScope.launch { _messages.emit("Added clip to timeline") }
        } else {
            viewModelScope.launch { _messages.emit("No unlocked compatible track available") }
        }
    }

    fun splitAtPlayhead() {
        pausePlayback()
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val targetClip = findSplittableClip(doc, playheadMs.value, uiState.value.selectedClipIds)
        if (targetClip == null) {
            viewModelScope.launch { _messages.emit("Move playhead inside an unlocked clip to split") }
            return
        }
        val (updated, rightClipId) = timelineEngine.splitClipAt(doc, targetClip.clipId, playheadMs.value)
        if (rightClipId != null) {
            selectedClipIds.value = setOf(rightClipId)
            commitDocumentEdit("Split Clip", doc, updated)
            viewModelScope.launch { _messages.emit("Clip split at playhead") }
        }
    }

    fun duplicateSelectedClip() {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val ownerTrack = doc.tracks.firstOrNull { it.id == clip.trackId }
        if (ownerTrack?.isLocked == true) {
            viewModelScope.launch { _messages.emit("Track ${ownerTrack.name} is locked") }
            return
        }
        val (updated, duplicatedId) = timelineEngine.duplicateClip(doc, clip.clipId)
        if (duplicatedId != null) {
            selectedClipIds.value = setOf(duplicatedId)
            commitDocumentEdit("Duplicate Clip", doc, updated)
            viewModelScope.launch { _messages.emit("Clip duplicated") }
        }
    }

    fun removeSelectedClips() {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val ids = uiState.value.selectedClipIds
        if (ids.isEmpty()) return
        val updated = timelineEngine.removeClips(doc, ids)
        if (updated == doc) {
            viewModelScope.launch { _messages.emit("Selected clip track is locked") }
            return
        }
        selectedClipIds.value = emptySet()
        commitDocumentEdit(
            if (ids.size > 1) "Delete ${ids.size} Clips" else "Delete Clip",
            doc,
            updated,
        )
        viewModelScope.launch {
            _messages.emit(if (ids.size > 1) "Removed ${ids.size} clips" else "Clip deleted")
        }
    }

    fun rippleDeleteSelectedClip() {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val ownerTrack = doc.tracks.firstOrNull { it.id == clip.trackId }
        if (ownerTrack?.isLocked == true) {
            viewModelScope.launch { _messages.emit("Track ${ownerTrack.name} is locked") }
            return
        }
        val updated = timelineEngine.rippleDeleteClip(doc, clip.clipId)
        selectedClipIds.value = emptySet()
        commitDocumentEdit("Ripple Delete", doc, updated)
        viewModelScope.launch { _messages.emit("Ripple deleted clip") }
    }

    fun nudgeTrimLeft(deltaMs: Long) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val res = timelineEngine.trimClipLeft(
            document = doc,
            clipId = clip.clipId,
            desiredStartMs = clip.timelineStartMs + deltaMs,
            snappingEnabled = false,
        )
        if (res.document != doc) {
            commitDocumentEdit("Trim In", doc, res.document)
        }
    }

    fun nudgeTrimRight(deltaMs: Long) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val assetDuration = uiState.value.selectedClipAsset
            ?.takeIf { it.type != MediaType.IMAGE }
            ?.durationMs ?: 0L
        val res = timelineEngine.trimClipRight(
            document = doc,
            clipId = clip.clipId,
            desiredEndMs = clip.timelineStartMs + clip.durationMs + deltaMs,
            maxSourceDurationMs = assetDuration,
            snappingEnabled = false,
        )
        if (res.document != doc) {
            commitDocumentEdit("Trim Out", doc, res.document)
        }
    }

    fun beginClipGesture() {
        pausePlayback()
        if (!guardWritableOrEmit()) return
        val current = uiState.value.project ?: return
        editSession.beginGesture(current)
    }

    fun previewTrimLeft(clipId: String, desiredStartMs: Long) {
        val base = editSession.draftDocument.value ?: return
        val res = timelineEngine.trimClipLeft(
            document = base,
            clipId = clipId,
            desiredStartMs = desiredStartMs,
            snappingEnabled = snappingEnabled.value,
            playheadMs = playheadMs.value,
        )
        editSession.updateDraft(res.document)
        activeSnapGuideMs.value = res.snappedTargetMs
    }

    fun previewTrimRight(clipId: String, desiredEndMs: Long) {
        val base = editSession.draftDocument.value ?: return
        val clip = base.tracks.flatMap { it.clips }.firstOrNull { it.clipId == clipId } ?: return
        val assetDuration = uiState.value.assetsById[clip.assetId]
            ?.takeIf { it.type != MediaType.IMAGE }
            ?.durationMs ?: 0L
        val res = timelineEngine.trimClipRight(
            document = base,
            clipId = clipId,
            desiredEndMs = desiredEndMs,
            maxSourceDurationMs = assetDuration,
            snappingEnabled = snappingEnabled.value,
            playheadMs = playheadMs.value,
        )
        editSession.updateDraft(res.document)
        activeSnapGuideMs.value = res.snappedTargetMs
    }

    fun previewMoveClip(clipId: String, desiredStartMs: Long, targetTrackId: String?) {
        val base = editSession.draftDocument.value ?: return
        val clip = base.tracks.flatMap { it.clips }.firstOrNull { it.clipId == clipId } ?: return
        val assetType = uiState.value.assetsById[clip.assetId]?.type
        val res = timelineEngine.moveClip(
            document = base,
            clipId = clipId,
            desiredStartMs = desiredStartMs,
            targetTrackId = targetTrackId,
            assetType = assetType,
            snappingEnabled = snappingEnabled.value,
            playheadMs = playheadMs.value,
        )
        editSession.updateDraft(res.document)
        activeSnapGuideMs.value = res.snappedTargetMs
    }

    fun commitClipGesture(label: String) {
        val changed = editSession.finishGesture()
        activeSnapGuideMs.value = null
        if (changed != null) {
            commitDocumentEdit(label, changed.first, changed.second)
        }
    }

    fun cancelClipGesture() {
        editSession.cancelGesture()
        activeSnapGuideMs.value = null
    }

    // --- Phase 4: Quick Edit Engine Operations ---

    fun addTextClip(
        preset: TextStylePreset = TextStylePreset.BOLD_TITLE,
        atPlayhead: Boolean = true,
    ) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val spec = EffectsEngine.defaultTextSpec(preset)
        val startMs = if (atPlayhead) playheadMs.value else null
        val (updated, created) = timelineEngine.insertTextClip(
            document = doc,
            spec = spec,
            targetTrackId = selectedTrackId.value.takeIf { id ->
                doc.tracks.firstOrNull { it.id == id }?.type == TrackType.TEXT
            },
            atTimelineMs = startMs,
        )
        if (created != null) {
            selectedClipIds.value = setOf(created.clipId)
            selectedTrackId.value = created.trackId
            playheadMs.value = created.timelineStartMs
            commitDocumentEdit("Add Text", doc, updated)
            viewModelScope.launch { _messages.emit("Added ${preset.label} text clip") }
        } else {
            viewModelScope.launch { _messages.emit("Unlock a Text track to add captions") }
        }
    }

    fun updateSelectedTextSpec(spec: TextOverlaySpec) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.TEXT) return
        val updated = timelineEngine.updateClipText(doc, clip.clipId, spec)
        commitDocumentEdit("Edit Text", doc, updated)
    }

    fun previewSelectedTextSpec(spec: TextOverlaySpec) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.TEXT) return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipText(base, clip.clipId, spec))
    }

    fun updateSelectedClipSpeed(newSpeed: Float) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val isImage = uiState.value.selectedClipAsset?.type == MediaType.IMAGE
        if (clip.clipKind == ClipKind.TEXT || isImage) {
            viewModelScope.launch { _messages.emit("Speed applies to video and audio clips") }
            return
        }
        val updated = timelineEngine.updateClipSpeed(doc, clip.clipId, newSpeed, isImageClip = false)
        commitDocumentEdit("Change Speed (${String.format("%.2gx", newSpeed)})", doc, updated)
    }

    fun updateSelectedClipAudio(
        volume: Float,
        isMuted: Boolean,
        fadeInMs: Long,
        fadeOutMs: Long,
    ) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipAudio(doc, clip.clipId, volume, isMuted, fadeInMs, fadeOutMs)
        commitDocumentEdit("Audio Settings", doc, updated)
    }

    fun previewSelectedClipAudio(
        volume: Float,
        isMuted: Boolean,
        fadeInMs: Long,
        fadeOutMs: Long,
    ) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(
            timelineEngine.updateClipAudio(base, clip.clipId, volume, isMuted, fadeInMs, fadeOutMs),
        )
    }

    fun updateSelectedClipTransition(transition: ClipTransition?) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipTransition(doc, clip.clipId, transition)
        commitDocumentEdit("Transition", doc, updated)
    }

    fun previewSelectedClipTransition(transition: ClipTransition?) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipTransition(base, clip.clipId, transition))
    }

    fun updateSelectedClipAdjustments(adjustments: ClipAdjustments) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val updated = timelineEngine.updateClipAdjustments(doc, clip.clipId, adjustments)
        commitDocumentEdit("Color & Filter", doc, updated)
    }

    fun previewSelectedClipAdjustments(adjustments: ClipAdjustments) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipAdjustments(base, clip.clipId, adjustments))
    }

    fun applySelectedAdjustmentsToAll() {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val updated = timelineEngine.applyAdjustmentsToAllVisualClips(doc, clip.adjustments)
        commitDocumentEdit("Apply Filter to All", doc, updated)
        viewModelScope.launch { _messages.emit("Applied filter to all visual clips") }
    }

    // --- Phase 5: Advanced Compositor Operations ---

    fun addAdjustmentClip(atPlayhead: Boolean = true) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val startMs = if (atPlayhead) playheadMs.value else null
        val (updated, created) = timelineEngine.insertAdjustmentClip(
            document = doc,
            targetTrackId = selectedTrackId.value.takeIf { id ->
                doc.tracks.firstOrNull { it.id == id }?.type == TrackType.ADJUSTMENT
            },
            atTimelineMs = startMs,
        )
        if (created != null) {
            selectedClipIds.value = setOf(created.clipId)
            selectedTrackId.value = created.trackId
            playheadMs.value = created.timelineStartMs
            commitDocumentEdit("Add Adjustment Layer", doc, updated)
            viewModelScope.launch { _messages.emit("Added adjustment layer clip") }
        } else {
            viewModelScope.launch { _messages.emit("Unlock an Adjustment track to add a layer") }
        }
    }

    fun updateSelectedClipTransform(transform: LayerTransform) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.ADJUSTMENT) return
        val updated = timelineEngine.updateClipTransform(doc, clip.clipId, transform)
        commitDocumentEdit("Layer Transform", doc, updated)
    }

    fun previewSelectedClipTransform(transform: LayerTransform) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.ADJUSTMENT) return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipTransform(base, clip.clipId, transform))
    }

    fun updateSelectedClipBlendMode(blendMode: BlendModePreset) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.ADJUSTMENT) return
        val updated = timelineEngine.updateClipBlendMode(doc, clip.clipId, blendMode)
        commitDocumentEdit("Blend Mode (${blendMode.label})", doc, updated)
    }

    fun updateSelectedClipMask(mask: LayerMask) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.MEDIA) return
        val updated = timelineEngine.updateClipMask(doc, clip.clipId, mask)
        commitDocumentEdit("Layer Mask", doc, updated)
    }

    fun previewSelectedClipMask(mask: LayerMask) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.MEDIA) return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipMask(base, clip.clipId, mask))
    }

    fun updateSelectedClipShaderEffect(shaderEffect: ClipShaderEffect) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val updated = timelineEngine.updateClipShaderEffect(doc, clip.clipId, shaderEffect)
        commitDocumentEdit("Shader FX (${shaderEffect.type.label})", doc, updated)
    }

    fun previewSelectedClipShaderEffect(shaderEffect: ClipShaderEffect) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipShaderEffect(base, clip.clipId, shaderEffect))
    }

    // --- Phase 6: Animation & Keyframe Graph Editor Operations ---

    fun selectKeyframeProperty(property: KeyframeProperty) {
        selectedKeyframeProperty.value = property
        selectedKeyframeIds.value = emptySet()
    }

    fun selectKeyframe(keyframeId: String?, multiSelect: Boolean = false) {
        if (keyframeId == null) {
            selectedKeyframeIds.value = emptySet()
            return
        }
        selectedKeyframeIds.value = if (multiSelect) {
            val cur = selectedKeyframeIds.value
            if (keyframeId in cur) cur - keyframeId else cur + keyframeId
        } else {
            setOf(keyframeId)
        }
    }

    fun jumpToClipRelativeTime(clipRelativeMs: Long) {
        val clip = uiState.value.selectedClip ?: return
        pausePlayback()
        seekTo(clip.timelineStartMs + clipRelativeMs.coerceIn(0L, clip.durationMs))
    }

    fun toggleKeyframeAtPlayhead(property: KeyframeProperty) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val clipRelMs = (playheadMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
        val existingTrack = clip.keyframeTracks.firstOrNull { it.property == property }
        val existingNearby = existingTrack?.keyframes?.firstOrNull { abs(it.timeMs - clipRelMs) <= 40L }
        if (existingNearby != null) {
            val updated = timelineEngine.removeKeyframes(doc, clip.clipId, property, setOf(existingNearby.id))
            selectedKeyframeIds.value = selectedKeyframeIds.value - existingNearby.id
            commitDocumentEdit("Remove ${property.label} Keyframe", doc, updated)
        } else {
            val currentValue = animationEngine.evaluateProperty(clip, property, clipRelMs)
            val (updated, created) = timelineEngine.upsertKeyframe(
                document = doc,
                clipId = clip.clipId,
                property = property,
                timeMs = clipRelMs,
                value = currentValue,
            )
            if (created != null) {
                selectedKeyframeIds.value = setOf(created.id)
                commitDocumentEdit("Add ${property.label} Keyframe", doc, updated)
            }
        }
    }

    fun previewValueAtPlayhead(property: KeyframeProperty, newValue: Float) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        val clipRelMs = (playheadMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
        val (updated, created) = timelineEngine.upsertKeyframe(
            document = base,
            clipId = clip.clipId,
            property = property,
            timeMs = clipRelMs,
            value = newValue,
        )
        if (created != null) {
            selectedKeyframeIds.value = setOf(created.id)
        }
        editSession.updateDraft(updated)
    }

    fun previewMoveKeyframe(
        property: KeyframeProperty,
        keyframeId: String,
        newTimeMs: Long,
        newValue: Float,
    ) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(
            timelineEngine.updateKeyframeById(
                document = base,
                clipId = clip.clipId,
                property = property,
                keyframeId = keyframeId,
                newTimeMs = newTimeMs,
                newValue = newValue,
            ),
        )
    }

    fun previewBezierHandle(
        property: KeyframeProperty,
        keyframeId: String,
        bezier: BezierCurveSpec,
    ) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(
            timelineEngine.updateKeyframeById(
                document = base,
                clipId = clip.clipId,
                property = property,
                keyframeId = keyframeId,
                interpolation = KeyframeInterpolation.CUBIC_BEZIER,
                bezier = bezier,
            ),
        )
    }

    fun updateSelectedKeyframesInterpolation(
        property: KeyframeProperty,
        interpolation: KeyframeInterpolation,
        bezier: BezierCurveSpec? = null,
    ) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val track = clip.keyframeTracks.firstOrNull { it.property == property } ?: return
        val targetIds = uiState.value.selectedKeyframeIds.ifEmpty {
            track.keyframes.firstOrNull()?.let { setOf(it.id) }.orEmpty()
        }
        if (targetIds.isEmpty()) return
        val updated = timelineEngine.updateMultipleKeyframesInterpolation(
            document = doc,
            clipId = clip.clipId,
            property = property,
            keyframeIds = targetIds,
            interpolation = interpolation,
            bezier = bezier,
        )
        commitDocumentEdit("Keyframe Easing (${interpolation.label})", doc, updated)
    }

    fun deleteSelectedKeyframes(property: KeyframeProperty) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val ids = uiState.value.selectedKeyframeIds
        if (ids.isEmpty()) return
        val updated = timelineEngine.removeKeyframes(doc, clip.clipId, property, ids)
        selectedKeyframeIds.value = emptySet()
        commitDocumentEdit("Delete Keyframes", doc, updated)
    }

    fun clearPropertyKeyframes(property: KeyframeProperty) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.clearKeyframeTrack(doc, clip.clipId, property)
        selectedKeyframeIds.value = emptySet()
        commitDocumentEdit("Clear ${property.label} Animation", doc, updated)
    }

    fun applyMotionPreset(preset: MotionPreset) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.applyMotionPresetToClip(doc, clip.clipId, preset)
        selectedKeyframeIds.value = emptySet()
        commitDocumentEdit("Motion Preset (${preset.label})", doc, updated)
        viewModelScope.launch { _messages.emit("Applied ${preset.label} animation") }
    }

    // --- Phase 7: Audio Engine Operations ---

    fun updateSelectedClipAudioEffect(effect: ClipAudioEffect) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipAudioEffect(doc, clip.clipId, effect)
        commitDocumentEdit("Audio FX (${effect.preset.label})", doc, updated)
    }

    fun previewSelectedClipAudioEffect(effect: ClipAudioEffect) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipAudioEffect(base, clip.clipId, effect))
    }

    fun addBeatMarkerAtPlayhead() {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val (updated, _) = timelineEngine.addTimelineMarker(
            document = doc,
            timeMs = playheadMs.value,
            label = "Beat ${doc.markers.size + 1}",
            isBeatMarker = true,
        )
        if (updated != doc) {
            commitDocumentEdit("Add Beat Marker", doc, updated)
            viewModelScope.launch { _messages.emit("Added beat marker") }
        }
    }

    fun detectBeatsForSelectedClip() {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val waveform = uiState.value.waveformsByAssetId[clip.assetId] ?: return
        val updated = timelineEngine.addBeatMarkersFromClip(doc, clip, waveform.beatTimestampsMs)
        if (updated != doc) {
            commitDocumentEdit("Auto-Detect Beats", doc, updated)
            viewModelScope.launch {
                _messages.emit("Detected ${updated.markers.size - doc.markers.size} beat marker(s)")
            }
        } else {
            viewModelScope.launch { _messages.emit("No new beats detected in selected clip range") }
        }
    }

    fun removeTimelineMarker(markerId: String) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.removeTimelineMarker(doc, markerId)
        if (updated != doc) {
            commitDocumentEdit("Remove Marker", doc, updated)
        }
    }

    fun clearAllTimelineMarkers() {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.clearTimelineMarkers(doc)
        if (updated != doc) {
            commitDocumentEdit("Clear Markers", doc, updated)
            viewModelScope.launch { _messages.emit("Cleared timeline markers") }
        }
    }

    fun toggleAuditionPcmMix() {
        audioCoordinator.toggleAuditionPcmMix(
            document = uiState.value.project,
            assetsById = uiState.value.assetsById,
            startMs = playheadMs.value,
            onBeforeStartAudition = { pausePlayback() },
        )
    }

    private fun stopPcmAudition() {
        audioCoordinator.stopAudition()
    }

    private fun ensureGestureBaseDocument(): ProjectDocument? {
        if (uiState.value.isReadOnlyFutureVersion) return null
        return editSession.ensureGestureBase(uiState.value.project)
    }

    fun importMedia(uris: List<Uri>, hintType: MediaType?) {
        if (uris.isEmpty() || !guardWritableOrEmit()) return
        viewModelScope.launch {
            isImporting.value = true
            try {
                val before = uiState.value.project
                val prefs = preferencesRepository.preferencesFlow.first()
                val report = projectRepository.importMediaToProject(
                    projectId = projectId,
                    uris = uris,
                    hintType = hintType,
                    appendToTimeline = prefs.autoAppendImportedToTimeline,
                )
                if (report.blockedByUnsupportedFutureSchema) {
                    _messages.emit("Import blocked: project uses an unsupported future schema")
                    return@launch
                }
                if (before != null && prefs.autoAppendImportedToTimeline && report.importedAssets.isNotEmpty()) {
                    val after = projectRepository.observeProject(projectId).first()
                    if (after != null && after != before) {
                        editSession.recordCommittedEdit("Import Media", before, after)
                    }
                }
                val message = buildString {
                    if (report.importedAssets.isNotEmpty()) {
                        append("Imported ${report.importedAssets.size} asset(s)")
                    }
                    if (report.failures.isNotEmpty()) {
                        if (isNotEmpty()) append(" • ")
                        append("${report.failures.size} unsupported/failed: ${report.failures.first().reason}")
                    }
                }
                if (message.isNotBlank()) {
                    _messages.emit(message)
                }
            } finally {
                isImporting.value = false
            }
        }
    }

    fun deleteAsset(assetId: String) {
        if (!guardWritableOrEmit()) return
        viewModelScope.launch {
            val deleted = projectRepository.deleteMediaAsset(assetId)
            if (!deleted) {
                _messages.emit("Asset protected: project schema is read-only")
                return@launch
            }
            editSession.pruneDeletedAsset(assetId)
            mediaAnalysisCoordinator.pruneAssetWaveform(assetId)
            _messages.emit("Asset deleted from project")
        }
    }

    private fun findSplittableClip(
        document: ProjectDocument,
        atMs: Long,
        selectedIds: Set<String>,
    ): TimelineClipRef? {
        val unlockedClips = document.tracks
            .filterNot { it.isLocked }
            .sortedBy { it.orderIndex }
            .flatMap { it.clips }

        val validAtPlayhead = unlockedClips.filter { clip ->
            atMs >= clip.timelineStartMs + 100L &&
                atMs <= (clip.timelineStartMs + clip.durationMs - 100L)
        }
        return validAtPlayhead.firstOrNull { it.clipId in selectedIds }
            ?: validAtPlayhead.firstOrNull()
    }

    // --- Phase 8: Export Engine Operations ---

    fun refreshExportHistory() {
        exportCoordinatorDelegate.refreshExportHistory()
    }

    fun updateExportSettings(settings: ExportSettingsSpec) {
        if (!guardWritableOrEmit()) return
        viewModelScope.launch {
            val outcome = projectRepository.updateProjectExportSettings(projectId, settings)
            if (outcome is ProjectSaveOutcome.Saved) {
                editSession.rebaseMetadata(outcome.document)
            }
        }
    }

    fun setPreferredExportPath(path: ExportRenderPath) {
        exportCoordinatorDelegate.setPreferredExportPath(path)
    }

    fun startExport() {
        exportCoordinatorDelegate.startExport(
            project = uiState.value.project,
            assetsById = uiState.value.assetsById,
            onBeforeStartExport = {
                pausePlayback()
                stopPcmAudition()
            },
        )
    }

    fun cancelExport() {
        exportCoordinatorDelegate.cancelExport()
    }

    fun resetExportState() {
        exportCoordinatorDelegate.resetExportState()
    }

    fun saveExportToGallery(artifact: ExportedArtifact) {
        exportCoordinatorDelegate.saveExportToGallery(artifact)
    }

    fun saveExportToUri(artifact: ExportedArtifact, destinationUri: Uri) {
        exportCoordinatorDelegate.saveExportToUri(artifact, destinationUri)
    }

    fun createShareIntent(artifact: ExportedArtifact): Intent? {
        return exportCoordinatorDelegate.createShareIntent(artifact)
    }

    fun deleteExportArtifact(artifactId: String) {
        exportCoordinatorDelegate.deleteExportArtifact(artifactId)
    }

    // --- Phase 9: Advanced Video Tools & Proxies ---

    fun setPreferProxyPlayback(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPreferProxyPlayback(enabled)
        }
    }

    fun updateSelectedClipSpeedRamp(preset: SpeedRampPreset, customPoints: List<SpeedPoint> = emptyList()) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val asset = uiState.value.selectedClipAsset
        val isImage = asset?.type == MediaType.IMAGE
        if (isImage) {
            viewModelScope.launch { _messages.emit("Speed ramping applies to video and audio clips") }
            return
        }
        val updated = timelineEngine.updateClipSpeedRamp(doc, clip.clipId, preset, customPoints, isImageClip = false)
        commitDocumentEdit("Speed Ramp (${preset.label})", doc, updated)
    }

    fun toggleSelectedClipReverse() {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val asset = uiState.value.selectedClipAsset
        if (asset?.type == MediaType.IMAGE) {
            viewModelScope.launch { _messages.emit("Reverse playback applies to video & audio clips") }
            return
        }
        val nextReversed = !clip.isReversed
        val updated = timelineEngine.updateClipReversed(doc, clip.clipId, nextReversed, isImageClip = false)
        commitDocumentEdit(if (nextReversed) "Reverse Clip" else "Forward Clip", doc, updated)
        viewModelScope.launch {
            _messages.emit(if (nextReversed) "Reverse playback enabled" else "Normal forward playback restored")
        }
    }

    fun previewSelectedClipChromaKey(spec: ChromaKeySpec) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipChromaKey(base, clip.clipId, spec))
    }

    fun updateSelectedClipChromaKey(spec: ChromaKeySpec) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipChromaKey(doc, clip.clipId, spec)
        commitDocumentEdit("Chroma Key", doc, updated)
    }

    fun previewSelectedClipLut(spec: ClipLutSpec) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        editSession.updateDraft(timelineEngine.updateClipLut(base, clip.clipId, spec))
    }

    fun updateSelectedClipLut(spec: ClipLutSpec) {
        if (!guardWritableOrEmit()) return
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipLut(doc, clip.clipId, spec)
        commitDocumentEdit("3D LUT (${spec.preset.label})", doc, updated)
    }

    fun importCustomCubeLut(uri: Uri) {
        if (!guardWritableOrEmit()) return
        mediaAnalysisCoordinator.importCustomCubeLut(
            uri = uri,
            selectedClip = uiState.value.selectedClip,
            currentDocumentProvider = { uiState.value.project },
            onCommitDocument = ::commitDocumentEdit,
        )
    }

    fun runMotionTrackingOnSelectedClip(roiNormX: Float, roiNormY: Float) {
        if (!guardWritableOrEmit()) return
        mediaAnalysisCoordinator.runMotionTracking(
            clip = uiState.value.selectedClip,
            asset = uiState.value.selectedClipAsset,
            roiNormX = roiNormX,
            roiNormY = roiNormY,
            currentDocumentProvider = { uiState.value.project },
            onCommitDocument = ::commitDocumentEdit,
        )
    }

    fun runStabilizationOnSelectedClip(strength: Float) {
        if (!guardWritableOrEmit()) return
        mediaAnalysisCoordinator.runStabilization(
            clip = uiState.value.selectedClip,
            asset = uiState.value.selectedClipAsset,
            strength = strength,
            currentDocumentProvider = { uiState.value.project },
            onCommitDocument = ::commitDocumentEdit,
        )
    }

    fun disableStabilizationOnSelectedClip() {
        if (!guardWritableOrEmit()) return
        mediaAnalysisCoordinator.disableStabilization(
            doc = uiState.value.project,
            clip = uiState.value.selectedClip,
            onCommitDocument = ::commitDocumentEdit,
        )
    }

    fun generateProxyForSelectedAsset() {
        mediaAnalysisCoordinator.generateProxy(uiState.value.selectedClipAsset)
    }

    fun cancelActiveProxyJob() {
        mediaAnalysisCoordinator.cancelActiveProxyJob()
    }

    fun deleteProxyForSelectedAsset() {
        mediaAnalysisCoordinator.deleteProxy(uiState.value.selectedClipAsset)
    }

    override fun onCleared() {
        playbackJob?.cancel()
        stopPcmAudition()
        super.onCleared()
    }

    companion object {
        fun factory(
            projectId: String,
            projectRepository: ProjectRepository,
            preferencesRepository: CutLabPreferencesRepository,
            pcmAudioExtractor: PcmAudioExtractor? = null,
            mixedAudioPreviewPlayer: MixedAudioPreviewPlayer? = null,
            exportPlanner: ExportPlanner = ExportPlanner(),
            exportOutputManager: ExportOutputManager? = null,
            exportCoordinator: CutLabExportCoordinator? = null,
            proxyMediaManager: ProxyMediaManager? = null,
            appContext: Context? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProjectWorkspaceViewModel(
                    projectId = projectId,
                    projectRepository = projectRepository,
                    preferencesRepository = preferencesRepository,
                    pcmAudioExtractor = pcmAudioExtractor,
                    mixedAudioPreviewPlayer = mixedAudioPreviewPlayer,
                    exportPlanner = exportPlanner,
                    exportOutputManager = exportOutputManager,
                    exportCoordinator = exportCoordinator,
                    proxyMediaManager = proxyMediaManager,
                    appContext = appContext,
                ) as T
            }
        }
    }
}
