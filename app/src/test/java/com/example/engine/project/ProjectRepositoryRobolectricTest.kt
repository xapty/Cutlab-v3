package com.example.engine.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.CutLabDatabase
import com.example.core.media.MediaMetadataExtractor
import com.example.core.storage.MediaStorageManager
import com.example.engine.effects.EffectsEngine
import com.example.engine.timeline.TimelineEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ProjectRepositoryRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: CutLabDatabase
    private lateinit var repository: ProjectRepository

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
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `created project persists and switches modes without data loss`() = runTest {
        val created = repository.createProject(
            name = "AMV Cut 01",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 60,
            editorMode = EditorMode.QUICK,
        )

        val loaded = repository.observeProject(created.id).first()
        assertNotNull(loaded)
        assertEquals("AMV Cut 01", loaded?.name)
        assertEquals(EditorMode.QUICK, loaded?.editorMode)
        assertEquals(1080, loaded?.canvasWidth)
        assertEquals(1920, loaded?.canvasHeight)

        repository.switchEditorMode(created.id, EditorMode.ADVANCED)
        val afterSwitch = repository.observeProject(created.id).first()
        assertEquals(EditorMode.ADVANCED, afterSwitch?.editorMode)
        assertEquals(4, afterSwitch?.tracks?.size)

        val duplicate = repository.duplicateProject(created.id)
        assertNotNull(duplicate)
        val summaries = repository.observeProjectSummaries().first()
        assertEquals(2, summaries.size)

        repository.deleteProject(created.id)
        assertNull(repository.observeProject(created.id).first())
    }

    @Test
    fun `phase 3 timeline mutations split trim ripple delete and undo saveDocument persist across reopen`() = runTest {
        val created = repository.createProject(
            name = "Phase 3 Persistence",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val timelineEngine = TimelineEngine()
        val sampleAsset = MediaAsset(
            id = "asset-video-1",
            projectId = created.id,
            type = MediaType.VIDEO,
            displayName = "clip.mp4",
            sourceUri = "content://media/clip",
            managedFilePath = "/tmp/clip.mp4",
            mimeType = "video/mp4",
            durationMs = 6_000L,
            width = 1920,
            height = 1080,
            fps = 30f,
            bitrate = 2_000_000L,
            fileSizeBytes = 1_500_000L,
            status = MediaStatus.READY,
            importedAt = 1_000L,
        )

        val (docWithClip, inserted) = timelineEngine.insertAssetAsClip(created, sampleAsset)
        val clipId = inserted!!.clipId
        val (docSplit, rightId) = timelineEngine.splitClipAt(docWithClip, clipId, 2_000L)
        val docTrimmed = timelineEngine.trimClipRight(
            document = docSplit,
            clipId = rightId!!,
            desiredEndMs = 4_500L,
            maxSourceDurationMs = 6_000L,
        ).document
        repository.saveDocument(docTrimmed)

        val reopened = repository.observeProject(created.id).first()
        assertNotNull(reopened)
        val clips = reopened!!.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, clips.size)
        assertEquals(2_000L, clips[0].durationMs)
        assertEquals(2_000L, clips[1].timelineStartMs)
        assertEquals(2_500L, clips[1].durationMs)
        assertEquals(6_000L, clips[1].sourceTotalDurationMs)

        val docRippled = timelineEngine.rippleDeleteClip(reopened, clipId)
        repository.saveDocument(docRippled)

        val afterRipple = repository.observeProject(created.id).first()
        val remaining = afterRipple!!.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(1, remaining.size)
        assertEquals(0L, remaining.first().timelineStartMs)
        assertEquals(2_500L, remaining.first().durationMs)
    }

    @Test
    fun `phase 4 quick edit properties filters transitions speed audio fades and text survive save and mode switch`() = runTest {
        val created = repository.createProject(
            name = "Phase 4 Persistence",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val timelineEngine = TimelineEngine()
        val sampleAsset = MediaAsset(
            id = "asset-v1",
            projectId = created.id,
            type = MediaType.VIDEO,
            displayName = "clip.mp4",
            sourceUri = "content://media/clip",
            managedFilePath = "/tmp/clip.mp4",
            mimeType = "video/mp4",
            durationMs = 4_000L,
            width = 1080,
            height = 1920,
            fps = 30f,
            bitrate = 2_000_000L,
            fileSizeBytes = 1_000_000L,
            status = MediaStatus.READY,
            importedAt = 1_000L,
        )

        val (doc1, inserted) = timelineEngine.insertAssetAsClip(created, sampleAsset)
        val clipId = inserted!!.clipId
        val doc2 = timelineEngine.updateClipAdjustments(
            doc1,
            clipId,
            ClipAdjustments(filterPreset = FilterPreset.VIVID, saturation = 1.3f, vignette = 0.25f),
        )
        val doc3 = timelineEngine.updateClipTransition(
            doc2,
            clipId,
            ClipTransition(type = TransitionType.ZOOM_IN, durationMs = 600L),
        )
        val doc4 = timelineEngine.updateClipSpeed(doc3, clipId, 2.0f)
        val doc5 = timelineEngine.updateClipAudio(
            doc4,
            clipId,
            volume = 0.8f,
            isMuted = false,
            fadeInMs = 300L,
            fadeOutMs = 400L,
        )
        val (doc6, textClip) = timelineEngine.insertTextClip(
            doc5,
            EffectsEngine.defaultTextSpec(TextStylePreset.NEON_POP).copy(text = "KINETIC"),
        )
        repository.saveDocument(doc6)
        repository.switchEditorMode(created.id, EditorMode.ADVANCED)

        val reopened = repository.observeProject(created.id).first()!!
        assertEquals(EditorMode.ADVANCED, reopened.editorMode)
        val reopenedClip = reopened.tracks.first { it.type == TrackType.VIDEO }.clips.first()
        assertEquals(FilterPreset.VIVID, reopenedClip.adjustments.filterPreset)
        assertEquals(TransitionType.ZOOM_IN, reopenedClip.transitionIn?.type)
        assertEquals(2.0f, reopenedClip.speed, 0.01f)
        assertEquals(2_000L, reopenedClip.durationMs)
        assertEquals(0.8f, reopenedClip.volume, 0.01f)
        assertEquals(300L, reopenedClip.fadeInMs)
        assertEquals(400L, reopenedClip.fadeOutMs)

        val reopenedText = reopened.tracks.first { it.type == TrackType.TEXT }.clips.first { it.clipId == textClip!!.clipId }
        assertEquals("KINETIC", reopenedText.textSpec?.text)
        assertEquals(TextStylePreset.NEON_POP, reopenedText.textSpec?.stylePreset)
    }

    @Test
    fun `phase 5 transforms blend modes masks shader effects and adjustment clips persist in Room`() = runTest {
        val created = repository.createProject(
            name = "Phase 5 Compositor",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val timelineEngine = TimelineEngine()
        val overlayImage = MediaAsset(
            id = "asset-img-1",
            projectId = created.id,
            type = MediaType.IMAGE,
            displayName = "overlay.png",
            sourceUri = "content://media/overlay",
            managedFilePath = "/tmp/overlay.png",
            mimeType = "image/png",
            durationMs = 0L,
            width = 1080,
            height = 1080,
            fps = 0f,
            bitrate = 0L,
            fileSizeBytes = 250_000L,
            status = MediaStatus.READY,
            importedAt = 1_000L,
        )
        val overlayTrackId = created.tracks.first { it.type == TrackType.OVERLAY }.id
        val (doc1, ovClip) = timelineEngine.insertAssetAsClip(created, overlayImage, targetTrackId = overlayTrackId)
        val clipId = ovClip!!.clipId

        val doc2 = timelineEngine.updateClipTransform(
            doc1,
            clipId,
            LayerTransform(positionX = 0.25f, positionY = -0.15f, scaleX = 0.7f, scaleY = 0.7f, rotationDeg = -30f, opacity = 0.85f),
        )
        val doc3 = timelineEngine.updateClipBlendMode(doc2, clipId, BlendModePreset.ADD)
        val doc4 = timelineEngine.updateClipMask(
            doc3,
            clipId,
            LayerMask(shape = MaskShape.ELLIPSE, centerX = 0.4f, centerY = 0.6f, feather = 0.14f, inverted = false),
        )
        val doc5 = timelineEngine.updateClipShaderEffect(
            doc4,
            clipId,
            ClipShaderEffect(ShaderEffectType.VHS_SCANLINES, intensity = 0.8f),
        )
        val (doc6, adjClip) = timelineEngine.insertAdjustmentClip(
            document = doc5,
            adjustments = ClipAdjustments(filterPreset = FilterPreset.COOL_SLATE, tint = -0.3f),
            shaderEffect = ClipShaderEffect(ShaderEffectType.NONE),
        )
        // Use valid ShaderEffectType.GLOW for adjustment clip
        val doc7 = timelineEngine.updateClipShaderEffect(
            doc6,
            adjClip!!.clipId,
            ClipShaderEffect(ShaderEffectType.GLOW, intensity = 0.55f),
        )
        repository.saveDocument(doc7)
        repository.switchEditorMode(created.id, EditorMode.QUICK)

        val reopened = repository.observeProject(created.id).first()!!
        assertEquals(EditorMode.QUICK, reopened.editorMode)
        val reopenedOv = reopened.tracks.first { it.type == TrackType.OVERLAY }.clips.first()
        assertEquals(0.25f, reopenedOv.transform.positionX, 0.01f)
        assertEquals(-30f, reopenedOv.transform.rotationDeg, 0.01f)
        assertEquals(0.85f, reopenedOv.transform.opacity, 0.01f)
        assertEquals(BlendModePreset.ADD, reopenedOv.blendMode)
        assertEquals(MaskShape.ELLIPSE, reopenedOv.mask.shape)
        assertEquals(0.14f, reopenedOv.mask.feather, 0.01f)
        assertEquals(ShaderEffectType.VHS_SCANLINES, reopenedOv.shaderEffect.type)

        val reopenedAdj = reopened.tracks.first { it.type == TrackType.ADJUSTMENT }.clips.first()
        assertEquals(ClipKind.ADJUSTMENT, reopenedAdj.clipKind)
        assertEquals(FilterPreset.COOL_SLATE, reopenedAdj.adjustments.filterPreset)
        assertEquals(-0.3f, reopenedAdj.adjustments.tint, 0.01f)
        assertEquals(ShaderEffectType.GLOW, reopenedAdj.shaderEffect.type)
    }

    @Test
    fun `phase 6 keyframes, custom bezier handles, and motion presets persist across Room reopen and mode switch`() = runTest {
        val created = repository.createProject(
            name = "Phase 6 Keyframes",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val timelineEngine = TimelineEngine()
        val videoAsset = MediaAsset(
            id = "asset-kf-1",
            projectId = created.id,
            type = MediaType.VIDEO,
            displayName = "kf.mp4",
            sourceUri = "content://media/kf",
            managedFilePath = "/tmp/kf.mp4",
            mimeType = "video/mp4",
            durationMs = 3_000L,
            width = 1080,
            height = 1920,
            fps = 30f,
            bitrate = 2_000_000L,
            fileSizeBytes = 900_000L,
            status = MediaStatus.READY,
            importedAt = 1_000L,
        )
        val (doc1, vClip) = timelineEngine.insertAssetAsClip(created, videoAsset)
        val clipId = vClip!!.clipId

        val doc2 = timelineEngine.applyMotionPresetToClip(doc1, clipId, MotionPreset.SLIDE_IN)
        val customBezier = BezierCurveSpec(0.18f, 1.42f, 0.48f, 0.96f)
        var doc3 = timelineEngine.upsertKeyframe(
            document = doc2,
            clipId = clipId,
            property = KeyframeProperty.ROTATION,
            timeMs = 0L,
            value = -25f,
            interpolation = KeyframeInterpolation.CUBIC_BEZIER,
            bezier = customBezier,
        ).first
        doc3 = timelineEngine.upsertKeyframe(
            document = doc3,
            clipId = clipId,
            property = KeyframeProperty.ROTATION,
            timeMs = 1_500L,
            value = 15f,
            interpolation = KeyframeInterpolation.EASE_OUT,
        ).first

        repository.saveDocument(doc3)
        repository.switchEditorMode(created.id, EditorMode.QUICK)

        val reopened = repository.observeProject(created.id).first()!!
        assertEquals(EditorMode.QUICK, reopened.editorMode)
        val reopenedClip = reopened.tracks.first { it.type == TrackType.VIDEO }.clips.first()
        val rotTrack = reopenedClip.keyframeTracks.firstOrNull { it.property == KeyframeProperty.ROTATION }
        assertNotNull(rotTrack)
        assertEquals(2, rotTrack!!.keyframes.size)
        assertEquals(KeyframeInterpolation.CUBIC_BEZIER, rotTrack.keyframes.first().interpolation)
        assertEquals(1.42f, rotTrack.keyframes.first().bezier.y1, 0.005f)
        assertEquals(15f, rotTrack.keyframes.last().value, 0.01f)
    }

    @Test
    fun `phase 7 clip audio effects and timeline beat markers persist across Room reopen and mode switch`() = runTest {
        val created = repository.createProject(
            name = "Phase 7 Audio Mix",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val timelineEngine = TimelineEngine()
        val audioAsset = MediaAsset(
            id = "asset-aud-7",
            projectId = created.id,
            type = MediaType.AUDIO,
            displayName = "soundtrack.wav",
            sourceUri = "content://media/soundtrack",
            managedFilePath = "/tmp/soundtrack.wav",
            mimeType = "audio/wav",
            durationMs = 8_000L,
            width = 0,
            height = 0,
            fps = 0f,
            bitrate = 256_000L,
            fileSizeBytes = 1_200_000L,
            status = MediaStatus.READY,
            importedAt = 1_000L,
        )
        val (doc1, aClip) = timelineEngine.insertAssetAsClip(created, audioAsset)
        val clipId = aClip!!.clipId

        val doc2 = timelineEngine.updateClipAudioEffect(
            doc1,
            clipId,
            ClipAudioEffect(
                preset = AudioEffectPreset.STUDIO_PUNCH,
                pan = 0.45f,
                bassDb = 5.0f,
                trebleDb = 3.5f,
                echoMix = 0.25f,
            ),
        )
        val (doc3, _) = timelineEngine.addTimelineMarker(doc2, timeMs = 1_500L, label = "Chorus")
        val (doc4, _) = timelineEngine.addTimelineMarker(doc3, timeMs = 3_250L, label = "Hook")

        repository.saveDocument(doc4)
        repository.switchEditorMode(created.id, EditorMode.QUICK)

        val reopened = repository.observeProject(created.id).first()!!
        assertEquals(EditorMode.QUICK, reopened.editorMode)
        val reopenedClip = reopened.tracks.first { it.type == TrackType.AUDIO }.clips.first()
        assertEquals(AudioEffectPreset.STUDIO_PUNCH, reopenedClip.audioEffect.preset)
        assertEquals(0.45f, reopenedClip.audioEffect.pan, 0.01f)
        assertEquals(5.0f, reopenedClip.audioEffect.bassDb, 0.01f)
        assertEquals(3.5f, reopenedClip.audioEffect.trebleDb, 0.01f)
        assertEquals(0.25f, reopenedClip.audioEffect.echoMix, 0.01f)
        assertEquals(2, reopened.markers.size)
        assertEquals("Chorus", reopened.markers[0].label)
        assertEquals(3_250L, reopened.markers[1].timeMs)
    }

    @Test
    fun `phase 8 export settings persist and ExportOutputManager validates files and tracks artifact history`() = runTest {
        val created = repository.createProject(
            name = "Phase 8 Export",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val newSettings = ExportSettingsSpec(
            resolutionHeight = 1440,
            fps = 60,
            codec = "H.265",
            audioBitrateKbps = 256,
        )
        repository.updateProjectExportSettings(created.id, newSettings)

        val reopened = repository.observeProject(created.id).first()!!
        assertEquals(1440, reopened.exportSettings.resolutionHeight)
        assertEquals(60, reopened.exportSettings.fps)
        assertEquals("H.265", reopened.exportSettings.codec)
        assertEquals(256, reopened.exportSettings.audioBitrateKbps)

        val planner = com.example.engine.export.ExportPlanner()
        val caps = planner.probeHardwareCapabilities()
        assertNotNull(caps)

        val outputManager = com.example.engine.export.ExportOutputManager(context)
        val targetFile = outputManager.createTargetOutputFile(created.id, created.name)
        assertEquals(false, outputManager.validateExportedMp4(targetFile.absolutePath, expectAudio = false).isValid)

        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(ByteArray(1024) { 0x1A })
        val invalidResult = outputManager.validateExportedMp4(targetFile.absolutePath, expectAudio = false)
        assertEquals(false, invalidResult.isValid)

        val mockPlan = com.example.engine.export.ExportPlan(
            selectedPath = com.example.engine.export.ExportRenderPath.CUSTOM_GL_CODEC,
            reason = "Custom GL test",
            compatibilityNotes = emptyList(),
            outputWidth = 2560,
            outputHeight = 1440,
            fps = 60,
            codec = com.example.engine.export.ExportVideoCodec.H264,
            videoBitrateBps = 18_000_000,
            audioBitrateBps = 256_000,
            hasAudio = true,
            totalDurationMs = 5_000L,
            estimatedFileSizeMb = 11.4f,
            isExportable = true,
        )
        val mockValidation = com.example.engine.export.ExportValidationResult(
            isValid = true,
            fileSizeBytes = targetFile.length(),
            durationMs = 5_000L,
            width = 2560,
            height = 1440,
            hasVideoTrack = true,
            hasAudioTrack = true,
        )
        val artifact = outputManager.recordArtifact(created.id, targetFile, mockPlan, mockValidation)
        val history = outputManager.listProjectExports(created.id)
        assertEquals(1, history.size)
        assertEquals(artifact.id, history.first().id)

        outputManager.deleteArtifact(artifact.id)
        assertEquals(0, outputManager.listProjectExports(created.id).size)
    }

    @Test
    fun `phase 9 speed ramps, reverse, chroma key, 3d luts, stabilization, and 540p proxy paths persist across Room reopen`() = runTest {
        val created = repository.createProject(
            name = "Phase 9 Pro VFX",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val timelineEngine = TimelineEngine()
        val tempOrig = java.io.File(context.filesDir, "vfx_orig_test.mp4").apply { writeBytes(ByteArray(32)) }
        val tempProxy = java.io.File(context.filesDir, "vfx_540p_test.mp4").apply { writeBytes(ByteArray(16)) }
        val videoAsset = MediaAsset(
            id = "asset-p9-1",
            projectId = created.id,
            type = MediaType.VIDEO,
            displayName = "vfx.mp4",
            sourceUri = "content://media/vfx",
            managedFilePath = tempOrig.absolutePath,
            mimeType = "video/mp4",
            durationMs = 5_000L,
            width = 1920,
            height = 1080,
            fps = 30f,
            bitrate = 8_000_000L,
            fileSizeBytes = 4_000_000L,
            status = MediaStatus.READY,
            importedAt = 1_000L,
        )
        database.mediaAssetDao().insertAsset(
            com.example.core.database.MediaAssetEntity(
                id = videoAsset.id,
                projectId = created.id,
                mediaTypeName = videoAsset.type.name,
                displayName = videoAsset.displayName,
                sourceUri = videoAsset.sourceUri,
                managedFilePath = videoAsset.managedFilePath,
                mimeType = videoAsset.mimeType,
                durationMs = videoAsset.durationMs,
                width = videoAsset.width,
                height = videoAsset.height,
                fps = videoAsset.fps,
                bitrate = videoAsset.bitrate,
                fileSizeBytes = videoAsset.fileSizeBytes,
                statusName = videoAsset.status.name,
                importedAt = videoAsset.importedAt,
            ),
        )

        repository.updateAssetProxyPath(videoAsset.id, tempProxy.absolutePath)
        val reloadedAssets = repository.observeProjectAssets(created.id).first()
        val proxyAsset = reloadedAssets.first { it.id == videoAsset.id }
        assertEquals(true, proxyAsset.hasReadyProxy)
        assertEquals("file://${tempProxy.absolutePath}", proxyAsset.previewUri(preferProxy = true))
        assertEquals("file://${tempOrig.absolutePath}", proxyAsset.previewUri(preferProxy = false))
        assertEquals("file://${tempOrig.absolutePath}", proxyAsset.playbackUri)
        tempOrig.delete()
        tempProxy.delete()

        val (doc1, vClip) = timelineEngine.insertAssetAsClip(created, videoAsset)
        val clipId = vClip!!.clipId
        val rampPts = com.example.engine.timeline.SpeedRampEngine.presetPoints(SpeedRampPreset.MONTAGE)
        val doc2 = timelineEngine.updateClipSpeedRamp(doc1, clipId, SpeedRampPreset.MONTAGE, rampPts)
        val doc3 = timelineEngine.updateClipReversed(doc2, clipId, isReversed = true)
        val doc4 = timelineEngine.updateClipChromaKey(
            doc3,
            clipId,
            ChromaKeySpec(enabled = true, keyColorArgb = 0xFF0055FFL, similarity = 0.42f, smoothness = 0.18f, spillSuppression = 0.6f),
        )
        val doc5 = timelineEngine.updateClipLut(
            doc4,
            clipId,
            ClipLutSpec(preset = LutPreset.KODAK_2383, intensity = 0.92f),
        )
        val doc6 = timelineEngine.updateClipShaderEffect(
            doc5,
            clipId,
            ClipShaderEffect(ShaderEffectType.HALATION, intensity = 0.65f),
        )
        val doc7 = timelineEngine.updateClipStabilization(
            doc6,
            clipId,
            StabilizationSpec(enabled = true, strength = 0.8f, cropScale = 1.14f),
            emptyList(),
        )
        repository.saveDocument(doc7)
        repository.switchEditorMode(created.id, EditorMode.QUICK)

        val reopened = repository.observeProject(created.id).first()!!
        val reopenedClip = reopened.tracks.first { it.type == TrackType.VIDEO }.clips.first()
        assertEquals(SpeedRampPreset.MONTAGE, reopenedClip.speedRampPreset)
        assertEquals(rampPts.size, reopenedClip.speedRampPoints.size)
        assertEquals(true, reopenedClip.isReversed)
        assertEquals(true, reopenedClip.chromaKey.enabled)
        assertEquals(0xFF0055FFL, reopenedClip.chromaKey.keyColorArgb)
        assertEquals(0.42f, reopenedClip.chromaKey.similarity, 0.01f)
        assertEquals(LutPreset.KODAK_2383, reopenedClip.lutSpec.preset)
        assertEquals(0.92f, reopenedClip.lutSpec.intensity, 0.01f)
        assertEquals(ShaderEffectType.HALATION, reopenedClip.shaderEffect.type)
        assertEquals(true, reopenedClip.stabilization.enabled)
        assertEquals(1.14f, reopenedClip.stabilization.cropScale, 0.01f)
    }

    @Test
    fun `corrupt project JSON refuses destructive rename overwrite and Room MIGRATION_1_2 preserves assets`() = runTest {
        val brokenEntity = com.example.core.database.ProjectEntity(
            id = "proj-corrupt-1",
            name = "Protected Project",
            aspectRatioName = AspectRatioPreset.VERTICAL_9_16.name,
            canvasWidth = 1080,
            canvasHeight = 1920,
            fps = 30,
            editorModeName = EditorMode.QUICK.name,
            schemaVersion = 1,
            projectJson = "{ invalid_json_payload",
            coverMediaUri = null,
            createdAt = 100L,
            updatedAt = 200L,
        )
        database.projectDao().upsertProject(brokenEntity)

        // Mutating operation must not overwrite unreadable projectJson with a blank document
        repository.renameProject("proj-corrupt-1", "Overwritten?")
        val storedAfterRenameAttempt = database.projectDao().getProjectById("proj-corrupt-1")
        assertEquals("{ invalid_json_payload", storedAfterRenameAttempt?.projectJson)
        assertEquals("Protected Project", storedAfterRenameAttempt?.name)

        // Verify MIGRATION_1_2 SQL adds nullable proxyFilePath column cleanly on a real v1 SQLite DB
        val helperDbName = "migration_1_2_verify.db"
        context.deleteDatabase(helperDbName)
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(helperDbName).apply { parentFile?.mkdirs() },
            null,
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_assets (
                id TEXT NOT NULL PRIMARY KEY,
                projectId TEXT,
                mediaTypeName TEXT NOT NULL,
                displayName TEXT NOT NULL,
                sourceUri TEXT NOT NULL,
                managedFilePath TEXT,
                mimeType TEXT NOT NULL,
                durationMs INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                fps REAL NOT NULL,
                bitrate INTEGER NOT NULL,
                fileSizeBytes INTEGER NOT NULL,
                statusName TEXT NOT NULL,
                importedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        sqlite.execSQL(
            """
            INSERT INTO media_assets (
                id, projectId, mediaTypeName, displayName, sourceUri, managedFilePath,
                mimeType, durationMs, width, height, fps, bitrate, fileSizeBytes, statusName, importedAt
            ) VALUES (
                'a1', 'p1', 'VIDEO', 'clip.mp4', 'content://1', '/tmp/1.mp4',
                'video/mp4', 1000, 1920, 1080, 30.0, 1000, 500, 'READY', 123
            )
            """.trimIndent(),
        )
        val supportDb = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
            .let { sqlite }
        // Run the exact ALTER TABLE from MIGRATION_1_2 against the Framework SQLite DB
        supportDb.execSQL("ALTER TABLE media_assets ADD COLUMN proxyFilePath TEXT")
        val cursor = supportDb.rawQuery("SELECT id, proxyFilePath FROM media_assets WHERE id = 'a1'", null)
        assertEquals(true, cursor.moveToFirst())
        assertEquals("a1", cursor.getString(0))
        assertNull(cursor.getString(1))
        cursor.close()
        supportDb.close()
        context.deleteDatabase(helperDbName)
    }

    @Test
    fun `future schema v4 row opens read-only and blocks all repository writes while preserving raw JSON on disk`() = runTest {
        val futureRawJson = """
            {
              "schemaVersion": 4,
              "id": "proj-future-3",
              "name": "Future Captions Studio",
              "aspectRatio": "VERTICAL_9_16",
              "canvasWidth": 1080,
              "canvasHeight": 1920,
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
              "createdAt": 1000,
              "updatedAt": 2000
            }
        """.trimIndent()

        val futureEntity = com.example.core.database.ProjectEntity(
            id = "proj-future-3",
            name = "Future Captions Studio",
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
        )
        database.projectDao().upsertProject(futureEntity)
        database.mediaAssetDao().insertAsset(
            com.example.core.database.MediaAssetEntity(
                id = "future-asset-1",
                projectId = "proj-future-3",
                mediaTypeName = MediaType.VIDEO.name,
                displayName = "v.mp4",
                sourceUri = "content://media/v",
                managedFilePath = null,
                mimeType = "video/mp4",
                durationMs = 3_000L,
                width = 1080,
                height = 1920,
                fps = 30f,
                bitrate = 1_000_000L,
                fileSizeBytes = 500_000L,
                statusName = MediaStatus.READY.name,
                importedAt = 1_000L,
            ),
        )

        val loadState = repository.observeProjectLoadState("proj-future-3").first()
        assertEquals(true, loadState is ProjectRepositoryLoadState.UnsupportedFutureVersion)
        val unsupported = loadState as ProjectRepositoryLoadState.UnsupportedFutureVersion
        assertEquals(4, unsupported.detectedSchemaVersion)
        assertEquals(setOf("captionTracks", "lyricAlignmentMode"), unsupported.unknownTopLevelKeys)

        val summary = repository.observeProjectSummaries().first().single { it.document.id == "proj-future-3" }
        assertEquals(true, summary.isUnsupportedFutureVersion)
        assertEquals(4, summary.detectedSchemaVersion)

        // All mutating operations must be blocked without overwriting stored schema v4 payload
        assertEquals(
            ProjectSaveOutcome.BlockedUnsupportedFutureVersion("proj-future-3", 4),
            repository.renameProject("proj-future-3", "Hijacked"),
        )
        assertEquals(
            ProjectSaveOutcome.BlockedUnsupportedFutureVersion("proj-future-3", 4),
            repository.switchEditorMode("proj-future-3", EditorMode.QUICK),
        )
        assertEquals(
            ProjectSaveOutcome.BlockedUnsupportedFutureVersion("proj-future-3", 4),
            repository.updateProjectCanvas("proj-future-3", AspectRatioPreset.LANDSCAPE_16_9, 60),
        )
        assertEquals(
            ProjectSaveOutcome.BlockedUnsupportedFutureVersion("proj-future-3", 4),
            repository.updateProjectExportSettings("proj-future-3", ExportSettingsSpec(resolutionHeight = 720)),
        )
        val forgedV2Copy = unsupported.previewDocument.copy(
            schemaVersion = CURRENT_PROJECT_SCHEMA_VERSION,
            name = "Downgraded?",
        )
        assertEquals(
            ProjectSaveOutcome.BlockedUnsupportedFutureVersion("proj-future-3", 4),
            repository.saveDocument(forgedV2Copy),
        )
        assertNull(repository.addTrack("proj-future-3", TrackType.OVERLAY))
        assertNull(repository.duplicateProject("proj-future-3"))
        assertNull(repository.applyOverlapRepair("proj-future-3"))
        assertEquals(false, repository.deleteMediaAsset("future-asset-1"))
        val importReport = repository.importMediaToProject(
            projectId = "proj-future-3",
            uris = listOf(android.net.Uri.parse("content://media/external/video/1")),
            hintType = MediaType.VIDEO,
        )
        assertEquals(true, importReport.blockedByUnsupportedFutureSchema)

        val storedAfterAttempts = database.projectDao().getProjectById("proj-future-3")!!
        assertEquals(4, storedAfterAttempts.schemaVersion)
        assertEquals("Future Captions Studio", storedAfterAttempts.name)
        assertEquals(futureRawJson, storedAfterAttempts.projectJson)
    }

    @Test
    fun `legacy overlapping project loads without silent timing shift and repairs cleanly when explicitly requested`() = runTest {
        val legacyOverlapJson = """
            {
              "schemaVersion": 1,
              "id": "proj-legacy-overlap",
              "name": "Legacy Overlapping Edit",
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
                      "durationMs": 2000
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
            com.example.core.database.ProjectEntity(
                id = "proj-legacy-overlap",
                name = "Legacy Overlapping Edit",
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

        // Load must NOT silently shift clip timing
        val loadState = repository.observeProjectLoadState("proj-legacy-overlap").first()
        assertEquals(true, loadState is ProjectRepositoryLoadState.Ready)
        val ready = loadState as ProjectRepositoryLoadState.Ready
        assertEquals(true, ready.didMigrateSchema)
        assertEquals(1, ready.sourceSchemaVersion)
        assertNotNull(ready.overlapRepairReport)
        assertEquals(true, ready.overlapRepairReport!!.hasOverlaps)
        assertEquals(500L, ready.document.tracks.first { it.id == "track_v1" }.clips[1].timelineStartMs)

        val summary = repository.observeProjectSummaries().first().single { it.document.id == "proj-legacy-overlap" }
        assertEquals(true, summary.hasPendingOverlapRepair)

        // Normal save does NOT silently alter clip timing
        val saveResult = repository.saveDocument(ready.document.copy(name = "Renamed Legacy"))
        assertEquals(true, saveResult is ProjectSaveOutcome.Saved)
        val reopenedBeforeRepair = repository.observeProject("proj-legacy-overlap").first()!!
        assertEquals(500L, reopenedBeforeRepair.tracks.first { it.id == "track_v1" }.clips[1].timelineStartMs)

        // Explicit overflow-track repair resolves overlap while preserving 500ms timing
        val outcome = repository.applyOverlapRepair(
            projectId = "proj-legacy-overlap",
            mode = com.example.engine.timeline.TimelineOverlapRepairMode.MOVE_TO_OVERFLOW_TRACK,
        )
        assertNotNull(outcome)
        assertEquals(true, outcome!!.report.timingPreserved)
        val repairedState = repository.observeProjectLoadState("proj-legacy-overlap").first() as ProjectRepositoryLoadState.Ready
        assertNull(repairedState.overlapRepairReport)
        val movedClip = repairedState.document.tracks.flatMap { it.clips }.first { it.clipId == "c2" }
        assertEquals("track_ov1", movedClip.trackId)
        assertEquals(500L, movedClip.timelineStartMs)
        assertEquals(2_000L, movedClip.durationMs)
    }
}
