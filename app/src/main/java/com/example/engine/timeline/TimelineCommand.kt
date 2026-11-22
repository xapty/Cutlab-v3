package com.example.engine.timeline

import com.example.engine.project.ProjectDocument

data class TimelineRevision(
    val label: String,
    val before: ProjectDocument,
    val after: ProjectDocument,
)

/**
 * Bounded, session-scoped in-memory undo/redo command history.
 * Committed revisions are immediately persisted to Room via [ProjectRepository.saveDocument],
 * while [rebaseMetadata] and [pruneDeletedAsset] keep historical snapshots synchronized with
 * external metadata changes and permanent media deletions.
 */
class TimelineCommandHistory(
    private val maxEntries: Int = 60,
) {
    private val undoStack = ArrayDeque<TimelineRevision>()
    private val redoStack = ArrayDeque<TimelineRevision>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    fun record(label: String, before: ProjectDocument, after: ProjectDocument) {
        if (before == after) return
        if (undoStack.size >= maxEntries) {
            undoStack.removeFirst()
        }
        undoStack.addLast(TimelineRevision(label = label, before = before, after = after))
        redoStack.clear()
    }

    fun undo(): TimelineRevision? {
        val rev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(rev)
        return rev
    }

    fun redo(): TimelineRevision? {
        val rev = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(rev)
        return rev
    }

    /**
     * Synchronizes project-level metadata (name, editorMode, canvas specs, exportSettings) onto all
     * stored revisions so undoing/redoing timeline edits never reverts newer project settings.
     */
    fun rebaseMetadata(latest: ProjectDocument) {
        fun ProjectDocument.withLatestMetadata(): ProjectDocument = copy(
            schemaVersion = maxOf(schemaVersion, latest.schemaVersion),
            name = latest.name,
            aspectRatio = latest.aspectRatio,
            canvasWidth = latest.canvasWidth,
            canvasHeight = latest.canvasHeight,
            fps = latest.fps,
            editorMode = latest.editorMode,
            exportSettings = latest.exportSettings,
        )

        rebaseStack(undoStack) { rev ->
            rev.copy(
                before = rev.before.withLatestMetadata(),
                after = rev.after.withLatestMetadata(),
            )
        }
        rebaseStack(redoStack) { rev ->
            rev.copy(
                before = rev.before.withLatestMetadata(),
                after = rev.after.withLatestMetadata(),
            )
        }
    }

    /**
     * Removes clip references belonging to a permanently deleted [MediaAsset] so undo/redo cannot
     * resurrect ghost clips pointing to deleted media files.
     */
    fun pruneDeletedAsset(assetId: String) {
        fun ProjectDocument.withoutAsset(): ProjectDocument = copy(
            tracks = tracks.map { track ->
                track.copy(clips = track.clips.filterNot { it.assetId == assetId })
            },
        )

        rebaseStack(undoStack) { rev ->
            val nextBefore = rev.before.withoutAsset()
            val nextAfter = rev.after.withoutAsset()
            if (nextBefore.copy(updatedAt = 0L) == nextAfter.copy(updatedAt = 0L)) {
                null
            } else {
                rev.copy(before = nextBefore, after = nextAfter)
            }
        }
        rebaseStack(redoStack) { rev ->
            val nextBefore = rev.before.withoutAsset()
            val nextAfter = rev.after.withoutAsset()
            if (nextBefore.copy(updatedAt = 0L) == nextAfter.copy(updatedAt = 0L)) {
                null
            } else {
                rev.copy(before = nextBefore, after = nextAfter)
            }
        }
    }

    private fun rebaseStack(
        stack: ArrayDeque<TimelineRevision>,
        transform: (TimelineRevision) -> TimelineRevision?,
    ) {
        if (stack.isEmpty()) return
        val transformed = stack.mapNotNull(transform)
        stack.clear()
        transformed.forEach { stack.addLast(it) }
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
