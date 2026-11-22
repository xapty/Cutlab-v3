package com.example.engine.project

import com.example.core.common.RationalTime
import com.example.core.common.TimeFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEngineTest {
    private val engine = ProjectEngine()

    @Test
    fun `createNewDocument initializes default non-destructive tracks and schema`() {
        val doc = engine.createNewDocument(
            name = "  Anime AMV  ",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 60,
            editorMode = EditorMode.ADVANCED,
            nowMs = 1_000L,
        )

        assertEquals(CURRENT_PROJECT_SCHEMA_VERSION, doc.schemaVersion)
        assertEquals("Anime AMV", doc.name)
        assertEquals(1920, doc.canvasWidth)
        assertEquals(1080, doc.canvasHeight)
        assertEquals(60, doc.fps)
        assertEquals(EditorMode.ADVANCED, doc.editorMode)
        assertEquals(4, doc.tracks.size)
        assertEquals(listOf("V1 Main", "OV1 Overlay", "T1 Titles", "A1 Audio"), doc.tracks.map { it.name })
    }

    @Test
    fun `switching between quick and advanced mode preserves tracks and clips`() {
        val initial = engine.createNewDocument(
            name = "Reels Cut",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val asset = MediaAsset(
            id = "asset-1",
            projectId = initial.id,
            type = MediaType.VIDEO,
            displayName = "clip.mp4",
            sourceUri = "content://media/1",
            managedFilePath = "/tmp/clip.mp4",
            mimeType = "video/mp4",
            durationMs = 4_500L,
            width = 1080,
            height = 1920,
            fps = 30f,
            bitrate = 5_000_000L,
            fileSizeBytes = 2_400_000L,
            status = MediaStatus.READY,
            importedAt = 2_000L,
        )
        val withAsset = engine.appendAssetToFoundationTrack(initial, asset)
        val switched = engine.switchMode(withAsset, EditorMode.ADVANCED)
        val serialized = engine.serialize(switched)
        val restored = engine.deserialize(serialized)

        assertEquals(EditorMode.ADVANCED, restored.editorMode)
        assertEquals(1, restored.tracks.first { it.type == TrackType.VIDEO }.clips.size)
        assertEquals(4_500L, restored.tracks.first { it.type == TrackType.VIDEO }.clips.first().durationMs)
    }

    @Test
    fun `duplicateDocument creates distinct id and preserves canvas specs`() {
        val source = engine.createNewDocument(
            name = "Master",
            aspectRatio = AspectRatioPreset.SQUARE_1_1,
            fps = 24,
            editorMode = EditorMode.QUICK,
        )
        val copy = engine.duplicateDocument(source, "Master Copy")

        assertNotEquals(source.id, copy.id)
        assertEquals("Master Copy", copy.name)
        assertEquals(source.canvasWidth, copy.canvasWidth)
        assertEquals(source.fps, copy.fps)
        assertTrue(copy.tracks.size == source.tracks.size)
    }

    @Test
    fun `rational time and timecode formatting handle 60fps accurately`() {
        val t = RationalTime.fromFrames(90L, fps = 60)
        assertEquals(1_500L, t.toMillis())
        assertEquals("00:01:30", TimeFormatting.formatTimecode(1_500L, fps = 60))
    }

    @Test
    fun `openProjectJson and decodeFutureSchemaReadOnlyPreview separate future previews and corrupt json from editable decode`() {
        val baseDoc = engine.createNewDocument("Future Guard", AspectRatioPreset.VERTICAL_9_16, 30, EditorMode.QUICK)
        val futureJson = """
            {
              "schemaVersion": 4,
              "id": "${baseDoc.id}",
              "name": "Future v4 Edit",
              "aspectRatio": "VERTICAL_9_16",
              "canvasWidth": 1080,
              "canvasHeight": 1920,
              "fps": 30,
              "editorMode": "QUICK",
              "tracks": [],
              "futureLyricLayer": { "mode": "INVERT" }
            }
        """.trimIndent()

        // Supported v2 document decodes cleanly for editing
        val supportedOpen = engine.openProjectJson(engine.serialize(baseDoc))
        assertTrue(supportedOpen is ProjectOpenResult.Supported)
        assertNull(engine.decodeFutureSchemaReadOnlyPreview(engine.serialize(baseDoc)))

        // Future v4 rejects editable decode and serialization, while preserving raw JSON in read-only preview
        assertThrows(UnsupportedFutureSchemaException::class.java) {
            engine.deserialize(futureJson)
        }
        assertThrows(UnsupportedFutureSchemaException::class.java) {
            engine.deserializeWithReport(futureJson)
        }
        val preview = engine.decodeFutureSchemaReadOnlyPreview(futureJson)
        assertTrue(preview != null)
        assertEquals(4, preview!!.detectedSchemaVersion)
        assertEquals(4, preview.readOnlyPreviewDocument?.schemaVersion)
        assertEquals(futureJson, preview.rawJson)
        assertThrows(UnsupportedFutureSchemaException::class.java) {
            engine.serialize(baseDoc.copy(schemaVersion = 4))
        }

        // Corrupt JSON returns ProjectOpenResult.Corrupt and never decodes as future preview
        val corruptOpen = engine.openProjectJson("{ not valid json")
        assertTrue(corruptOpen is ProjectOpenResult.Corrupt)
        assertNull(engine.decodeFutureSchemaReadOnlyPreview("{ not valid json"))
    }
}
