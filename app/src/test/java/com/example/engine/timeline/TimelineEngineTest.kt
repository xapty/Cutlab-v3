package com.example.engine.timeline

import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.ClipTransition
import com.example.engine.project.EasingPreset
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
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {
    private val projectEngine = ProjectEngine()
    private val timelineEngine = TimelineEngine()

    private fun sampleAsset(
        id: String,
        type: MediaType,
        durationMs: Long = 3_000L,
    ): MediaAsset = MediaAsset(
        id = id,
        projectId = "proj-1",
        type = type,
        displayName = "$id.mp4",
        sourceUri = "content://media/$id",
        managedFilePath = "/tmp/$id",
        mimeType = if (type == MediaType.AUDIO) "audio/mp3" else "video/mp4",
        durationMs = durationMs,
        width = 1080,
        height = 1920,
        fps = 30f,
        bitrate = 1_000_000L,
        fileSizeBytes = 1_000_000L,
        status = MediaStatus.READY,
        importedAt = 1_000L,
    )

    @Test
    fun `insertAssetAsClip places sequential non-overlapping clips and evaluates playhead`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Phase 2 Test",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 3_000L)
        val a1 = sampleAsset("a1", MediaType.AUDIO, durationMs = 5_000L)

        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, clip2) = timelineEngine.insertAssetAsClip(doc1, v2)
        val (doc3, audioClip) = timelineEngine.insertAssetAsClip(doc2, a1)

        assertNotNull(clip1)
        assertNotNull(clip2)
        assertNotNull(audioClip)
        assertEquals(0L, clip1?.timelineStartMs)
        assertEquals(2_000L, clip2?.timelineStartMs)
        assertEquals(5_000L, timelineEngine.calculateTimelineDurationMs(doc3))

        val frameAt2500 = timelineEngine.evaluateFrame(doc3, 2_500L)
        assertEquals("v2", frameAt2500.primaryVisualLayer?.clip?.assetId)
        assertEquals(500L, frameAt2500.primaryVisualLayer?.clipRelativeMs)
        assertEquals("a1", frameAt2500.audioLayers.firstOrNull()?.clip?.assetId)
    }

    @Test
    fun `muted track is skipped during frame evaluation and locked track blocks edits`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Lock & Mute",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 60,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 4_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val videoTrackId = doc1.tracks.first { it.type == TrackType.VIDEO }.id

        val mutedDoc = timelineEngine.toggleTrackMute(doc1, videoTrackId)
        val frameMuted = timelineEngine.evaluateFrame(mutedDoc, 1_000L)
        assertTrue(frameMuted.visualLayers.isEmpty())

        val lockedDoc = timelineEngine.toggleTrackLock(mutedDoc, videoTrackId)
        val (afterInsertAttempt, failedClip) = timelineEngine.insertAssetAsClip(
            lockedDoc,
            sampleAsset("v2", MediaType.VIDEO),
            targetTrackId = videoTrackId,
        )
        assertNull(failedClip)

        val afterRemoveAttempt = timelineEngine.removeClip(afterInsertAttempt, clip1!!.clipId)
        assertEquals(1, afterRemoveAttempt.tracks.first { it.id == videoTrackId }.clips.size)
    }

    @Test
    fun `addTrack appends track with incremented name`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Multi-Track",
            aspectRatio = AspectRatioPreset.SQUARE_1_1,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val (doc1, newTrack) = timelineEngine.addTrack(doc0, TrackType.VIDEO)
        assertEquals("V2 Video", newTrack.name)
        assertEquals(5, doc1.tracks.size)
    }

    @Test
    fun `splitClipAt divides clip accurately with sourceStartMs offset and rejects near edges`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Split Test",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 4_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val clipId = clip1!!.clipId

        // Too close to edge (< MIN_CLIP_DURATION_MS) should fail
        val (_, invalidSplit) = timelineEngine.splitClipAt(doc1, clipId, 50L)
        assertNull(invalidSplit)

        // Valid split at 1500ms
        val (doc2, rightId) = timelineEngine.splitClipAt(doc1, clipId, 1_500L)
        assertNotNull(rightId)
        val clips = doc2.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].timelineStartMs)
        assertEquals(1_500L, clips[0].durationMs)
        assertEquals(0L, clips[0].sourceStartMs)

        assertEquals(1_500L, clips[1].timelineStartMs)
        assertEquals(2_500L, clips[1].durationMs)
        assertEquals(1_500L, clips[1].sourceStartMs)
    }

    @Test
    fun `trimClipLeft and trimClipRight respect source bounds, neighbors, and snapping`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Trim Test",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 3_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 3_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, clip2) = timelineEngine.insertAssetAsClip(doc1, v2)

        // Trim clip2 left edge forward by 500ms
        val leftTrimmed = timelineEngine.trimClipLeft(
            document = doc2,
            clipId = clip2!!.clipId,
            desiredStartMs = 3_500L,
            snappingEnabled = false,
        ).document
        val trimmed2 = leftTrimmed.tracks.flatMap { it.clips }.first { it.clipId == clip2.clipId }
        assertEquals(3_500L, trimmed2.timelineStartMs)
        assertEquals(2_500L, trimmed2.durationMs)
        assertEquals(500L, trimmed2.sourceStartMs)

        // Trim clip1 right edge toward clip2 start with snapping (within 160ms of 3500ms -> snaps to 3500ms, capped at source 3000ms)
        val rightTrimCapped = timelineEngine.trimClipRight(
            document = leftTrimmed,
            clipId = clip1!!.clipId,
            desiredEndMs = 3_450L,
            maxSourceDurationMs = 3_000L,
            snappingEnabled = true,
        )
        val trimmed1 = rightTrimCapped.document.tracks.flatMap { it.clips }.first { it.clipId == clip1.clipId }
        assertEquals(3_000L, trimmed1.durationMs)

        // Shortening clip1 to 2000ms then snapping right edge near playhead 2400ms
        val shortened = timelineEngine.trimClipRight(
            document = leftTrimmed,
            clipId = clip1.clipId,
            desiredEndMs = 2_350L,
            maxSourceDurationMs = 3_000L,
            snappingEnabled = true,
            playheadMs = 2_400L,
        )
        val trimmed1Snapped = shortened.document.tracks.flatMap { it.clips }.first { it.clipId == clip1.clipId }
        assertEquals(2_400L, trimmed1Snapped.durationMs)
        assertEquals(2_400L, shortened.snappedTargetMs)
    }

    @Test
    fun `moveClip handles cross-track compatible move, incompatible rejection, and collision avoidance`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Move Test",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 2_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, clip2) = timelineEngine.insertAssetAsClip(doc1, v2)

        val overlayTrack = doc2.tracks.first { it.type == TrackType.OVERLAY }
        val audioTrack = doc2.tracks.first { it.type == TrackType.AUDIO }

        // Move clip2 onto overlay track at 500ms (with snapping disabled so exact 500ms)
        val movedToOverlay = timelineEngine.moveClip(
            document = doc2,
            clipId = clip2!!.clipId,
            desiredStartMs = 500L,
            targetTrackId = overlayTrack.id,
            assetType = MediaType.VIDEO,
            snappingEnabled = false,
        ).document
        val overlayClips = movedToOverlay.tracks.first { it.id == overlayTrack.id }.clips
        assertEquals(1, overlayClips.size)
        assertEquals(500L, overlayClips.first().timelineStartMs)

        // Attempt to move video clip1 to audio track -> stays on video track
        val rejectedAudioMove = timelineEngine.moveClip(
            document = movedToOverlay,
            clipId = clip1!!.clipId,
            desiredStartMs = 300L,
            targetTrackId = audioTrack.id,
            assetType = MediaType.VIDEO,
            snappingEnabled = false,
        ).document
        assertTrue(rejectedAudioMove.tracks.first { it.id == audioTrack.id }.clips.isEmpty())
    }

    @Test
    fun `duplicateClip, rippleDeleteClip, removeClips, reorderTrack, and CommandHistory work end to end`() {
        val history = TimelineCommandHistory()
        val doc0 = projectEngine.createNewDocument(
            name = "History & Ripple",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        history.record("Insert v1", doc0, doc1)

        val (doc2, dupId) = timelineEngine.duplicateClip(doc1, clip1!!.clipId)
        assertNotNull(dupId)
        history.record("Duplicate", doc1, doc2)

        val (doc3, thirdId) = timelineEngine.duplicateClip(doc2, dupId!!)
        assertNotNull(thirdId)
        history.record("Duplicate 2", doc2, doc3)

        val videoClipsBefore = doc3.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(listOf(0L, 2_000L, 4_000L), videoClipsBefore.map { it.timelineStartMs })

        // Ripple delete middle clip (2000..4000) -> third clip shifts left to 2000ms
        val doc4 = timelineEngine.rippleDeleteClip(doc3, dupId)
        history.record("Ripple Delete", doc3, doc4)
        val videoClipsAfterRipple = doc4.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, videoClipsAfterRipple.size)
        assertEquals(listOf(0L, 2_000L), videoClipsAfterRipple.map { it.timelineStartMs })

        // Undo ripple delete restores 3 clips
        val undoRev = history.undo()
        assertNotNull(undoRev)
        assertEquals(3, undoRev!!.before.tracks.first { it.type == TrackType.VIDEO }.clips.size)
        assertTrue(history.canRedo)

        // Redo restores 2 clips
        val redoRev = history.redo()
        assertNotNull(redoRev)
        assertEquals(2, redoRev!!.after.tracks.first { it.type == TrackType.VIDEO }.clips.size)

        // Reorder track
        val firstTrackId = doc4.tracks.sortedBy { it.orderIndex }.first().id
        val reordered = timelineEngine.reorderTrack(doc4, firstTrackId, +1)
        assertEquals(firstTrackId, reordered.tracks.sortedBy { it.orderIndex }[1].id)

        // Multi-clip removeClips
        val allIds = reordered.tracks.flatMap { it.clips }.map { it.clipId }.toSet()
        val cleared = timelineEngine.removeClips(reordered, allIds)
        assertEquals(0L, timelineEngine.calculateTimelineDurationMs(cleared))
    }

    @Test
    fun `phase 4 text overlay, speed adjustment, audio fade envelope, and transitions evaluate accurately`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Phase 4 Quick Edit",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 2_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, clip2) = timelineEngine.insertAssetAsClip(doc1, v2)

        // Add transitionIn on clip2 (starts at 2000ms)
        val doc3 = timelineEngine.updateClipTransition(
            doc2,
            clip2!!.clipId,
            ClipTransition(type = TransitionType.CROSS_DISSOLVE, durationMs = 600L, easing = EasingPreset.LINEAR),
        )
        val frameMidTransition = timelineEngine.evaluateFrame(doc3, 2_300L)
        assertNotNull(frameMidTransition.outgoingVisualLayer)
        assertEquals(clip1!!.clipId, frameMidTransition.outgoingVisualLayer?.clip?.clipId)
        assertEquals(0.5f, frameMidTransition.primaryVisualLayer!!.transitionProgress, 0.01f)

        // Speed up clip1 to 2.0x -> duration becomes 1000ms; then slow down to 0.5x -> shifts clip2 cleanly without overlap
        val docFast = timelineEngine.updateClipSpeed(doc3, clip1.clipId, 2.0f)
        val fast1 = docFast.tracks.flatMap { it.clips }.first { it.clipId == clip1.clipId }
        assertEquals(1_000L, fast1.durationMs)
        assertEquals(2.0f, fast1.speed, 0.01f)

        val docSlow = timelineEngine.updateClipSpeed(docFast, clip1.clipId, 0.5f)
        val videoClipsAfterSlow = docSlow.tracks.first { it.type == TrackType.VIDEO }.clips.sortedBy { it.timelineStartMs }
        assertEquals(4_000L, videoClipsAfterSlow[0].durationMs)
        assertEquals(4_000L, videoClipsAfterSlow[1].timelineStartMs)

        // Audio fade-in & fade-out evaluation
        val a1 = sampleAsset("a1", MediaType.AUDIO, durationMs = 4_000L)
        val (docWithAudio, audioClip) = timelineEngine.insertAssetAsClip(docSlow, a1)
        val docFaded = timelineEngine.updateClipAudio(
            document = docWithAudio,
            clipId = audioClip!!.clipId,
            volume = 1.0f,
            isMuted = false,
            fadeInMs = 1_000L,
            fadeOutMs = 1_000L,
        )
        assertEquals(0.5f, timelineEngine.evaluateFrame(docFaded, 500L).audioLayers.first().effectiveVolume, 0.02f)
        assertEquals(1.0f, timelineEngine.evaluateFrame(docFaded, 2_000L).audioLayers.first().effectiveVolume, 0.02f)
        assertEquals(0.5f, timelineEngine.evaluateFrame(docFaded, 3_500L).audioLayers.first().effectiveVolume, 0.02f)

        // Text clip evaluates simultaneously with underlying video layer
        val (docWithText, textClip) = timelineEngine.insertTextClip(
            document = docFaded,
            spec = TextOverlaySpec(text = "DROP", animationPreset = TextAnimationPreset.POP_OVERSHOOT),
            atTimelineMs = 500L,
            durationMs = 1_500L,
        )
        assertNotNull(textClip)
        val docFiltered = timelineEngine.updateClipAdjustments(
            docWithText,
            clip1.clipId,
            ClipAdjustments(filterPreset = FilterPreset.CINEMATIC, contrast = 1.2f),
        )
        val frameAt1000 = timelineEngine.evaluateFrame(docFiltered, 1_000L)
        assertEquals(1, frameAt1000.visualLayers.size)
        assertEquals(1, frameAt1000.textLayers.size)
        assertEquals("DROP", frameAt1000.textLayers.first().clip.textSpec?.text)
        assertEquals(FilterPreset.CINEMATIC, frameAt1000.primaryVisualLayer?.clip?.adjustments?.filterPreset)
    }

    @Test
    fun `phase 5 insertAdjustmentClip and compositor clip properties update and evaluate cleanly`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Phase 5 Compositor",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 4_000L)
        val (doc1, vClip) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, adjClip) = timelineEngine.insertAdjustmentClip(
            document = doc1,
            adjustments = ClipAdjustments(filterPreset = FilterPreset.CYBER_NEON),
            shaderEffect = ClipShaderEffect(ShaderEffectType.GLOW, intensity = 0.65f),
            atTimelineMs = 500L,
            durationMs = 2_000L,
        )
        assertNotNull(adjClip)
        assertEquals(ClipKind.ADJUSTMENT, adjClip!!.clipKind)
        assertEquals(500L, adjClip.timelineStartMs)

        // Transform, BlendMode, Mask, and Shader updates on video clip
        val doc3 = timelineEngine.updateClipTransform(
            doc2,
            vClip!!.clipId,
            LayerTransform(positionX = -0.2f, scaleX = 1.5f, scaleY = 1.5f, rotationDeg = 45f, opacity = 0.75f),
        )
        val doc4 = timelineEngine.updateClipBlendMode(doc3, vClip.clipId, BlendModePreset.MULTIPLY)
        val doc5 = timelineEngine.updateClipMask(
            doc4,
            vClip.clipId,
            LayerMask(shape = MaskShape.RECTANGLE, feather = 0.12f, inverted = true),
        )
        val doc6 = timelineEngine.updateClipShaderEffect(
            doc5,
            vClip.clipId,
            ClipShaderEffect(ShaderEffectType.FILM_GRAIN, intensity = 0.4f),
        )

        // Adjustment clip can be trimmed like any other non-source-bounded clip
        val trimmedAdjDoc = timelineEngine.trimClipRight(
            document = doc6,
            clipId = adjClip.clipId,
            desiredEndMs = 3_500L,
            maxSourceDurationMs = 0L,
            snappingEnabled = false,
        ).document

        val frameActiveAdj = timelineEngine.evaluateFrame(trimmedAdjDoc, 1_500L)
        assertEquals(1, frameActiveAdj.adjustmentLayers.size)
        assertEquals(3_000L, frameActiveAdj.adjustmentLayers.first().clip.durationMs)
        assertEquals(ShaderEffectType.GLOW, frameActiveAdj.adjustmentLayers.first().clip.shaderEffect.type)

        val evaluatedVideo = frameActiveAdj.primaryVisualLayer!!.clip
        assertEquals(BlendModePreset.MULTIPLY, evaluatedVideo.blendMode)
        assertEquals(45f, evaluatedVideo.transform.rotationDeg, 0.01f)
        assertEquals(MaskShape.RECTANGLE, evaluatedVideo.mask.shape)
        assertTrue(evaluatedVideo.mask.inverted)
        assertEquals(ShaderEffectType.FILM_GRAIN, evaluatedVideo.shaderEffect.type)

        // After adjustment clip ends (3600ms), adjustmentLayers is empty
        val frameAfterAdj = timelineEngine.evaluateFrame(trimmedAdjDoc, 3_600L)
        assertTrue(frameAfterAdj.adjustmentLayers.isEmpty())
    }

    @Test
    fun `phase 6 keyframe crud, motion presets, volume keyframes, split slicing, and speed rescaling work`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Phase 6 Keyframes",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 4_000L)
        val (doc1, vClip) = timelineEngine.insertAssetAsClip(doc0, v1)
        val clipId = vClip!!.clipId

        // Apply Overshoot preset -> populates SCALE_X and SCALE_Y tracks
        val docPreset = timelineEngine.applyMotionPresetToClip(
            doc1,
            clipId,
            com.example.engine.project.MotionPreset.OVERSHOOT,
        )
        val presetClip = docPreset.tracks.flatMap { it.clips }.first { it.clipId == clipId }
        assertTrue(presetClip.keyframeTracks.any { it.property == com.example.engine.project.KeyframeProperty.SCALE_X })

        // Add volume keyframes (0ms -> 0.0f, 4000ms -> 1.0f) and verify evaluateFrame volume
        var docVol = timelineEngine.upsertKeyframe(
            docPreset,
            clipId,
            com.example.engine.project.KeyframeProperty.VOLUME,
            0L,
            0.0f,
            com.example.engine.project.KeyframeInterpolation.LINEAR,
        ).first
        docVol = timelineEngine.upsertKeyframe(
            docVol,
            clipId,
            com.example.engine.project.KeyframeProperty.VOLUME,
            4_000L,
            1.0f,
            com.example.engine.project.KeyframeInterpolation.LINEAR,
        ).first
        assertEquals(0.5f, timelineEngine.evaluateFrame(docVol, 2_000L).visualLayers.first().effectiveVolume, 0.02f)

        // Update all keyframes in VOLUME track via multi-selection interpolation
        val volIds = docVol.tracks.flatMap { it.clips }.first { it.clipId == clipId }
            .keyframeTracks.first { it.property == com.example.engine.project.KeyframeProperty.VOLUME }
            .keyframes.map { it.id }.toSet()
        val docBezier = timelineEngine.updateMultipleKeyframesInterpolation(
            docVol,
            clipId,
            com.example.engine.project.KeyframeProperty.VOLUME,
            volIds,
            com.example.engine.project.KeyframeInterpolation.CUBIC_BEZIER,
            com.example.engine.project.BezierCurveSpec(0.16f, 1.0f, 0.3f, 1.0f),
        )

        // Split clip at 2000ms -> both halves get valid sliced/interpolated VOLUME tracks
        val (docSplit, rightId) = timelineEngine.splitClipAt(docBezier, clipId, 2_000L)
        assertNotNull(rightId)
        val leftClip = docSplit.tracks.flatMap { it.clips }.first { it.clipId == clipId }
        val rightClip = docSplit.tracks.flatMap { it.clips }.first { it.clipId == rightId }
        assertEquals(2_000L, leftClip.keyframeTracks.first { it.property == com.example.engine.project.KeyframeProperty.VOLUME }.keyframes.last().timeMs)
        assertEquals(0L, rightClip.keyframeTracks.first { it.property == com.example.engine.project.KeyframeProperty.VOLUME }.keyframes.first().timeMs)

        // Speed 2.0x on right clip halves its duration to 1000ms and rescales keyframes proportionally
        val docSped = timelineEngine.updateClipSpeed(docSplit, rightId!!, 2.0f)
        val spedRight = docSped.tracks.flatMap { it.clips }.first { it.clipId == rightId }
        assertEquals(1_000L, spedRight.durationMs)
        val spedVolTrack = spedRight.keyframeTracks.first { it.property == com.example.engine.project.KeyframeProperty.VOLUME }
        assertEquals(1_000L, spedVolTrack.keyframes.last().timeMs)

        // Clear track removes it cleanly
        val docCleared = timelineEngine.clearKeyframeTrack(
            docSped,
            rightId,
            com.example.engine.project.KeyframeProperty.VOLUME,
        )
        val clearedRight = docCleared.tracks.flatMap { it.clips }.first { it.clipId == rightId }
        assertTrue(clearedRight.keyframeTracks.none { it.property == com.example.engine.project.KeyframeProperty.VOLUME })
    }

    @Test
    fun `phase 7 audio effects clamping, beat marker management, and magnetic marker snapping work`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Phase 7 Audio",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val a1 = sampleAsset("bgm-1", MediaType.AUDIO, durationMs = 5_000L)
        val (doc1, aClip) = timelineEngine.insertAssetAsClip(doc0, a1, atTimelineMs = 0L)
        val clipId = aClip!!.clipId

        // Update Audio FX + out-of-range clamping
        val docFx = timelineEngine.updateClipAudioEffect(
            doc1,
            clipId,
            com.example.engine.project.ClipAudioEffect(
                preset = com.example.engine.project.AudioEffectPreset.VOCAL_CLEAR,
                pan = -2.5f,
                bassDb = 18.0f,
                trebleDb = -20.0f,
                echoMix = 1.5f,
            ),
        )
        val updatedFx = docFx.tracks.flatMap { it.clips }.first { it.clipId == clipId }.audioEffect
        assertEquals(com.example.engine.project.AudioEffectPreset.VOCAL_CLEAR, updatedFx.preset)
        assertEquals(-1.0f, updatedFx.pan, 0.001f)
        assertEquals(12.0f, updatedFx.bassDb, 0.001f)
        assertEquals(-12.0f, updatedFx.trebleDb, 0.001f)
        assertEquals(0.75f, updatedFx.echoMix, 0.001f)

        // Add manual beat marker + auto-detected clip beat markers
        val (docMarker, m1) = timelineEngine.addTimelineMarker(docFx, timeMs = 1_200L, label = "Drop")
        val docAutoBeats = timelineEngine.addBeatMarkersFromClip(
            document = docMarker,
            clip = docMarker.tracks.flatMap { it.clips }.first { it.clipId == clipId },
            assetBeatTimestampsMs = listOf(1_220L, 2_500L, 4_000L), // 1220ms dedups against 1200ms
        )
        assertEquals(3, docAutoBeats.markers.size)
        assertEquals(listOf(1_200L, 2_500L, 4_000L), docAutoBeats.markers.map { it.timeMs })

        // Verify snapping targets include timeline markers so moving another clip snaps cleanly to 2_500ms
        val a2 = sampleAsset("sfx-1", MediaType.AUDIO, durationMs = 1_000L)
        val (docExtraTrack, extraTrack) = timelineEngine.addTrack(docAutoBeats, TrackType.AUDIO)
        val (docWithSfx, sfxClip) = timelineEngine.insertAssetAsClip(
            docExtraTrack,
            a2,
            targetTrackId = extraTrack.id,
            atTimelineMs = 0L,
        )
        val moveResult = timelineEngine.moveClip(
            document = docWithSfx,
            clipId = sfxClip!!.clipId,
            desiredStartMs = 2_430L, // Within 160ms snap threshold of beat marker at 2_500ms
            snappingEnabled = true,
        )
        assertEquals(2_500L, moveResult.snappedTargetMs)
        assertEquals(2_500L, moveResult.document.tracks.first { it.id == extraTrack.id }.clips.first().timelineStartMs)

        // Remove single marker and clear remaining markers
        val docRemovedOne = timelineEngine.removeTimelineMarker(docWithSfx, m1.id)
        assertEquals(2, docRemovedOne.markers.size)
        val docCleared = timelineEngine.clearTimelineMarkers(docRemovedOne)
        assertTrue(docCleared.markers.isEmpty())
    }

    @Test
    fun `reversed clip split and trims keep source intervals consistent`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Reversed Trim",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 4_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val clipId = clip1!!.clipId

        val revDoc = timelineEngine.updateClipReversed(doc1, clipId, isReversed = true)
        val trimmedLeft = timelineEngine.trimClipLeft(
            document = revDoc,
            clipId = clipId,
            desiredStartMs = 1_000L,
            snappingEnabled = false,
        ).document
        val leftClip = trimmedLeft.tracks.flatMap { it.clips }.first { it.clipId == clipId }
        assertEquals(1_000L, leftClip.timelineStartMs)
        assertEquals(3_000L, leftClip.durationMs)
        // Reversed clip plays backwards from sourceEnd; trimming timeline left shrinks the high source end, preserving sourceStartMs=0
        assertEquals(0L, leftClip.sourceStartMs)

        val trimmedRight = timelineEngine.trimClipRight(
            document = trimmedLeft,
            clipId = clipId,
            desiredEndMs = 3_500L,
            maxSourceDurationMs = 4_000L,
            snappingEnabled = false,
        ).document
        val rightClip = trimmedRight.tracks.flatMap { it.clips }.first { it.clipId == clipId }
        assertEquals(2_500L, rightClip.durationMs)
        // Trimming timeline right advances sourceStartMs by 500ms while preserving reversed source span bounds
        assertEquals(500L, rightClip.sourceStartMs)
    }

    @Test
    fun `speed updates round timeline duration without cumulative truncation`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Speed Rounding",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 1_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val fast = timelineEngine.updateClipSpeed(doc1, clip1!!.clipId, 3.0f)
        val updated = fast.tracks.flatMap { it.clips }.first { it.clipId == clip1.clipId }
        assertEquals(333L, updated.durationMs)
        // Restoring to 1.0x recovers rounded 999ms instead of drifting lower with repeated truncation
        val backTo1x = timelineEngine.updateClipSpeed(fast, clip1.clipId, 1.0f)
        val restored = backTo1x.tracks.flatMap { it.clips }.first { it.clipId == clip1.clipId }
        assertEquals(999L, restored.durationMs)
    }

    @Test
    fun `non-overlapping track invariant is enforced across insert, move, trim, split, duplicate, and legacy normalization`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Overlap Invariant",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val a1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val a2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 2_000L)

        // Insert a1 at 0..2000ms, then attempt inserting a2 at 500ms -> pushed to 2000ms
        val (doc1, c1) = timelineEngine.insertAssetAsClip(doc0, a1, atTimelineMs = 0L)
        val (doc2, c2) = timelineEngine.insertAssetAsClip(doc1, a2, atTimelineMs = 500L)
        assertEquals(2_000L, c2!!.timelineStartMs)

        // Duplicate c1 on occupied track -> placed at 4000ms without overlapping c2
        val (doc3, dupId) = timelineEngine.duplicateClip(doc2, c1!!.clipId)
        val dupClip = doc3.tracks.flatMap { it.clips }.first { it.clipId == dupId }
        assertEquals(4_000L, dupClip.timelineStartMs)

        // Move dupClip onto c2 range (2500ms) -> nearest non-overlapping slot is 4000ms
        val moved = timelineEngine.moveClip(doc3, dupId!!, desiredStartMs = 2_500L, snappingEnabled = false).document
        assertFalse(timelineEngine.hasOverlappingClips(moved))

        // Trim c1 right into c2 -> clamped cleanly at 2000ms
        val trimmedRight = timelineEngine.trimClipRight(
            moved,
            c1.clipId,
            desiredEndMs = 2_800L,
            maxSourceDurationMs = 5_000L,
            snappingEnabled = false,
        ).document
        val trimmedC1 = trimmedRight.tracks.flatMap { it.clips }.first { it.clipId == c1.clipId }
        assertEquals(2_000L, trimmedC1.timelineStartMs + trimmedC1.durationMs)
        assertFalse(timelineEngine.hasOverlappingClips(trimmedRight))

        // Corrupt doc with overlapping clips is detected and repaired deterministically
        val corruptedTrack = trimmedRight.tracks.first { it.type == TrackType.VIDEO }.let { tr ->
            val forcedOverlap = tr.clips.mapIndexed { idx, clip ->
                if (idx == 1) clip.copy(timelineStartMs = 800L) else clip
            }
            tr.copy(clips = forcedOverlap)
        }
        val corruptedDoc = trimmedRight.copy(
            tracks = trimmedRight.tracks.map { if (it.id == corruptedTrack.id) corruptedTrack else it },
        )
        assertTrue(timelineEngine.hasOverlappingClips(corruptedDoc))
        val repaired = timelineEngine.normalizeNonOverlappingTracks(corruptedDoc)
        assertFalse(timelineEngine.hasOverlappingClips(repaired))
        val repairedClips = repaired.tracks.first { it.id == corruptedTrack.id }.clips
        assertEquals(0L, repairedClips[0].timelineStartMs)
        assertEquals(2_000L, repairedClips[1].timelineStartMs)
    }

    @Test
    fun `commandHistory rebaseMetadata and pruneDeletedAsset prevent restoring stale metadata or ghost clips`() {
        val history = TimelineCommandHistory()
        val base = projectEngine.createNewDocument(
            name = "Initial Name",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 2_000L)

        val (afterV1, _) = timelineEngine.insertAssetAsClip(base, v1)
        history.record("Insert v1", base, afterV1)

        val (afterV2, _) = timelineEngine.insertAssetAsClip(afterV1, v2)
        history.record("Insert v2", afterV1, afterV2)

        // Rename + change FPS externally, then rebase history metadata
        val renamedAndRespec = afterV2.copy(
            name = "Final Master",
            fps = 60,
            editorMode = EditorMode.ADVANCED,
        )
        history.rebaseMetadata(renamedAndRespec)

        // Delete v1 externally, prune history
        history.pruneDeletedAsset("v1")
        assertEquals(1, history.undoCount)

        val undone = history.undo()!!
        assertEquals("Final Master", undone.before.name)
        assertEquals(60, undone.before.fps)
        assertEquals(EditorMode.ADVANCED, undone.before.editorMode)
        assertTrue(undone.before.tracks.flatMap { it.clips }.none { it.assetId == "v1" })

        val redone = history.redo()!!
        assertEquals("Final Master", redone.after.name)
        assertEquals(listOf("v2"), redone.after.tracks.flatMap { it.clips }.map { it.assetId })
    }

    @Test
    fun `inspectTrackOverlaps builds detailed repair report without mutating input document`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Legacy Overlap Inspect",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val a1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val a2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 1_500L)
        val (doc1, c1) = timelineEngine.insertAssetAsClip(doc0, a1, atTimelineMs = 0L)
        val (doc2, c2) = timelineEngine.insertAssetAsClip(doc1, a2, atTimelineMs = 2_000L)

        // Simulate legacy overlapping clips on V1 (c2 overlaps c1 across 800..2000ms)
        val overlappingDoc = doc2.copy(
            tracks = doc2.tracks.map { tr ->
                if (tr.type == TrackType.VIDEO) {
                    tr.copy(
                        clips = tr.clips.map { clip ->
                            if (clip.clipId == c2!!.clipId) clip.copy(timelineStartMs = 800L) else clip
                        },
                    )
                } else {
                    tr
                }
            },
        )

        val report = timelineEngine.inspectTrackOverlaps(
            overlappingDoc,
            mode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
        )
        assertTrue(report.hasOverlaps)
        assertTrue(report.timingPreserved)
        assertEquals(1, report.affectedTrackCount)
        assertEquals(1, report.affectedClipCount)

        val trackEntry = report.affectedTracks.single()
        assertEquals(TrackType.VIDEO, trackEntry.trackType)
        val clipEntry = report.affectedClips.single()
        assertEquals(c2!!.clipId, clipEntry.clipId)
        assertEquals(listOf(c1!!.clipId), clipEntry.overlappingWithClipIds)
        assertEquals(1_200L, clipEntry.overlapDurationMs)
        assertEquals(800L, clipEntry.originalStartMs)
        assertEquals(1_500L, clipEntry.originalDurationMs)
        assertEquals(800L, clipEntry.proposedStartMs)
        assertEquals(1_500L, clipEntry.proposedDurationMs)
        assertEquals(OverlapRepairAction.MOVE_TO_OVERFLOW_TRACK, clipEntry.action)
        assertFalse(clipEntry.timingChanged)

        // Input document must remain untouched
        val untouchedClips = overlappingDoc.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(listOf(0L, 800L), untouchedClips.map { it.timelineStartMs })
    }

    @Test
    fun `repairOverlappingTracks supports timing-preserving overflow repair and explicit shift-forward repair`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Repair Modes",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val a1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val a2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 1_500L)
        val (doc1, c1) = timelineEngine.insertAssetAsClip(doc0, a1, atTimelineMs = 0L)
        val (doc2, c2) = timelineEngine.insertAssetAsClip(doc1, a2, atTimelineMs = 2_000L)

        val overlappingDoc = doc2.copy(
            tracks = doc2.tracks.map { tr ->
                if (tr.type == TrackType.VIDEO) {
                    tr.copy(
                        clips = tr.clips.map { clip ->
                            if (clip.clipId == c2!!.clipId) clip.copy(timelineStartMs = 800L) else clip
                        },
                    )
                } else {
                    tr
                }
            },
        )

        // Timing-preserving overflow repair moves c2 onto a compatible track at 800ms
        val overflowOutcome = timelineEngine.repairOverlappingTracks(
            overlappingDoc,
            mode = TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
        )
        assertFalse(timelineEngine.hasOverlappingClips(overflowOutcome.document))
        assertTrue(overflowOutcome.report.timingPreserved)
        val repairedC1 = overflowOutcome.document.tracks.flatMap { it.clips }.first { it.clipId == c1!!.clipId }
        val repairedC2 = overflowOutcome.document.tracks.flatMap { it.clips }.first { it.clipId == c2!!.clipId }
        assertEquals(0L, repairedC1.timelineStartMs)
        assertEquals(800L, repairedC2.timelineStartMs)
        assertEquals(TrackType.OVERLAY, overflowOutcome.document.tracks.first { it.id == repairedC2.trackId }.type)

        // Shift-forward repair keeps c2 on V1 but shifts it to 2000ms
        val shiftOutcome = timelineEngine.repairOverlappingTracks(
            overlappingDoc,
            mode = TimelineOverlapRepairMode.SHIFT_FORWARD_ON_TRACK,
        )
        assertFalse(timelineEngine.hasOverlappingClips(shiftOutcome.document))
        assertFalse(shiftOutcome.report.timingPreserved)
        val shiftEntry = shiftOutcome.report.affectedClips.single()
        assertEquals(OverlapRepairAction.SHIFT_FORWARD, shiftEntry.action)
        assertEquals(800L, shiftEntry.originalStartMs)
        assertEquals(2_000L, shiftEntry.proposedStartMs)
        val v1AfterShift = shiftOutcome.document.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(listOf(0L, 2_000L), v1AfterShift.map { it.timelineStartMs })
    }

    @Test
    fun `evaluateFrame evaluates all overlapping active clips on an unrepaired track without silent drop`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Multi-Active Track Eval",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val a1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val a2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 2_000L)
        val (doc1, c1) = timelineEngine.insertAssetAsClip(doc0, a1, atTimelineMs = 0L)
        val (doc2, c2) = timelineEngine.insertAssetAsClip(doc1, a2, atTimelineMs = 2_000L)

        val unrepairedOverlap = doc2.copy(
            tracks = doc2.tracks.map { tr ->
                if (tr.type == TrackType.VIDEO) {
                    tr.copy(
                        clips = tr.clips.map { clip ->
                            if (clip.clipId == c2!!.clipId) clip.copy(timelineStartMs = 500L) else clip
                        },
                    )
                } else {
                    tr
                }
            },
        )

        val frameAt1000 = timelineEngine.evaluateFrame(unrepairedOverlap, 1_000L)
        assertEquals(listOf(c1!!.clipId, c2!!.clipId), frameAt1000.visualLayers.map { it.clip.clipId })
    }
}
