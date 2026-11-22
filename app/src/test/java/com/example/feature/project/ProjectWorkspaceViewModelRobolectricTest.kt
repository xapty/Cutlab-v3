package com.example.feature.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.CutLabDatabase
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.database.ProjectEntity
import com.example.core.media.MediaMetadataExtractor
import com.example.core.storage.MediaStorageManager
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.MotionPreset
import com.example.engine.project.ProjectEngine
import com.example.engine.project.ProjectRepository
import com.example.engine.project.TrackType
import com.example.engine.timeline.TimelineOverlapRepairMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProjectWorkspaceViewModelRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: CutLabDatabase
    private lateinit var repository: ProjectRepository
    private lateinit var preferencesRepository: CutLabPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CutLabDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val storage = MediaStorageManager(context)
        repository = ProjectRepository(
            projectDao = database.projectDao(),
            mediaAssetDao = database.mediaAssetDao(),
            projectEngine = ProjectEngine(),
            storageManager = storage,
            metadataExtractor = MediaMetadataExtractor(storage),
        )
        preferencesRepository = CutLabPreferencesRepository(context)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `legacy overlap report inspects overflow vs shift without mutating until repairLegacyOverlaps is confirmed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val legacyOverlapJson = """
            {
              "schemaVersion": 1,
              "id": "vm-legacy-overlap",
              "name": "VM Legacy Overlap",
              "aspectRatio": "VERTICAL_9_16",
              "canvasWidth": 1080,
              "canvasHeight": 1920,
              "fps": 30,
              "editorMode": "ADVANCED",
              "tracks": [
                {
                  "id": "track_v1",
                  "name": "V1 Main",
                  "type": "VIDEO",
                  "orderIndex": 0,
                  "clips": [
                    {
                      "clipId": "c1",
                      "assetId": "a1",
                      "timelineStartMs": 0,
                      "sourceStartMs": 0,
                      "durationMs": 2000
                    },
                    {
                      "clipId": "c2",
                      "assetId": "a2",
                      "timelineStartMs": 500,
                      "sourceStartMs": 0,
                      "durationMs": 1800
                    }
                  ]
                },
                {
                  "id": "track_ov1",
                  "name": "O1 Overlay",
                  "type": "OVERLAY",
                  "orderIndex": 1,
                  "clips": []
                }
              ],
              "createdAt": 1000,
              "updatedAt": 2000
            }
        """.trimIndent()

        database.projectDao().upsertProject(
            ProjectEntity(
                id = "vm-legacy-overlap",
                name = "VM Legacy Overlap",
                aspectRatioName = AspectRatioPreset.VERTICAL_9_16.name,
                canvasWidth = 1080,
                canvasHeight = 1920,
                fps = 30,
                editorModeName = EditorMode.ADVANCED.name,
                schemaVersion = 1,
                projectJson = legacyOverlapJson,
                coverMediaUri = null,
                createdAt = 1_000L,
                updatedAt = 2_000L,
            ),
        )

        val viewModel = ProjectWorkspaceViewModel(
            projectId = "vm-legacy-overlap",
            projectRepository = repository,
            preferencesRepository = preferencesRepository,
        )
        val collectJob = backgroundScope.launch(dispatcher) {
            viewModel.uiState.collect {}
        }
        val state = viewModel.uiState.first { !it.isLoading && it.project != null }

        // Pending overlap report should surface before any confirmation, with timing untouched
        val pending = state.pendingOverlapRepair
        assertNotNull(pending)
        assertEquals(TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK, pending!!.mode)
        assertTrue(pending.timingPreserved)
        assertEquals(1, pending.affectedClips.size)
        val pendingEntry = pending.affectedClips.single()
        assertEquals("c2", pendingEntry.clipId)
        assertEquals("V1 Main", pendingEntry.sourceTrackName)
        assertEquals(500L, pendingEntry.originalStartMs)
        assertEquals("O1 Overlay", pendingEntry.proposedTrackName)
        assertFalse(pendingEntry.timingChanged)

        // Inspecting SHIFT_FORWARD_ON_TRACK previews timing changes without mutating project
        val shiftPreview = viewModel.inspectOverlapRepairReport(TimelineOverlapRepairMode.SHIFT_FORWARD_ON_TRACK)
        assertNotNull(shiftPreview)
        assertFalse(shiftPreview!!.timingPreserved)
        assertTrue(shiftPreview.affectedClips.single().timingChanged)
        assertEquals(2_000L, shiftPreview.affectedClips.single().proposedStartMs)
        assertEquals(
            500L,
            viewModel.uiState.value.project!!.tracks.first { it.id == "track_v1" }.clips[1].timelineStartMs,
        )

        // Confirming default repair moves clip onto overflow track while preserving 500ms timing
        viewModel.repairLegacyOverlaps(TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK)
        advanceUntilIdle()

        val repairedState = viewModel.uiState.first { it.pendingOverlapRepair == null }
        val movedClip = repairedState.project!!.tracks.flatMap { it.clips }.first { it.clipId == "c2" }
        assertEquals("track_ov1", movedClip.trackId)
        assertEquals(500L, movedClip.timelineStartMs)
        assertEquals(1_800L, movedClip.durationMs)
        assertTrue(repairedState.canUndo)

        collectJob.cancel()
    }

    @Test
    fun `future schema v4 in viewModel blocks mutating operations and preserves raw JSON`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)

        val futureRawJson = """
            {
              "schemaVersion": 4,
              "id": "vm-future-v3",
              "name": "Future Lyric Project",
              "aspectRatio": "VERTICAL_9_16",
              "canvasWidth": 1080,
              "canvasHeight": 1920,
              "fps": 30,
              "editorMode": "ADVANCED",
              "autoLyricTracks": [ { "id": "lyr_1" } ],
              "tracks": [
                {
                  "id": "track_v1",
                  "name": "V1 Main",
                  "type": "VIDEO",
                  "orderIndex": 0,
                  "clips": [
                    {
                      "clipId": "c1",
                      "assetId": "a1",
                      "timelineStartMs": 0,
                      "sourceStartMs": 0,
                      "durationMs": 2000
                    }
                  ]
                }
              ],
              "createdAt": 1000,
              "updatedAt": 2000
            }
        """.trimIndent()

        database.projectDao().upsertProject(
            ProjectEntity(
                id = "vm-future-v3",
                name = "Future Lyric Project",
                aspectRatioName = AspectRatioPreset.VERTICAL_9_16.name,
                canvasWidth = 1080,
                canvasHeight = 1920,
                fps = 30,
                editorModeName = EditorMode.ADVANCED.name,
                schemaVersion = 4,
                projectJson = futureRawJson,
                coverMediaUri = null,
                createdAt = 1_000L,
                updatedAt = 2_000L,
            ),
        )

        val viewModel = ProjectWorkspaceViewModel(
            projectId = "vm-future-v3",
            projectRepository = repository,
            preferencesRepository = preferencesRepository,
        )
        val emittedMessages = mutableListOf<String>()
        val collectJob = backgroundScope.launch(dispatcher) {
            viewModel.uiState.collect {}
        }
        val msgJob = backgroundScope.launch(dispatcher) {
            viewModel.messages.collect { emittedMessages += it }
        }
        val state = viewModel.uiState.first { !it.isLoading && it.project != null }
        assertTrue(state.isReadOnlyFutureVersion)
        assertEquals(4, state.detectedSchemaVersion)
        assertEquals(setOf("autoLyricTracks"), state.unsupportedFutureKeys)
        assertFalse(state.canUndo)
        assertFalse(state.canSplitAtPlayhead)

        viewModel.selectClip("c1")
        viewModel.addTrack(TrackType.OVERLAY)
        viewModel.toggleSelectedClipReverse()
        viewModel.applyMotionPreset(MotionPreset.SLIDE_IN)
        viewModel.renameProject("Hijack Name")
        advanceUntilIdle()

        assertTrue(emittedMessages.any { it.contains("Read-only", ignoreCase = true) })
        assertFalse(emittedMessages.any { it.contains("Reverse playback enabled", ignoreCase = true) })
        assertFalse(emittedMessages.any { it.contains("Applied", ignoreCase = true) })

        val stored = database.projectDao().getProjectById("vm-future-v3")!!
        assertEquals(4, stored.schemaVersion)
        assertEquals(futureRawJson, stored.projectJson)

        msgJob.cancel()
        collectJob.cancel()
    }
}
