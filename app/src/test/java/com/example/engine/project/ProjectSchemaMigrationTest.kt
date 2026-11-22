package com.example.engine.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSchemaMigrationTest {

    private val engine = ProjectEngine()

    @Test
    fun `legacy v1 minimal json migrates cleanly to v2 with preserved clips and defaults`() {
        val legacyV1Json = """
            {
              "schemaVersion": 1,
              "id": "proj-v1-legacy",
              "name": "Legacy Cut",
              "aspectRatio": "VERTICAL_9_16",
              "canvasWidth": 1080,
              "canvasHeight": 1920,
              "fps": 24,
              "editorMode": "QUICK",
              "tracks": [
                {
                  "id": "track_v1",
                  "name": "V1 Main",
                  "type": "VIDEO",
                  "clips": [
                    {
                      "clipId": "c1",
                      "assetId": "a1",
                      "timelineStartMs": 0,
                      "sourceStartMs": 500,
                      "durationMs": 2500
                    }
                  ]
                },
                {
                  "id": "track_t1",
                  "name": "T1 Titles",
                  "type": "TEXT",
                  "clips": [
                    {
                      "clipId": "t1",
                      "assetId": "cutlab_text_layer",
                      "timelineStartMs": 200,
                      "sourceStartMs": 0,
                      "durationMs": 1200,
                      "textSpec": { "text": "INTRO" }
                    }
                  ]
                }
              ],
              "createdAt": 1000,
              "updatedAt": 2000
            }
        """.trimIndent()

        val result = engine.deserializeWithReport(legacyV1Json)
        assertTrue(result.didMigrate)
        assertFalse(result.isReadOnly)
        assertEquals(SchemaCompatibility.MIGRATED_FROM_LEGACY, result.compatibility)
        assertEquals(1, result.sourceSchemaVersion)
        assertEquals(CURRENT_PROJECT_SCHEMA_VERSION, result.document.schemaVersion)
        assertEquals(24, result.document.exportSettings.fps)
        assertTrue(result.document.markers.isEmpty())

        val vTrack = result.document.tracks.first { it.id == "track_v1" }
        assertEquals(0, vTrack.orderIndex)
        assertEquals("track_v1", vTrack.clips.first().trackId)
        assertEquals(ClipKind.MEDIA, vTrack.clips.first().clipKind)
        assertEquals(500L, vTrack.clips.first().sourceStartMs)

        val tTrack = result.document.tracks.first { it.id == "track_t1" }
        assertEquals(1, tTrack.orderIndex)
        assertEquals(ClipKind.TEXT, tTrack.clips.first().clipKind)
        assertEquals("INTRO", tTrack.clips.first().textSpec?.text)
    }

    @Test
    fun `future schema json is detected via read-only preview, rejected by editable decode and serializer, and preserves raw json`() {
        val futureJson = """
            {
              "schemaVersion": 3,
              "id": "proj-future",
              "name": "Future Lyrics Ready",
              "aspectRatio": "LANDSCAPE_16_9",
              "canvasWidth": 1920,
              "canvasHeight": 1080,
              "fps": 30,
              "editorMode": "ADVANCED",
              "captionTracks": [
                { "id": "cap_1", "language": "en", "cues": [] }
              ],
              "lyricAlignmentMode": "WORD_SYNC",
              "tracks": [
                {
                  "id": "track_v1",
                  "name": "V1 Main",
                  "type": "VIDEO",
                  "orderIndex": 0,
                  "clips": []
                }
              ],
              "exportSettings": {
                "resolutionHeight": 1080,
                "fps": 30,
                "codec": "H.264",
                "audioBitrateKbps": 192
              },
              "createdAt": 1000,
              "updatedAt": 2000
            }
        """.trimIndent()

        // 1. Future schema detection via read-only preview API (no downgrade, raw JSON intact)
        val openResult = engine.openProjectJson(futureJson)
        assertTrue(openResult is ProjectOpenResult.UnsupportedFutureVersion)
        val unsupported = openResult as ProjectOpenResult.UnsupportedFutureVersion
        assertEquals("proj-future", unsupported.projectId)
        assertEquals("Future Lyrics Ready", unsupported.projectName)
        assertEquals(3, unsupported.detectedSchemaVersion)
        assertEquals(CURRENT_PROJECT_SCHEMA_VERSION, unsupported.supportedSchemaVersion)
        assertEquals(setOf("captionTracks", "lyricAlignmentMode"), unsupported.unknownTopLevelKeys)
        assertEquals(futureJson, unsupported.rawJson)
        assertEquals(3, unsupported.readOnlyPreviewDocument?.schemaVersion)

        val readOnlyPreview = engine.decodeFutureSchemaReadOnlyPreview(futureJson)
        assertEquals(unsupported, readOnlyPreview)

        // 2. Ordinary editable decode paths must REJECT unsupported future schemas
        assertThrows(UnsupportedFutureSchemaException::class.java) {
            engine.deserialize(futureJson)
        }
        assertThrows(UnsupportedFutureSchemaException::class.java) {
            engine.deserializeEditable(futureJson)
        }
        assertThrows(UnsupportedFutureSchemaException::class.java) {
            engine.deserializeWithReport(futureJson)
        }
        assertThrows(UnsupportedFutureSchemaException::class.java) {
            engine.deserializeEditableWithReport(futureJson)
        }

        // 3. Direct serialization of read-only future preview document is also blocked
        assertThrows(UnsupportedFutureSchemaException::class.java) {
            engine.serialize(unsupported.readOnlyPreviewDocument!!)
        }
    }
}
