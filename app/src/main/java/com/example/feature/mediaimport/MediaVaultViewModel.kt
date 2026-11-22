package com.example.feature.mediaimport

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MediaVaultUiState(
    val assets: List<MediaAsset> = emptyList(),
    val filter: MediaType? = null,
    val isImporting: Boolean = false,
)

class MediaVaultViewModel(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<MediaType?>(null)
    private val isImporting = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    val uiState: StateFlow<MediaVaultUiState> = combine(
        projectRepository.observeAllMediaAssets(),
        filter,
        isImporting,
    ) { assets, activeFilter, importing ->
        MediaVaultUiState(
            assets = if (activeFilter == null) assets else assets.filter { it.type == activeFilter },
            filter = activeFilter,
            isImporting = importing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MediaVaultUiState(),
    )

    fun toggleFilter(type: MediaType?) {
        filter.value = if (filter.value == type) null else type
    }

    fun importStandaloneMedia(uris: List<Uri>, hintType: MediaType?) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            isImporting.value = true
            try {
                val report = projectRepository.importMediaToProject(
                    projectId = null,
                    uris = uris,
                    hintType = hintType,
                    appendToTimeline = false,
                )
                val msg = buildString {
                    if (report.importedAssets.isNotEmpty()) {
                        append("Added ${report.importedAssets.size} asset(s) to Vault")
                    }
                    if (report.failures.isNotEmpty()) {
                        if (isNotEmpty()) append(" • ")
                        append("${report.failures.size} failed: ${report.failures.first().reason}")
                    }
                }
                if (msg.isNotBlank()) _messages.emit(msg)
            } finally {
                isImporting.value = false
            }
        }
    }

    fun deleteAsset(assetId: String) {
        viewModelScope.launch {
            projectRepository.deleteMediaAsset(assetId)
            _messages.emit("Media asset deleted")
        }
    }

    companion object {
        fun factory(projectRepository: ProjectRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MediaVaultViewModel(projectRepository) as T
                }
            }
    }
}
