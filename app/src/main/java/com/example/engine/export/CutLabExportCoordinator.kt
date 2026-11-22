package com.example.engine.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.example.core.performance.DevicePerformanceMonitor

/**
 * Orchestrates CutLab's dual-path MP4 export workflow:
 * - Path A (Media3 Transformer) for sequential timelines without unsupported effects
 * - Path B (Custom OpenGL ES 2.0 + GLSL + MediaCodec + MultiTrackAudioMixer) for multi-track
 *   layers, text, shaders, SDF masks, keyframes, transitions, and audio DSP mixes.
 * Always validates the generated MP4 container before reporting success.
 */
class CutLabExportCoordinator(
    private val exportPlanner: ExportPlanner,
    private val outputManager: ExportOutputManager,
    private val media3Exporter: Media3TransformerExporter,
    private val customGlExporter: CustomGlVideoExporter,
    private val performanceMonitor: DevicePerformanceMonitor? = null,
) {
    private val _jobState = MutableStateFlow<ExportJobState>(ExportJobState.Idle)
    val jobState: StateFlow<ExportJobState> = _jobState.asStateFlow()

    val hardwareCapabilities: HardwareExportCapabilities by lazy {
        exportPlanner.probeHardwareCapabilities()
    }

    private var activeExportJob: Job? = null

    fun buildPlan(request: ExportRequest): ExportPlan {
        return exportPlanner.buildPlan(
            document = request.project,
            assetsById = request.assetsById,
            settings = request.settings,
            preferredPath = request.preferredPath,
            capabilities = hardwareCapabilities,
        )
    }

    fun startExport(
        scope: CoroutineScope,
        request: ExportRequest,
        onArtifactRecorded: (suspend (ExportedArtifact) -> Unit)? = null,
    ) {
        activeExportJob?.cancel()

        val initialPlan = buildPlan(request)
        if (!initialPlan.isExportable) {
            _jobState.value = ExportJobState.Failed(
                initialPlan.blockingError ?: initialPlan.reason,
            )
            return
        }

        val targetFile = outputManager.createTargetOutputFile(
            projectId = request.project.id,
            projectName = request.project.name,
        )
        val startedAtMs = System.currentTimeMillis()
        _jobState.value = ExportJobState.Preparing(initialPlan)

        activeExportJob = scope.launch(Dispatchers.Default) {
            var executedPlan = initialPlan
            try {
                val emitProgress: (Float, String) -> Unit = { progress, msg ->
                    _jobState.value = ExportJobState.Exporting(
                        plan = executedPlan,
                        progress = progress.coerceIn(0f, 0.99f),
                        statusMessage = msg,
                        elapsedMs = System.currentTimeMillis() - startedAtMs,
                    )
                }

                if (initialPlan.selectedPath == ExportRenderPath.MEDIA3_TRANSFORMER) {
                    try {
                        media3Exporter.export(
                            document = request.project,
                            assetsById = request.assetsById,
                            plan = initialPlan,
                            outputFile = targetFile,
                            onProgress = emitProgress,
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (pathAThrowable: Throwable) {
                        if (request.preferredPath == ExportRenderPath.MEDIA3_TRANSFORMER) {
                            throw pathAThrowable
                        }
                        // Fallback cleanly to Path B when Auto selected Path A and Transformer rejected source format
                        executedPlan = initialPlan.copy(
                            selectedPath = ExportRenderPath.CUSTOM_GL_CODEC,
                            reason = "Fallback to Path B (Custom GL) after Media3 rejected input (${pathAThrowable.message ?: "codec"})",
                        )
                        customGlExporter.export(
                            document = request.project,
                            assetsById = request.assetsById,
                            plan = executedPlan,
                            outputFile = targetFile,
                            onProgress = emitProgress,
                        )
                    }
                } else {
                    customGlExporter.export(
                        document = request.project,
                        assetsById = request.assetsById,
                        plan = initialPlan,
                        outputFile = targetFile,
                        onProgress = emitProgress,
                    )
                }

                _jobState.value = ExportJobState.Validating(executedPlan)
                val validation = outputManager.validateExportedMp4(
                    filePath = targetFile.absolutePath,
                    expectAudio = executedPlan.hasAudio,
                )

                if (!validation.isValid) {
                    runCatching { targetFile.delete() }
                    _jobState.value = ExportJobState.Failed(
                        validation.errorMessage ?: "Exported video failed container validation.",
                    )
                    return@launch
                }

                val artifact = outputManager.recordArtifact(
                    projectId = request.project.id,
                    file = targetFile,
                    plan = executedPlan,
                    validation = validation,
                )
                onArtifactRecorded?.invoke(artifact)
                val totalElapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
                performanceMonitor?.recordExportBenchmark(
                    label = "${request.project.name} (${artifact.width}x${artifact.height})",
                    durationMs = totalElapsedMs,
                    outputBytes = artifact.fileSizeBytes,
                    width = artifact.width,
                    height = artifact.height,
                    fps = artifact.fps,
                    renderPath = artifact.renderPathUsed,
                    validated = validation.isValid,
                )
                _jobState.value = ExportJobState.Succeeded(
                    artifact = artifact,
                    validation = validation,
                    plan = executedPlan,
                )
            } catch (ce: CancellationException) {
                runCatching { targetFile.delete() }
                _jobState.value = ExportJobState.Cancelled("Export cancelled by user.")
                throw ce
            } catch (t: Throwable) {
                runCatching { targetFile.delete() }
                _jobState.value = ExportJobState.Failed(
                    t.message?.takeIf { it.isNotBlank() } ?: "Export failed unexpectedly.",
                )
            }
        }
    }

    fun cancelExport() {
        activeExportJob?.cancel()
        activeExportJob = null
        _jobState.value = ExportJobState.Cancelled("Export cancelled by user.")
    }

    fun resetState() {
        if (_jobState.value !is ExportJobState.Exporting &&
            _jobState.value !is ExportJobState.Preparing &&
            _jobState.value !is ExportJobState.Validating
        ) {
            _jobState.value = ExportJobState.Idle
        }
    }
}
