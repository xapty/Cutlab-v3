package com.example.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.database.StudioPreferences
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.ProjectRepository
import com.example.engine.project.ProjectSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val projects: List<ProjectSummary> = emptyList(),
    val searchQuery: String = "",
    val selectedModeFilter: EditorMode? = null,
    val selectedRatioFilter: AspectRatioPreset? = null,
    val preferences: StudioPreferences = StudioPreferences(),
    val isLoading: Boolean = true,
)

sealed interface HomeEvent {
    data class OpenProject(val projectId: String) : HomeEvent
    data class ShowMessage(val message: String) : HomeEvent
}

class HomeViewModel(
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: CutLabPreferencesRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedModeFilter = MutableStateFlow<EditorMode?>(null)
    private val selectedRatioFilter = MutableStateFlow<AspectRatioPreset?>(null)

    private val _events = MutableSharedFlow<HomeEvent>()
    val events = _events.asSharedFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        projectRepository.observeProjectSummaries(),
        searchQuery,
        selectedModeFilter,
        selectedRatioFilter,
        preferencesRepository.preferencesFlow,
    ) { allProjects, query, modeFilter, ratioFilter, prefs ->
        val trimmedQuery = query.trim()
        val filtered = allProjects.filter { summary ->
            val matchesQuery = trimmedQuery.isEmpty() ||
                summary.document.name.contains(trimmedQuery, ignoreCase = true)
            val matchesMode = modeFilter == null || summary.document.editorMode == modeFilter
            val matchesRatio = ratioFilter == null || summary.document.aspectRatio == ratioFilter
            matchesQuery && matchesMode && matchesRatio
        }
        HomeUiState(
            projects = filtered,
            searchQuery = query,
            selectedModeFilter = modeFilter,
            selectedRatioFilter = ratioFilter,
            preferences = prefs,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun onSearchQueryChanged(query: String) {
        searchQuery.value = query
    }

    fun onModeFilterSelected(mode: EditorMode?) {
        selectedModeFilter.value = if (selectedModeFilter.value == mode) null else mode
    }

    fun onRatioFilterSelected(ratio: AspectRatioPreset?) {
        selectedRatioFilter.value = if (selectedRatioFilter.value == ratio) null else ratio
    }

    fun createProject(
        name: String,
        aspectRatio: AspectRatioPreset,
        fps: Int,
        editorMode: EditorMode,
    ) {
        viewModelScope.launch {
            val created = projectRepository.createProject(
                name = name,
                aspectRatio = aspectRatio,
                fps = fps,
                editorMode = editorMode,
            )
            _events.emit(HomeEvent.OpenProject(created.id))
        }
    }

    fun renameProject(projectId: String, newName: String) {
        viewModelScope.launch {
            when (val outcome = projectRepository.renameProject(projectId, newName)) {
                is com.example.engine.project.ProjectSaveOutcome.Saved ->
                    _events.emit(HomeEvent.ShowMessage("Project renamed"))
                is com.example.engine.project.ProjectSaveOutcome.BlockedUnsupportedFutureVersion ->
                    _events.emit(
                        HomeEvent.ShowMessage(
                            "Read-only project (schema v${outcome.detectedSchemaVersion} > supported v${outcome.supportedSchemaVersion})",
                        ),
                    )
                is com.example.engine.project.ProjectSaveOutcome.BlockedCorruptData ->
                    _events.emit(HomeEvent.ShowMessage("Project data unreadable; original row preserved"))
                is com.example.engine.project.ProjectSaveOutcome.ProjectNotFound -> Unit
            }
        }
    }

    fun duplicateProject(projectId: String) {
        viewModelScope.launch {
            val duplicated = projectRepository.duplicateProject(projectId)
            if (duplicated != null) {
                _events.emit(HomeEvent.ShowMessage("Duplicated \"${duplicated.name}\""))
            } else {
                _events.emit(HomeEvent.ShowMessage("Cannot duplicate unsupported or unreadable project"))
            }
        }
    }

    fun switchProjectMode(projectId: String, currentMode: EditorMode) {
        viewModelScope.launch {
            val target = if (currentMode == EditorMode.QUICK) EditorMode.ADVANCED else EditorMode.QUICK
            when (val outcome = projectRepository.switchEditorMode(projectId, target)) {
                is com.example.engine.project.ProjectSaveOutcome.Saved ->
                    _events.emit(HomeEvent.ShowMessage("Switched to ${target.label}"))
                is com.example.engine.project.ProjectSaveOutcome.BlockedUnsupportedFutureVersion ->
                    _events.emit(
                        HomeEvent.ShowMessage(
                            "Read-only project (schema v${outcome.detectedSchemaVersion} > supported v${outcome.supportedSchemaVersion})",
                        ),
                    )
                is com.example.engine.project.ProjectSaveOutcome.BlockedCorruptData ->
                    _events.emit(HomeEvent.ShowMessage("Project data unreadable; original row preserved"))
                is com.example.engine.project.ProjectSaveOutcome.ProjectNotFound -> Unit
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            projectRepository.deleteProject(projectId)
            _events.emit(HomeEvent.ShowMessage("Project deleted"))
        }
    }

    companion object {
        fun factory(
            projectRepository: ProjectRepository,
            preferencesRepository: CutLabPreferencesRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(projectRepository, preferencesRepository) as T
            }
        }
    }
}
