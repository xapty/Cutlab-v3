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
              "schemaVersion": 4,
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
        assertEquals(4, unsupported.detectedSchemaVersion)
        assertEquals(CURRENT_PROJECT_SCHEMA_VERSION, unsupported.supportedSchemaVersion)
        assertEquals(setOf("captionTracks", "lyricAlignmentMode"), unsupported.unknownTopLevelKeys)
        assertEquals(futureJson, unsupported.rawJson)
        assertEquals(4, unsupported.readOnlyPreviewDocument?.schemaVersion)

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

    @Test
    fun `schema v2 project migrates cleanly to v3 preserving existing text clips and applying v3 defaults`() {
        val v2Json = """
            {
              "schemaVersion": 2,
              "id": "proj-v2-existing",
              "name": "Existing Cut v2",
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
                      "clipId": "v_clip",
                      "assetId": "asset_1",
                      "clipKind": "MEDIA",
                      "trackId": "track_v1",
                      "timelineStartMs": 0,
                      "sourceStartMs": 0,
                      "durationMs": 4000
                    }
                  ]
                },
                {
                  "id": "track_t1",
                  "name": "T1 Titles",
                  "type": "TEXT",
                  "orderIndex": 2,
                  "clips": [
                    {
                      "clipId": "text_legacy",
                      "assetId": "cutlab_text_layer",
                      "clipKind": "TEXT",
                      "trackId": "track_t1",
                      "timelineStartMs": 500,
                      "sourceStartMs": 0,
                      "durationMs": 2000,
                      "textSpec": {
                        "text": "HELLO V2",
                        "stylePreset": "BOLD_TITLE",
                        "animationPreset": "FADE_IN",
                        "fontScale": 1.2
                      }
                    }
                  ]
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

        val report = engine.deserializeEditableWithReport(v2Json)
        assertTrue(report.didMigrate)
        assertFalse(report.isReadOnly)
        assertEquals(2, report.sourceSchemaVersion)
        assertEquals(3, CURRENT_PROJECT_SCHEMA_VERSION)
        assertEquals(3, report.document.schemaVersion)

        val textClip = report.document.tracks.first { it.id == "track_t1" }.clips.single()
        val spec = textClip.textSpec!!
        assertEquals("HELLO V2", spec.text)
        assertEquals(TextStylePreset.BOLD_TITLE, spec.stylePreset)
        assertEquals(TextAnimationPreset.FADE_IN, spec.animationPreset)
        assertEquals(1.2f, spec.fontScale, 0.001f)
        // New v3 fields default safely
        assertEquals(TextContentType.TITLE, spec.contentType)
        assertEquals(TextRenderMode.NORMAL, spec.renderMode)
        assertEquals(KaraokeHighlightMode.NONE, spec.karaokeMode)
        assertTrue(spec.timedWords.isEmpty())
        assertEquals(1.0f, spec.invertStrength, 0.001f)
    }

    @Test
    fun `schema v3 document with timed words karaoke and invert lyric round-trips without data loss`() {
        val base = engine.createNewDocument(
            name = "V3 Lyric Test",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val lyricClip = TimelineClipRef(
            clipId = "lyr_clip_1",
            assetId = "cutlab_text_layer",
            clipKind = ClipKind.TEXT,
            trackId = "track_t1",
            timelineStartMs = 1_000L,
            sourceStartMs = 0L,
            durationMs = 3_000L,
            textSpec = TextOverlaySpec(
                text = "Shine bright tonight",
                contentType = TextContentType.LYRIC,
                renderMode = TextRenderMode.INVERT_VIDEO,
                invertStrength = 0.85f,
                invertEdgeSoftness = 0.15f,
                invertIncludeBackground = false,
                karaokeMode = KaraokeHighlightMode.PROGRESSIVE,
                highlightColorArgb = 0xFF00E5FF,
                spokenColorArgb = 0xFFFFD54F,
                wordSpacingEm = 0.08f,
                isTimingEstimated = false,
                sourceLanguage = "en",
                sourceAssetId = "audio_1",
                timedWords = listOf(
                    TimedTextWord(id = "w1", text = "Shine", startMs = 0L, endMs = 800L, confidence = 0.95f),
                    TimedTextWord(id = "w2", text = "bright", startMs = 800L, endMs = 1_700L, confidence = 0.92f),
                    TimedTextWord(id = "w3", text = "tonight", startMs = 1_700L, endMs = 2_900L, confidence = 0.98f),
                ),
            ),
        )
        val populated = base.copy(
            tracks = base.tracks.map { track ->
                if (track.type == TrackType.TEXT) track.copy(clips = listOf(lyricClip)) else track
            },
        )

        val serialized = engine.serialize(populated)
        val decoded = engine.deserialize(serialized)
        assertEquals(3, decoded.schemaVersion)
        val roundTrippedSpec = decoded.tracks.first { it.type == TrackType.TEXT }.clips.single().textSpec!!
        assertEquals(TextContentType.LYRIC, roundTrippedSpec.contentType)
        assertEquals(TextRenderMode.INVERT_VIDEO, roundTrippedSpec.renderMode)
        assertEquals(0.85f, roundTrippedSpec.invertStrength, 0.001f)
        assertEquals(0.15f, roundTrippedSpec.invertEdgeSoftness, 0.001f)
        assertEquals(KaraokeHighlightMode.PROGRESSIVE, roundTrippedSpec.karaokeMode)
        assertEquals(0xFF00E5FF, roundTrippedSpec.highlightColorArgb)
        assertEquals(3, roundTrippedSpec.timedWords.size)
        assertEquals("bright", roundTrippedSpec.timedWords[1].text)
        assertEquals(800L, roundTrippedSpec.timedWords[1].startMs)
        assertEquals(1_700L, roundTrippedSpec.timedWords[1].endMs)
    }
}
