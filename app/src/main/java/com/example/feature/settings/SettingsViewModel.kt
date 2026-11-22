package com.example.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.database.StudioPreferences
import com.example.core.performance.BenchmarkRecord
import com.example.core.performance.DevicePerformanceMonitor
import com.example.core.performance.PerformanceSnapshot
import com.example.core.performance.PreviewQualityMode
import com.example.engine.export.CutLabExportCoordinator
import com.example.engine.export.HardwareExportCapabilities
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: StudioPreferences = StudioPreferences(),
    val managedStorageBytes: Long = 0L,
    val performance: PerformanceSnapshot = PerformanceSnapshot(),
    val benchmarks: List<BenchmarkRecord> = emptyList(),
    val hardwareCapabilities: HardwareExportCapabilities = HardwareExportCapabilities(),
)

class SettingsViewModel(
    private val preferencesRepository: CutLabPreferencesRepository,
    private val projectRepository: ProjectRepository,
    private val performanceMonitor: DevicePerformanceMonitor? = null,
    private val exportCoordinator: CutLabExportCoordinator? = null,
) : ViewModel() {

    private val storageBytes = MutableStateFlow(0L)
    private val hardwareCapabilities = MutableStateFlow(HardwareExportCapabilities())
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    init {
        refreshStorageFootprint()
        refreshDiagnostics()
        if (exportCoordinator != null) {
            viewModelScope.launch(Dispatchers.Default) {
                hardwareCapabilities.value = exportCoordinator.hardwareCapabilities
            }
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferencesFlow,
        storageBytes,
        performanceMonitor?.snapshot ?: flowOf(PerformanceSnapshot()),
        performanceMonitor?.benchmarks ?: flowOf(emptyList()),
        hardwareCapabilities,
    ) { prefs, bytes, perf, benchmarks, caps ->
        SettingsUiState(
            preferences = prefs,
            managedStorageBytes = bytes,
            performance = perf,
            benchmarks = benchmarks,
            hardwareCapabilities = caps,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun refreshStorageFootprint() {
        viewModelScope.launch {
            storageBytes.value = projectRepository.getManagedStorageFootprintBytes()
        }
    }

    fun refreshDiagnostics() {
        performanceMonitor?.refreshRuntimeMetrics()
    }

    fun setDefaultAspectRatio(preset: AspectRatioPreset) {
        viewModelScope.launch { preferencesRepository.setDefaultAspectRatio(preset) }
    }

    fun setDefaultFps(fps: Int) {
        viewModelScope.launch { preferencesRepository.setDefaultFps(fps) }
    }

    fun setDefaultEditorMode(mode: EditorMode) {
        viewModelScope.launch { preferencesRepository.setDefaultEditorMode(mode) }
    }

    fun setAutoAppendImported(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoAppendImported(enabled) }
    }

    fun setPreferProxyPlayback(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setPreferProxyPlayback(enabled) }
    }

    fun setPreviewQualityMode(mode: PreviewQualityMode) {
        viewModelScope.launch {
            preferencesRepository.setPreviewQualityMode(mode)
            performanceMonitor?.setPreviewQualityMode(mode)
        }
    }

    fun pruneOrphanedMedia() {
        viewModelScope.launch {
            val pruned = projectRepository.pruneOrphanedMedia()
            refreshStorageFootprint()
            _messages.emit(
                if (pruned > 0) "Cleaned $pruned orphaned media file(s)" else "Storage is already clean",
            )
        }
    }

    fun clearRuntimeCaches() {
        performanceMonitor?.forceTrimAllCaches()
        viewModelScope.launch {
            _messages.emit("Released memory, thumbnail & waveform caches")
        }
    }

    fun runDeviceBenchmark() {
        val monitor = performanceMonitor ?: return
        if (monitor.snapshot.value.isBenchmarkRunning) return
        viewModelScope.launch {
            val record = monitor.runPipelineBenchmark()
            _messages.emit(
                "Benchmark complete: ${record.fps.toInt()} FPS (${record.durationMs}ms, ${"%.1f".format(record.throughputMbps)} Mbps)",
            )
        }
    }

    fun dismissCrashReport() {
        performanceMonitor?.clearLastCrashReport()
    }

    companion object {
        fun factory(
            preferencesRepository: CutLabPreferencesRepository,
            projectRepository: ProjectRepository,
            performanceMonitor: DevicePerformanceMonitor? = null,
            exportCoordinator: CutLabExportCoordinator? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    preferencesRepository = preferencesRepository,
                    projectRepository = projectRepository,
                    performanceMonitor = performanceMonitor,
                    exportCoordinator = exportCoordinator,
                ) as T
            }
        }
    }
}
