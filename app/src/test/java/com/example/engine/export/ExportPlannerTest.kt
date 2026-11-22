package com.example.engine.export

import com.example.engine.effects.EffectsEngine
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ChromaKeySpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipAudioEffect
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.ClipTransition
import com.example.engine.project.EditorMode
import com.example.engine.project.ExportSettingsSpec
import com.example.engine.project.FilterPreset
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.LutPreset
import com.example.engine.project.MaskShape
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.MotionPreset
import com.example.engine.project.ProjectDocument
import com.example.engine.project.ProjectEngine
import com.example.engine.project.ShaderEffectType
import com.example.engine.project.SpeedRampPreset
import com.example.engine.project.StabilizationSpec
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import com.example.engine.timeline.SpeedRampEngine
import com.example.engine.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPlannerTest {

    private val projectEngine = ProjectEngine()
    private val timelineEngine = TimelineEngine()
    private val planner = ExportPlanner(timelineEngine)

    private fun sampleAsset(
        id: String = "v1",
        type: MediaType = MediaType.VIDEO,
        status: MediaStatus = MediaStatus.READY,
        durationMs: Long = 4_000L,
    ) = MediaAsset(
        id = id,
        projectId = "p1",
        type = type,
        displayName = "$id.mp4",
        sourceUri = "content://media/$id",
        managedFilePath = "/tmp/$id.mp4",
        mimeType = if (type == MediaType.IMAGE) "image/png" else "video/mp4",
        durationMs = durationMs,
        width = 1080,
        height = 1920,
        fps = 30f,
        bitrate = 5_000_000L,
        fileSizeBytes = 2_000_000L,
        status = status,
        importedAt = 100L,
    )

    private fun createBaseProject(mode: EditorMode = EditorMode.ADVANCED): Pair<ProjectDocument, Map<String, MediaAsset>> {
        val doc = projectEngine.createNewDocument(
            name = "Export Test",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = mode,
        )
        val asset = sampleAsset("v1")
        val (withClip, _) = timelineEngine.insertAssetAsClip(doc, asset)
        return withClip to mapOf(asset.id to asset)
    }

    @Test
    fun `computeOutputDimensions preserves aspect ratio and produces even encoder dimensions`() {
        val (w916, h916) = planner.computeOutputDimensions(1080, 1920, 720)
        assertEquals(720, w916)
        assertEquals(1280, h916)
        assertEquals(0, w916 % 2)
        assertEquals(0, h916 % 2)

        val (w169, h169) = planner.computeOutputDimensions(1920, 1080, 2160)
        assertEquals(3840, w169)
        assertEquals(2160, h169)

        val (w45, h45) = planner.computeOutputDimensions(1080, 1350, 1440)
        assertEquals(1440, w45)
        assertEquals(1800, h45)
    }

    @Test
    fun `clean sequential video clip routes to Path A Media3 Transformer under Auto`() {
        val (doc, assets) = createBaseProject()
        val plan = planner.buildPlan(
            document = doc,
            assetsById = assets,
            settings = ExportSettingsSpec(resolutionHeight = 1080, fps = 30, codec = "H.264", audioBitrateKbps = 192),
            preferredPath = ExportRenderPath.AUTO,
            capabilities = HardwareExportCapabilities(supportsAvc = true, supportsHevc = true, maxSupportedShortSidePx = 2160),
        )

        assertTrue(plan.isExportable)
        assertEquals(ExportRenderPath.MEDIA3_TRANSFORMER, plan.selectedPath)
        assertEquals(1080, plan.outputWidth)
        assertEquals(1920, plan.outputHeight)
        assertTrue(plan.hasAudio)
        assertTrue(plan.estimatedFileSizeMb > 0f)
    }

    @Test
    fun `advanced edits force Path B Custom GL even when user prefers Path A`() {
        val (baseDoc, assets) = createBaseProject()
        val clipId = baseDoc.tracks.first { it.type == TrackType.VIDEO }.clips.first().clipId

        val cases = listOf(
            "Transition" to timelineEngine.updateClipTransition(
                baseDoc,
                clipId,
                ClipTransition(TransitionType.CROSS_DISSOLVE, 500L),
            ),
            "Text Overlay" to timelineEngine.insertTextClip(
                baseDoc,
                EffectsEngine.defaultTextSpec(TextStylePreset.BOLD_TITLE),
            ).first,
            "Blend Mode" to timelineEngine.updateClipBlendMode(baseDoc, clipId, BlendModePreset.ADD),
            "Mask" to timelineEngine.updateClipMask(baseDoc, clipId, LayerMask(shape = MaskShape.ELLIPSE)),
            "GLSL Shader" to timelineEngine.updateClipShaderEffect(
                baseDoc,
                clipId,
                ClipShaderEffect(ShaderEffectType.CHROMATIC_ABERRATION, 0.7f),
            ),
            "Zoom Blur Shader" to timelineEngine.updateClipShaderEffect(
                baseDoc,
                clipId,
                ClipShaderEffect(ShaderEffectType.ZOOM_BLUR, 0.6f),
            ),
            "Transform" to timelineEngine.updateClipTransform(baseDoc, clipId, LayerTransform(rotationDeg = 22f)),
            "Keyframes" to timelineEngine.applyMotionPresetToClip(baseDoc, clipId, MotionPreset.BOUNCE),
            "Color Filter" to timelineEngine.updateClipAdjustments(
                baseDoc,
                clipId,
                ClipAdjustments(filterPreset = FilterPreset.CINEMATIC),
            ),
            "Adjustment Layer" to timelineEngine.insertAdjustmentClip(baseDoc).first,
            "Audio FX" to timelineEngine.updateClipAudioEffect(
                baseDoc,
                clipId,
                ClipAudioEffect(pan = -0.5f, bassDb = 4f),
            ),
            "Reverse Playback" to timelineEngine.updateClipReversed(baseDoc, clipId, isReversed = true),
            "Speed Ramp" to timelineEngine.updateClipSpeedRamp(
                baseDoc,
                clipId,
                SpeedRampPreset.MONTAGE,
                SpeedRampEngine.presetPoints(SpeedRampPreset.MONTAGE),
            ),
            "Chroma Key" to timelineEngine.updateClipChromaKey(
                baseDoc,
                clipId,
                ChromaKeySpec(enabled = true),
            ),
            "3D LUT" to timelineEngine.updateClipLut(
                baseDoc,
                clipId,
                ClipLutSpec(preset = LutPreset.TEAL_ORANGE, intensity = 0.9f),
            ),
            "Stabilization" to timelineEngine.updateClipStabilization(
                baseDoc,
                clipId,
                StabilizationSpec(enabled = true, strength = 0.75f, cropScale = 1.12f),
                emptyList(),
            ),
        )

        for ((label, mutatedDoc) in cases) {
            val plan = planner.buildPlan(
                document = mutatedDoc,
                assetsById = assets,
                settings = ExportSettingsSpec(),
                preferredPath = ExportRenderPath.MEDIA3_TRANSFORMER,
            )
            assertTrue("$label should stay exportable", plan.isExportable)
            assertEquals("$label must route to Path B so edits are never omitted", ExportRenderPath.CUSTOM_GL_CODEC, plan.selectedPath)
        }
    }

    @Test
    fun `hardware capability clamps 4K and H265 when device encoders lack support`() {
        val (doc, assets) = createBaseProject()
        val limitedCaps = HardwareExportCapabilities(
            supportsAvc = true,
            supportsHevc = false,
            maxSupportedShortSidePx = 1080,
        )
        val plan = planner.buildPlan(
            document = doc,
            assetsById = assets,
            settings = ExportSettingsSpec(resolutionHeight = 2160, fps = 60, codec = "H.265", audioBitrateKbps = 320),
            preferredPath = ExportRenderPath.AUTO,
            capabilities = limitedCaps,
        )

        assertTrue(plan.isExportable)
        assertEquals(ExportVideoCodec.H264, plan.codec)
        assertEquals(1080, plan.outputWidth)
        assertEquals(1920, plan.outputHeight)
        assertEquals(60, plan.fps)
        assertEquals(320_000, plan.audioBitrateBps)
        assertTrue(plan.compatibilityNotes.any { it.contains("H.265") })
        assertTrue(plan.compatibilityNotes.any { it.contains("1080p") })
    }

    @Test
    fun `empty timeline or missing asset returns non-exportable plan with clear error`() {
        val emptyDoc = projectEngine.createNewDocument(
            name = "Empty",
            aspectRatio = AspectRatioPreset.SQUARE_1_1,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val emptyPlan = planner.buildPlan(emptyDoc, emptyMap(), ExportSettingsSpec())
        assertFalse(emptyPlan.isExportable)
        assertNotNull(emptyPlan.blockingError)

        val (docWithClip, _) = createBaseProject()
        val missingAsset = sampleAsset("v1", status = MediaStatus.MISSING)
        val missingPlan = planner.buildPlan(
            document = docWithClip,
            assetsById = mapOf(missingAsset.id to missingAsset),
            settings = ExportSettingsSpec(),
        )
        assertFalse(missingPlan.isExportable)
        assertTrue(missingPlan.blockingError!!.contains("missing", ignoreCase = true))
    }

    @Test
    fun `micro gaps and non-unity clip volume route to Path B Custom GL`() {
        val (baseDoc, assets) = createBaseProject()
        val vTrack = baseDoc.tracks.first { it.type == TrackType.VIDEO }
        val clip = vTrack.clips.first()

        // 20ms leading gap must route to Path B so Media3 sequential concat does not drop timing
        val leadingGapDoc = timelineEngine.moveClip(baseDoc, clip.clipId, desiredStartMs = 20L, snappingEnabled = false).document
        val leadingGapPlan = planner.buildPlan(leadingGapDoc, assets, ExportSettingsSpec(), preferredPath = ExportRenderPath.AUTO)
        assertEquals(ExportRenderPath.CUSTOM_GL_CODEC, leadingGapPlan.selectedPath)

        // Volume 0.5f must route to Path B so volume scaling is honored
        val scaledVolDoc = timelineEngine.updateClipAudio(baseDoc, clip.clipId, volume = 0.5f, isMuted = false, fadeInMs = 0L, fadeOutMs = 0L)
        val scaledVolPlan = planner.buildPlan(scaledVolDoc, assets, ExportSettingsSpec(), preferredPath = ExportRenderPath.AUTO)
        assertEquals(ExportRenderPath.CUSTOM_GL_CODEC, scaledVolPlan.selectedPath)
    }
}
