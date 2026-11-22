package com.example.feature.project

import com.example.engine.project.ProjectDocument
import com.example.engine.timeline.TimelineCommandHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditRevisionOutcome(
    val label: String,
    val document: ProjectDocument,
    val filteredSelectedClipIds: Set<String>,
)

/**
 * Encapsulates bounded session-scoped Undo/Redo history and transient gesture draft staging
 * so scrubbing/dragging sliders or trim handles only commit one history step on release.
 */
class WorkspaceEditSession(
    private val commandHistory: TimelineCommandHistory = TimelineCommandHistory(),
) {
    private val _draftDocument = MutableStateFlow<ProjectDocument?>(null)
    val draftDocument: StateFlow<ProjectDocument?> = _draftDocument.asStateFlow()

    private val _historyVersion = MutableStateFlow(0)
    val historyVersion: StateFlow<Int> = _historyVersion.asStateFlow()

    private var gestureStartDocument: ProjectDocument? = null

    val canUndo: Boolean get() = commandHistory.canUndo
    val canRedo: Boolean get() = commandHistory.canRedo
    val undoLabel: String? get() = commandHistory.undoLabel
    val redoLabel: String? get() = commandHistory.redoLabel

    fun beginGesture(currentDocument: ProjectDocument) {
        gestureStartDocument = currentDocument
        _draftDocument.value = currentDocument
    }

    fun ensureGestureBase(currentDocument: ProjectDocument?): ProjectDocument? {
        val base = _draftDocument.value ?: currentDocument ?: return null
        if (gestureStartDocument == null) {
            gestureStartDocument = base
        }
        return base
    }

    fun updateDraft(updated: ProjectDocument) {
        _draftDocument.value = updated
    }

    /**
     * Finalizes an active gesture and records a single Undo entry if the document changed.
     * Returns `Pair(before, after)` when a durable save is needed, or null if no-op.
     */
    fun finishGesture(): Pair<ProjectDocument, ProjectDocument>? {
        val before = gestureStartDocument
        val after = _draftDocument.value
        gestureStartDocument = null
        _draftDocument.value = null
        return if (before != null && after != null && before != after) {
            before to after
        } else {
            null
        }
    }

    fun cancelGesture() {
        gestureStartDocument = null
        _draftDocument.value = null
    }

    fun recordCommittedEdit(
        label: String,
        before: ProjectDocument,
        after: ProjectDocument,
    ): Boolean {
        if (before == after) return false
        commandHistory.record(label, before, after)
        _historyVersion.value++
        return true
    }

    fun undo(currentSelectedClipIds: Set<String>): EditRevisionOutcome? {
        cancelGesture()
        val rev = commandHistory.undo() ?: return null
        _historyVersion.value++
        val validIds = rev.before.tracks.flatMap { it.clips }.map { it.clipId }.toSet()
        return EditRevisionOutcome(
            label = rev.label,
            document = rev.before,
            filteredSelectedClipIds = currentSelectedClipIds.intersect(validIds),
        )
    }

    fun redo(currentSelectedClipIds: Set<String>): EditRevisionOutcome? {
        cancelGesture()
        val rev = commandHistory.redo() ?: return null
        _historyVersion.value++
        val validIds = rev.after.tracks.flatMap { it.clips }.map { it.clipId }.toSet()
        return EditRevisionOutcome(
            label = rev.label,
            document = rev.after,
            filteredSelectedClipIds = currentSelectedClipIds.intersect(validIds),
        )
    }

    fun rebaseMetadata(latest: ProjectDocument) {
        commandHistory.rebaseMetadata(latest)
    }

    fun pruneDeletedAsset(assetId: String) {
        commandHistory.pruneDeletedAsset(assetId)
        _historyVersion.value++
    }
}
