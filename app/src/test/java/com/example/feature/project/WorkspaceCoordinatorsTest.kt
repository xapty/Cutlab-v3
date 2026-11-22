package com.example.feature.project

import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectEngine
import com.example.engine.project.PropertyKeyframe
import com.example.engine.project.StabilizationSpec
import com.example.engine.timeline.TimelineEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceCoordinatorsTest {

    private val projectEngine = ProjectEngine()
    private val timelineEngine = TimelineEngine()

    private fun sampleVideoAsset(id: String = "v1") = MediaAsset(
        id = id,
        projectId = "proj-1",
        type = MediaType.VIDEO,
        displayName = "$id.mp4",
        sourceUri = "content://media/$id",
        managedFilePath = "/tmp/$id.mp4",
        mimeType = "video/mp4",
        durationMs = 3_000L,
        width = 1080,
        height = 1920,
        fps = 30f,
        bitrate = 2_000_000L,
        fileSizeBytes = 500_000L,
        status = MediaStatus.READY,
        importedAt = 1_000L,
    )

    @Test
    fun `workspaceEditSession batches gestures and safely filters selected clip IDs on undo redo`() {
        val session = WorkspaceEditSession()
        val base = projectEngine.createNewDocument("Test", AspectRatioPreset.VERTICAL_9_16, 30, EditorMode.ADVANCED)
        val (withClip, createdClip) = timelineEngine.insertAssetAsClip(base, sampleVideoAsset())
        assertTrue(session.recordCommittedEdit("Insert Clip", base, withClip))
        val clipId = createdClip!!.clipId

        // Begin a gesture, update draft twice, then commit as a single history step
        session.beginGesture(withClip)
        val draft1 = timelineEngine.updateClipSpeed(withClip, clipId, 1.5f)
        session.updateDraft(draft1)
        val draft2 = timelineEngine.updateClipSpeed(withClip, clipId, 2.0f)
        session.updateDraft(draft2)
        val committed = session.finishGesture()
        assertNotNull(committed)
        assertNull(session.draftDocument.value)
        assertTrue(session.recordCommittedEdit("Change Speed", committed!!.first, committed.second))

        // Undo speed change keeps clipId selected
        val undoSpeed = session.undo(setOf(clipId))
        assertNotNull(undoSpeed)
        assertEquals(setOf(clipId), undoSpeed!!.filteredSelectedClipIds)

        // Undo clip insert prunes clipId from selection because clip no longer exists
        val undoInsert = session.undo(setOf(clipId))
        assertNotNull(undoInsert)
        assertTrue(undoInsert!!.filteredSelectedClipIds.isEmpty())

        // Rebase metadata updates history without creating stale names
        val renamed = base.copy(name = "Renamed Studio")
        session.rebaseMetadata(renamed)
        val redoInsert = session.redo(emptySet())
        assertEquals("Renamed Studio", redoInsert!!.document.name)
    }

    @Test
    fun `workspaceAudioCoordinator emits unavailable message when extractor or player missing`() = runTest {
        val messages = mutableListOf<String>()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val audioCoordinator = WorkspaceAudioCoordinator(
            scope = scope,
            pcmAudioExtractor = null,
            mixedAudioPreviewPlayer = null,
            emitMessage = { messages += it },
        )
        val doc = projectEngine.createNewDocument("Audio", AspectRatioPreset.SQUARE_1_1, 30, EditorMode.QUICK)
        audioCoordinator.toggleAuditionPcmMix(
            document = doc,
            assetsById = emptyMap(),
            startMs = 0L,
            onBeforeStartAudition = {},
        )
        scope.advanceUntilIdle()
        assertFalse(audioCoordinator.isAuditioningMix.value)
        assertTrue(messages.any { it.contains("unavailable", ignoreCase = true) })
    }

    @Test
    fun `workspaceExportCoordinator updates path and handles missing coordinator cleanly`() = runTest {
        val messages = mutableListOf<String>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val coordinator = WorkspaceExportCoordinator(
            scope = scope,
            projectId = "proj-1",
            probeDispatcher = dispatcher,
            emitMessage = { messages += it },
        )
        coordinator.initialize()
        coordinator.setPreferredExportPath(com.example.engine.export.ExportRenderPath.CUSTOM_GL_CODEC)
        assertEquals(
            com.example.engine.export.ExportRenderPath.CUSTOM_GL_CODEC,
            coordinator.preferredExportPath.value,
        )
        val doc = projectEngine.createNewDocument("Export", AspectRatioPreset.LANDSCAPE_16_9, 30, EditorMode.QUICK)
        coordinator.startExport(doc, emptyMap(), onBeforeStartExport = {})
        scope.advanceUntilIdle()
        assertTrue(messages.any { it.contains("unavailable", ignoreCase = true) })
    }

    @Test
    fun `workspaceMediaAnalysisCoordinator disableStabilization clears stabilization state`() = runTest {
        val messages = mutableListOf<String>()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val mediaCoordinator = WorkspaceMediaAnalysisCoordinator(
            scope = scope,
            timelineEngine = timelineEngine,
            emitMessage = { messages += it },
        )
        val doc0 = projectEngine.createNewDocument("Stab", AspectRatioPreset.VERTICAL_9_16, 30, EditorMode.ADVANCED)
        val (doc1, clip) = timelineEngine.insertAssetAsClip(doc0, sampleVideoAsset())
        val stabilized = timelineEngine.updateClipStabilization(
            document = doc1,
            clipId = clip!!.clipId,
            stabilization = StabilizationSpec(enabled = true, strength = 0.8f, cropScale = 1.15f),
            generatedTracks = listOf(
                com.example.engine.project.KeyframeTrack(
                    property = KeyframeProperty.SCALE_X,
                    keyframes = listOf(PropertyKeyframe(id = "kf-1", timeMs = 0L, value = 1.15f)),
                ),
            ),
        )
        var committedPair: Pair<com.example.engine.project.ProjectDocument, com.example.engine.project.ProjectDocument>? = null
        mediaCoordinator.disableStabilization(
            doc = stabilized,
            clip = stabilized.tracks.flatMap { it.clips }.first { it.clipId == clip.clipId },
            onCommitDocument = { _, before, after -> committedPair = before to after },
        )
        scope.advanceUntilIdle()
        assertNotNull(committedPair)
        val updatedClip = committedPair!!.second.tracks.flatMap { it.clips }.first { it.clipId == clip.clipId }
        assertFalse(updatedClip.stabilization.enabled)
        assertEquals(1f, updatedClip.stabilization.cropScale, 0.001f)
    }
}
