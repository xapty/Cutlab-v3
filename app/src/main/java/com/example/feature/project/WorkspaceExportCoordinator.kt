package com.example.feature.project

import android.content.Intent
import android.net.Uri
import com.example.engine.export.CutLabExportCoordinator
import com.example.engine.export.ExportJobState
import com.example.engine.export.ExportOutputManager
import com.example.engine.export.ExportPlanner
import com.example.engine.export.ExportRenderPath
import com.example.engine.export.ExportRequest
import com.example.engine.export.ExportedArtifact
import com.example.engine.export.HardwareExportCapabilities
import com.example.engine.project.MediaAsset
import com.example.engine.project.ProjectDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates hardware export capability probing, export job launching/cancellation,
 * output artifact history tracking, and Gallery / SAF / Share delivery for a workspace project.
 */
class WorkspaceExportCoordinator(
    private val scope: CoroutineScope,
    private val projectId: String,
    private val exportPlanner: ExportPlanner = ExportPlanner(),
    private val exportOutputManager: ExportOutputManager? = null,
    private val exportCoordinator: CutLabExportCoordinator? = null,
    private val probeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val emitMessage: suspend (String) -> Unit = {},
) {
    private val _preferredExportPath = MutableStateFlow(ExportRenderPath.AUTO)
    val preferredExportPath: StateFlow<ExportRenderPath> = _preferredExportPath.asStateFlow()

    private val _hardwareCapabilities = MutableStateFlow(HardwareExportCapabilities())
    val hardwareCapabilities: StateFlow<HardwareExportCapabilities> = _hardwareCapabilities.asStateFlow()

    private val _projectExports = MutableStateFlow<List<ExportedArtifact>>(emptyList())
    val projectExports: StateFlow<List<ExportedArtifact>> = _projectExports.asStateFlow()

    private val fallbackExportJobState = MutableStateFlow<ExportJobState>(ExportJobState.Idle)
    val exportJobState: StateFlow<ExportJobState>
        get() = exportCoordinator?.jobState ?: fallbackExportJobState

    fun initialize() {
        scope.launch(probeDispatcher) {
            _hardwareCapabilities.value = exportCoordinator?.hardwareCapabilities
                ?: exportPlanner.probeHardwareCapabilities()
        }
        refreshExportHistory()
    }

    fun setPreferredExportPath(path: ExportRenderPath) {
        _preferredExportPath.value = path
    }

    fun refreshExportHistory() {
        val mgr = exportOutputManager ?: return
        scope.launch {
            _projectExports.value = mgr.listProjectExports(projectId)
        }
    }

    fun startExport(
        project: ProjectDocument?,
        assetsById: Map<String, MediaAsset>,
        onBeforeStartExport: () -> Unit,
    ) {
        val doc = project ?: return
        val coordinator = exportCoordinator ?: run {
            scope.launch { emitMessage("Export engine unavailable") }
            return
        }
        onBeforeStartExport()
        coordinator.startExport(
            scope = scope,
            request = ExportRequest(
                project = doc,
                assetsById = assetsById,
                settings = doc.exportSettings,
                preferredPath = _preferredExportPath.value,
            ),
            onArtifactRecorded = {
                refreshExportHistory()
            },
        )
    }

    fun cancelExport() {
        exportCoordinator?.cancelExport()
    }

    fun resetExportState() {
        exportCoordinator?.resetState()
    }

    fun saveExportToGallery(artifact: ExportedArtifact) {
        val mgr = exportOutputManager ?: return
        scope.launch {
            val uri = mgr.saveToGallery(artifact)
            if (uri != null) {
                refreshExportHistory()
                emitMessage("Saved to Movies/CutLab gallery")
            } else {
                emitMessage("Unable to save video to gallery")
            }
        }
    }

    fun saveExportToUri(artifact: ExportedArtifact, destinationUri: Uri) {
        val mgr = exportOutputManager ?: return
        scope.launch {
            val ok = mgr.writeToDestinationUri(artifact, destinationUri)
            if (ok) {
                refreshExportHistory()
                emitMessage("Saved MP4 to chosen location")
            } else {
                emitMessage("Failed writing MP4 to destination")
            }
        }
    }

    fun createShareIntent(artifact: ExportedArtifact): Intent? {
        return runCatching {
            exportOutputManager?.createShareIntent(artifact)
        }.getOrNull()
    }

    fun deleteExportArtifact(artifactId: String) {
        val mgr = exportOutputManager ?: return
        scope.launch {
            mgr.deleteArtifact(artifactId)
            refreshExportHistory()
            emitMessage("Removed exported file")
        }
    }
}
