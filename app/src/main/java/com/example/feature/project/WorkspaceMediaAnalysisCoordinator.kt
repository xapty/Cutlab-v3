package com.example.feature.project

import android.content.Context
import android.net.Uri
import com.example.core.media.ProxyJobState
import com.example.core.media.ProxyMediaManager
import com.example.engine.animation.MotionTrackingEngine
import com.example.engine.animation.TrackedMotionPoint
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.audio.WaveformEnvelope
import com.example.engine.effects.Lut3DEngine
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.LutPreset
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.StabilizationSpec
import com.example.engine.project.TimelineClipRef
import com.example.engine.timeline.TimelineEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Coordinates asynchronous media analysis workflows for the project workspace:
 * - Background PCM waveform & beat-transient extraction
 * - Custom Adobe `.cube` LUT file validation & import
 * - Optical SAD motion tracking and camera stabilization trajectory analysis
 * - 540p proxy media transcode job orchestration
 */
class WorkspaceMediaAnalysisCoordinator(
    private val scope: CoroutineScope,
    private val timelineEngine: TimelineEngine,
    private val pcmAudioExtractor: PcmAudioExtractor? = null,
    private val proxyMediaManager: ProxyMediaManager? = null,
    private val appContext: Context? = null,
    private val emitMessage: suspend (String) -> Unit = {},
) {
    private val _waveformsByAssetId = MutableStateFlow<Map<String, WaveformEnvelope>>(emptyMap())
    val waveformsByAssetId: StateFlow<Map<String, WaveformEnvelope>> = _waveformsByAssetId.asStateFlow()

    private val _isMotionAnalysisRunning = MutableStateFlow(false)
    val isMotionAnalysisRunning: StateFlow<Boolean> = _isMotionAnalysisRunning.asStateFlow()

    private val _lastMotionTrackPoints = MutableStateFlow<List<TrackedMotionPoint>>(emptyList())
    val lastMotionTrackPoints: StateFlow<List<TrackedMotionPoint>> = _lastMotionTrackPoints.asStateFlow()

    private val fallbackProxyJobState = MutableStateFlow<ProxyJobState>(ProxyJobState.Idle)
    val proxyJobState: StateFlow<ProxyJobState>
        get() = proxyMediaManager?.state ?: fallbackProxyJobState

    private val extractingAssetIds = Collections.synchronizedSet(mutableSetOf<String>())
    private var waveformObserverJob: Job? = null

    fun observeAssetsForWaveforms(assetsFlow: Flow<List<MediaAsset>>) {
        val extractor = pcmAudioExtractor ?: return
        waveformObserverJob?.cancel()
        waveformObserverJob = scope.launch {
            assetsFlow.collect { assets ->
                assets.forEach { asset ->
                    if (asset.type != MediaType.IMAGE &&
                        asset.status == MediaStatus.READY &&
                        !_waveformsByAssetId.value.containsKey(asset.id) &&
                        extractingAssetIds.add(asset.id)
                    ) {
                        launch {
                            try {
                                val envelope = extractor.extractWaveform(asset)
                                if (envelope != null) {
                                    _waveformsByAssetId.value = _waveformsByAssetId.value + (asset.id to envelope)
                                }
                            } finally {
                                extractingAssetIds.remove(asset.id)
                            }
                        }
                    }
                }
            }
        }
    }

    fun pruneAssetWaveform(assetId: String) {
        if (_waveformsByAssetId.value.containsKey(assetId)) {
            _waveformsByAssetId.value = _waveformsByAssetId.value - assetId
        }
    }

    fun importCustomCubeLut(
        uri: Uri,
        selectedClip: TimelineClipRef?,
        currentDocumentProvider: () -> ProjectDocument?,
        onCommitDocument: (label: String, before: ProjectDocument, after: ProjectDocument) -> Unit,
    ) {
        val ctx = appContext ?: run {
            scope.launch { emitMessage("Context unavailable for LUT import") }
            return
        }
        val clip = selectedClip ?: return
        scope.launch {
            val result = Lut3DEngine.importCubeFile(ctx, uri)
            result.onSuccess { (path, name) ->
                val latestDoc = currentDocumentProvider() ?: return@onSuccess
                val updated = timelineEngine.updateClipLut(
                    latestDoc,
                    clip.clipId,
                    ClipLutSpec(
                        preset = LutPreset.CUSTOM_CUBE,
                        intensity = 0.85f,
                        customCubePath = path,
                        customCubeName = name,
                    ),
                )
                onCommitDocument("Import .cube LUT ($name)", latestDoc, updated)
                emitMessage("Loaded 3D LUT: $name")
            }.onFailure { err ->
                emitMessage(err.message ?: "Failed parsing .cube LUT file")
            }
        }
    }

    fun runMotionTracking(
        clip: TimelineClipRef?,
        asset: MediaAsset?,
        roiNormX: Float,
        roiNormY: Float,
        currentDocumentProvider: () -> ProjectDocument?,
        onCommitDocument: (label: String, before: ProjectDocument, after: ProjectDocument) -> Unit,
    ) {
        val ctx = appContext
        if (ctx == null || clip == null || asset == null || asset.type != MediaType.VIDEO) {
            scope.launch { emitMessage("Select a video clip to run optical motion tracking") }
            return
        }
        if (_isMotionAnalysisRunning.value) return
        scope.launch {
            _isMotionAnalysisRunning.value = true
            try {
                val analysis = MotionTrackingEngine.analyzeTrackTarget(
                    context = ctx,
                    asset = asset,
                    clip = clip,
                    startNormX = roiNormX,
                    startNormY = roiNormY,
                )
                if (analysis.keyframeTracks.isEmpty()) {
                    emitMessage("Unable to sample video frames for motion tracking")
                } else {
                    _lastMotionTrackPoints.value = analysis.points
                    val doc = currentDocumentProvider() ?: return@launch
                    val updated = timelineEngine.mergeKeyframeTracks(doc, clip.clipId, analysis.keyframeTracks)
                    onCommitDocument("Optical Motion Track", doc, updated)
                    emitMessage("Tracked ${analysis.points.size} keyframes onto Position X/Y")
                }
            } finally {
                _isMotionAnalysisRunning.value = false
            }
        }
    }

    fun runStabilization(
        clip: TimelineClipRef?,
        asset: MediaAsset?,
        strength: Float,
        currentDocumentProvider: () -> ProjectDocument?,
        onCommitDocument: (label: String, before: ProjectDocument, after: ProjectDocument) -> Unit,
    ) {
        val ctx = appContext
        if (ctx == null || clip == null || asset == null || asset.type != MediaType.VIDEO) {
            scope.launch { emitMessage("Select a video clip to run video stabilization") }
            return
        }
        if (_isMotionAnalysisRunning.value) return
        scope.launch {
            _isMotionAnalysisRunning.value = true
            try {
                val analysis = MotionTrackingEngine.analyzeStabilization(
                    context = ctx,
                    asset = asset,
                    clip = clip,
                    strength = strength,
                )
                if (analysis.keyframeTracks.isEmpty()) {
                    emitMessage("Unable to sample video frames for stabilization")
                } else {
                    val doc = currentDocumentProvider() ?: return@launch
                    val spec = StabilizationSpec(
                        enabled = true,
                        strength = strength,
                        cropScale = analysis.cropScale,
                    )
                    val updated = timelineEngine.updateClipStabilization(
                        document = doc,
                        clipId = clip.clipId,
                        stabilization = spec,
                        generatedTracks = analysis.keyframeTracks,
                    )
                    onCommitDocument("Stabilize Clip", doc, updated)
                    emitMessage(
                        "Stabilized (${(strength * 100).toInt()}%, auto-crop ${String.format("%.2fx", analysis.cropScale)})",
                    )
                }
            } finally {
                _isMotionAnalysisRunning.value = false
            }
        }
    }

    fun disableStabilization(
        doc: ProjectDocument?,
        clip: TimelineClipRef?,
        onCommitDocument: (label: String, before: ProjectDocument, after: ProjectDocument) -> Unit,
    ) {
        if (doc == null || clip == null) return
        val updated = timelineEngine.updateClipStabilization(
            document = doc,
            clipId = clip.clipId,
            stabilization = StabilizationSpec(enabled = false, strength = clip.stabilization.strength, cropScale = 1f),
            generatedTracks = emptyList(),
        )
        onCommitDocument("Remove Stabilization", doc, updated)
        scope.launch { emitMessage("Stabilization transforms cleared") }
    }

    fun generateProxy(asset: MediaAsset?) {
        val mgr = proxyMediaManager
        if (asset == null || mgr == null) {
            scope.launch { emitMessage("Select a video clip first to generate a 540p proxy") }
            return
        }
        mgr.generateProxyForAsset(asset)
    }

    fun cancelActiveProxyJob() {
        proxyMediaManager?.cancelActiveProxyJob()
    }

    fun deleteProxy(asset: MediaAsset?) {
        if (asset == null) return
        val mgr = proxyMediaManager ?: return
        scope.launch {
            mgr.deleteProxyForAsset(asset)
            emitMessage("Deleted 540p proxy (original preserved)")
        }
    }
}
