package com.example.engine.compositor

import com.example.engine.effects.EffectsEngine
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.EditorMode
import com.example.engine.project.FilterPreset
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MaskShape
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectEngine
import com.example.engine.project.ShaderEffectType
import com.example.engine.project.TrackType
import com.example.engine.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositorEngineTest {

    private val projectEngine = ProjectEngine()
    private val timelineEngine = TimelineEngine()
    private val compositorEngine = CompositorEngine()

    private fun sampleAsset(id: String, type: MediaType): MediaAsset = MediaAsset(
        id = id,
        projectId = "proj-comp",
        type = type,
        displayName = "$id.${if (type == MediaType.IMAGE) "png" else "mp4"}",
        sourceUri = "content://media/$id",
        managedFilePath = "/tmp/$id",
        mimeType = if (type == MediaType.IMAGE) "image/png" else "video/mp4",
        durationMs = 4_000L,
        width = 1080,
        height = 1920,
        fps = 30f,
        bitrate = 2_000_000L,
        fileSizeBytes = 1_000_000L,
        status = MediaStatus.READY,
        importedAt = 1_000L,
    )

    @Test
    fun `resolveFrame orders layers bottom-to-top and cascades higher-order adjustment layers only`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Compositor Cascade",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val vAsset = sampleAsset("base-v", MediaType.VIDEO)
        val imgAsset = sampleAsset("ov-img", MediaType.IMAGE)
        val assetsById = mapOf(vAsset.id to vAsset, imgAsset.id to imgAsset)

        val videoTrackId = doc0.tracks.first { it.type == TrackType.VIDEO }.id
        val overlayTrackId = doc0.tracks.first { it.type == TrackType.OVERLAY }.id

        val (doc1, vClip) = timelineEngine.insertAssetAsClip(doc0, vAsset, targetTrackId = videoTrackId)
        val (doc2, ovClip) = timelineEngine.insertAssetAsClip(doc1, imgAsset, targetTrackId = overlayTrackId, atTimelineMs = 0L)

        // Add Adjustment Track (gets highest orderIndex -> affects both V1 and OV1)
        val (doc3, adjClip) = timelineEngine.insertAdjustmentClip(
            document = doc2,
            adjustments = ClipAdjustments(
                filterPreset = FilterPreset.CINEMATIC,
                brightness = 0.10f,
                tint = 0.25f,
            ),
            shaderEffect = ClipShaderEffect(ShaderEffectType.CHROMATIC_ABERRATION, intensity = 0.7f),
            atTimelineMs = 0L,
            durationMs = 3_000L,
        )
        // Add transform & blend mode to overlay layer
        val doc4 = timelineEngine.updateClipTransform(
            doc3,
            ovClip!!.clipId,
            LayerTransform(positionX = 0.1f, scaleX = 0.5f, scaleY = 0.5f, rotationDeg = 15f, opacity = 0.8f),
        )
        val doc5 = timelineEngine.updateClipBlendMode(doc4, ovClip.clipId, BlendModePreset.SCREEN)
        val doc6 = timelineEngine.updateClipMask(
            doc5,
            vClip!!.clipId,
            LayerMask(shape = MaskShape.ELLIPSE, feather = 0.1f),
        )

        val activeFrame = timelineEngine.evaluateFrame(doc6, 1_000L)
        val resolved = compositorEngine.resolveFrame(
            activeFrame = activeFrame,
            assetsById = assetsById,
            canvasWidth = doc6.canvasWidth,
            canvasHeight = doc6.canvasHeight,
        )

        assertTrue(resolved.hasActiveAdjustmentLayer)
        assertEquals(2, resolved.layers.size)

        // Base video layer comes first (zOrder = 0) and receives adjustment layer grading + shader
        val baseLayer = resolved.layers[0]
        assertEquals(0, baseLayer.zOrder)
        assertEquals(vClip.clipId, baseLayer.sourceLayer.clip.clipId)
        assertEquals(MaskShape.ELLIPSE, baseLayer.mask.shape)
        assertEquals(FilterPreset.CINEMATIC, baseLayer.effectiveAdjustments.filterPreset)
        assertEquals(ShaderEffectType.CHROMATIC_ABERRATION, baseLayer.effectiveShader.type)

        // Overlay image layer sits above (zOrder = 1) with SCREEN blend + transform + adjustment grade
        val topLayer = resolved.layers[1]
        assertEquals(1, topLayer.zOrder)
        assertEquals(BlendModePreset.SCREEN, topLayer.blendMode)
        assertEquals(0.8f, topLayer.effectiveOpacity, 0.01f)
        assertEquals(15f, topLayer.transform.rotationDeg, 0.01f)
        assertEquals(FilterPreset.CINEMATIC, topLayer.effectiveAdjustments.filterPreset)

        // Move Adjustment Track down so it sits BELOW Overlay track -> only V1 gets adjustment, OV1 does not
        val adjTrackId = adjClip!!.trackId
        val docReordered = timelineEngine.reorderTrack(
            timelineEngine.reorderTrack(
                timelineEngine.reorderTrack(doc6, adjTrackId, -1),
                adjTrackId,
                -1,
            ),
            adjTrackId,
            -1,
        )
        val frameAfterReorder = compositorEngine.resolveFrame(
            timelineEngine.evaluateFrame(docReordered, 1_000L),
            assetsById,
            docReordered.canvasWidth,
            docReordered.canvasHeight,
        )
        val newBase = frameAfterReorder.layers.first { it.sourceLayer.clip.clipId == vClip.clipId }
        val newTop = frameAfterReorder.layers.first { it.sourceLayer.clip.clipId == ovClip.clipId }
        assertEquals(ShaderEffectType.CHROMATIC_ABERRATION, newBase.effectiveShader.type)
        assertEquals(ShaderEffectType.NONE, newTop.effectiveShader.type)
    }

    @Test
    fun `evaluateMaskAlpha supports rectangle, ellipse, linear split, feather, and inversion`() {
        val rectMask = LayerMask(
            shape = MaskShape.RECTANGLE,
            centerX = 0.5f,
            centerY = 0.5f,
            width = 0.5f,
            height = 0.5f,
            feather = 0.02f,
            inverted = false,
        )
        assertTrue(compositorEngine.evaluateMaskAlpha(rectMask, 0.5f, 0.5f) > 0.95f)
        assertTrue(compositorEngine.evaluateMaskAlpha(rectMask, 0.05f, 0.05f) < 0.05f)

        val invertedRect = rectMask.copy(inverted = true)
        assertTrue(compositorEngine.evaluateMaskAlpha(invertedRect, 0.5f, 0.5f) < 0.05f)
        assertTrue(compositorEngine.evaluateMaskAlpha(invertedRect, 0.05f, 0.05f) > 0.95f)

        val ellipseMask = LayerMask(
            shape = MaskShape.ELLIPSE,
            centerX = 0.5f,
            centerY = 0.5f,
            width = 0.5f,
            height = 0.5f,
            feather = 0.02f,
        )
        assertTrue(compositorEngine.evaluateMaskAlpha(ellipseMask, 0.5f, 0.5f) > 0.95f)
        // Corner of bounding box (0.26, 0.26) is outside inscribed ellipse
        assertTrue(compositorEngine.evaluateMaskAlpha(ellipseMask, 0.26f, 0.26f) < 0.2f)

        val splitMask = LayerMask(
            shape = MaskShape.LINEAR_SPLIT,
            centerX = 0.5f,
            centerY = 0.5f,
            feather = 0.02f,
        )
        assertTrue(compositorEngine.evaluateMaskAlpha(splitMask, 0.2f, 0.5f) > 0.95f)
        assertTrue(compositorEngine.evaluateMaskAlpha(splitMask, 0.8f, 0.5f) < 0.05f)

        val roundedRectMask = rectMask.copy(cornerRadius = 0.15f, feather = 0.01f)
        // Corner of unrounded 0.5x0.5 box at (0.26, 0.26) lies outside r=0.15 rounded arc
        assertTrue(compositorEngine.evaluateMaskAlpha(roundedRectMask, 0.5f, 0.5f) > 0.95f)
        assertTrue(compositorEngine.evaluateMaskAlpha(roundedRectMask, 0.26f, 0.26f) < 0.2f)
    }

    @Test
    fun `mergeAdjustments combines layer and adjustment grades including tint`() {
        val merged = EffectsEngine.mergeAdjustments(
            base = ClipAdjustments(brightness = 0.1f, contrast = 1.1f, saturation = 1.2f, tint = 0.2f),
            adjustmentLayer = ClipAdjustments(
                filterPreset = FilterPreset.WARM_VINTAGE,
                brightness = 0.05f,
                tint = 0.15f,
            ),
        )
        assertEquals(FilterPreset.WARM_VINTAGE, merged.filterPreset)
        assertEquals(0.18f, merged.brightness, 0.01f)
        assertEquals(0.35f, merged.tint, 0.01f)
        assertFalse(merged.isDefault)
        val matrix = EffectsEngine.buildColorMatrixValues(merged)
        assertEquals(20, matrix.size)
    }

    @Test
    fun `phase 6 animated keyframes evaluate consistently across playhead positions in resolveFrame`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Animated Compositor",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val vAsset = sampleAsset("v-anim", MediaType.VIDEO)
        val assetsById = mapOf(vAsset.id to vAsset)
        val (doc1, vClip) = timelineEngine.insertAssetAsClip(doc0, vAsset)
        val clipId = vClip!!.clipId

        var docAnim = timelineEngine.updateClipShaderEffect(
            doc1,
            clipId,
            ClipShaderEffect(ShaderEffectType.GLOW, intensity = 0.0f),
        )
        docAnim = timelineEngine.upsertKeyframe(
            docAnim,
            clipId,
            com.example.engine.project.KeyframeProperty.SCALE_X,
            0L,
            1.0f,
            com.example.engine.project.KeyframeInterpolation.LINEAR,
        ).first
        docAnim = timelineEngine.upsertKeyframe(
            docAnim,
            clipId,
            com.example.engine.project.KeyframeProperty.SCALE_X,
            4_000L,
            2.0f,
            com.example.engine.project.KeyframeInterpolation.LINEAR,
        ).first
        docAnim = timelineEngine.upsertKeyframe(
            docAnim,
            clipId,
            com.example.engine.project.KeyframeProperty.BLUR,
            0L,
            0f,
            com.example.engine.project.KeyframeInterpolation.LINEAR,
        ).first
        docAnim = timelineEngine.upsertKeyframe(
            docAnim,
            clipId,
            com.example.engine.project.KeyframeProperty.BLUR,
            4_000L,
            16f,
            com.example.engine.project.KeyframeInterpolation.LINEAR,
        ).first
        docAnim = timelineEngine.upsertKeyframe(
            docAnim,
            clipId,
            com.example.engine.project.KeyframeProperty.SHADER_INTENSITY,
            0L,
            0.2f,
            com.example.engine.project.KeyframeInterpolation.LINEAR,
        ).first
        docAnim = timelineEngine.upsertKeyframe(
            docAnim,
            clipId,
            com.example.engine.project.KeyframeProperty.SHADER_INTENSITY,
            4_000L,
            1.0f,
            com.example.engine.project.KeyframeInterpolation.LINEAR,
        ).first

        val resolvedAtStart = compositorEngine.resolveFrame(
            timelineEngine.evaluateFrame(docAnim, 0L),
            assetsById,
            docAnim.canvasWidth,
            docAnim.canvasHeight,
        )
        val resolvedAtMid = compositorEngine.resolveFrame(
            timelineEngine.evaluateFrame(docAnim, 2_000L),
            assetsById,
            docAnim.canvasWidth,
            docAnim.canvasHeight,
        )
        assertEquals(1.0f, resolvedAtStart.layers.first().transform.scaleX, 0.01f)
        assertEquals(1.5f, resolvedAtMid.layers.first().transform.scaleX, 0.01f)
        assertEquals(8.0f, resolvedAtMid.layers.first().effectiveAdjustments.blurRadius, 0.05f)
        assertEquals(0.6f, resolvedAtMid.layers.first().effectiveShader.intensity, 0.01f)
    }
}
