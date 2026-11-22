package com.example.engine.asr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.CutLabDatabase
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.database.MediaAssetEntity
import com.example.core.media.MediaMetadataExtractor
import com.example.core.storage.MediaStorageManager
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.effects.EffectsEngine
import com.example.engine.export.TextLayerBitmapRenderer
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.LayerTransform
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectEngine
import com.example.engine.project.ProjectRepository
import com.example.engine.project.TextContentType
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TimedTextWord
import com.example.engine.project.TrackType
import com.example.feature.project.AutoCaptionJobState
import com.example.feature.project.ProjectWorkspaceViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutoCaptionPipelineTest {

    private lateinit var context: Context
    private lateinit var database: CutLabDatabase
    private lateinit var repository: ProjectRepository
    private lateinit var preferencesRepository: CutLabPreferencesRepository
    private lateinit var asrModelManager: AsrModelManager

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
        asrModelManager = AsrModelManager(context)
        // Ensure clean model directory between tests
        AsrModelCatalog.MODELS.forEach { asrModelManager.deleteModel(it) }
    }

    @After
    fun teardown() {
        AsrModelCatalog.MODELS.forEach { asrModelManager.deleteModel(it) }
        Dispatchers.resetMain()
        database.close()
    }

    private fun installFakeValidModel(spec: AsrModelSpec = AsrModelCatalog.defaultModel()): File {
        val dir = asrModelManager.getModelDirectory(spec)
        dir.mkdirs()
        File(dir, "am").mkdirs()
        File(dir, "conf").mkdirs()
        File(dir, "graph").mkdirs()
        File(dir, "am/final.mdl").writeBytes(ByteArray(2048) { 1 })
        return dir
    }

    private suspend fun seedProjectWithVideoAsset(projectName: String = "Caption Test"): Pair<String, String> {
        val doc = repository.createProject(
            name = projectName,
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val assetId = "asset_vid_1"
        val tempVideoFile = File(context.cacheDir, "speech_clip.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
        database.mediaAssetDao().insertAsset(
            MediaAssetEntity(
                id = assetId,
                projectId = doc.id,
                mediaTypeName = MediaType.VIDEO.name,
                displayName = "speech_clip.mp4",
                sourceUri = "content://media/speech_clip",
                managedFilePath = tempVideoFile.absolutePath,
                mimeType = "video/mp4",
                durationMs = 5_000L,
                width = 1080,
                height = 1920,
                fps = 30f,
                bitrate = 2_000_000L,
                fileSizeBytes = 1_500_000L,
                statusName = MediaStatus.READY.name,
                importedAt = 1_000L,
            ),
        )
        return doc.id to assetId
    }

    @Test
    fun `streaming 16k mono resampler preserves sample rate ratio and chunk continuity across arbitrary boundaries`() {
        // 48 kHz mono for 1.0 second split across two uneven buffers -> 16,000 output samples total
        val resampler48k = StreamingMono16kResampler(inputSampleRateHz = 48_000, inputChannels = 1)
        val bufA = ShortArray(20_000) { i -> ((i % 200) * 100).toShort() }
        val bufB = ShortArray(28_000) { i -> (((i + 20_000) % 200) * 100).toShort() }
        val outA = resampler48k.processInterleavedShorts(bufA)
        val outB = resampler48k.processInterleavedShorts(bufB)
        assertEquals(16_000, outA.size + outB.size)

        // 44.1 kHz stereo float audio -> 1.0 second = 44,100 frames -> ~16,000 mono samples
        val resampler44kStereo = StreamingMono16kResampler(inputSampleRateHz = 44_100, inputChannels = 2)
        val stereo = FloatArray(44_100 * 2) { 0.25f }
        val out44k = resampler44kStereo.processInterleavedFloats(stereo)
        assertTrue("Expected ~16000 samples, got ${out44k.size}", out44k.size in 15_995..16_005)
        assertTrue(out44k.all { kotlin.math.abs(it - 8191) <= 2 })
    }

    @Test
    fun `vosk json parser decodes word timestamps cleanly and flags fallback segments without words as estimated`() {
        val voskEngine = VoskSpeechRecognitionEngine()
        val rawWithWords = """
            {
              "result" : [
                {"conf" : 0.98, "end" : 0.72, "start" : 0.30, "word" : "cut"},
                {"conf" : 0.94, "end" : 1.15, "start" : 0.72, "word" : "faster"}
              ],
              "text" : "cut faster"
            }
        """.trimIndent()
        val parsed = voskEngine.parseVoskBlock(rawWithWords, fallbackStartMs = 0L)
        assertNotNull(parsed)
        assertEquals("cut faster", parsed!!.text)
        assertFalse(parsed.isTimingEstimated)
        assertEquals(2, parsed.words.size)
        assertEquals(300L, parsed.words[0].startMs)
        assertEquals(720L, parsed.words[0].endMs)
        assertEquals(1_150L, parsed.words[1].endMs)

        // Wordless Vosk output is marked estimated rather than fabricating exact words
        val rawTextOnly = """{ "text" : "fallback line" }"""
        val fallback = voskEngine.parseVoskBlock(rawTextOnly, fallbackStartMs = 2_000L)
        assertNotNull(fallback)
        assertTrue(fallback!!.isTimingEstimated)
        assertTrue(fallback.words.isEmpty())
        assertEquals(2_000L, fallback.startMs)
        assertTrue(fallback.endMs > 2_000L)

        // Empty JSON returns null
        assertNull(voskEngine.parseVoskBlock("""{ "text" : "" }""", 0L))
        assertNull(voskEngine.parseVoskBlock(null, 0L))
    }

    @Test
    fun `asr model manager validates directory structure and cleans up deleted models`() {
        val spec = AsrModelCatalog.defaultModel()
        assertTrue(asrModelManager.inspectModelStatus(spec) is AsrModelAvailability.NotInstalled)

        // Corrupt incomplete dir without final.mdl -> still NotInstalled
        val dir = asrModelManager.getModelDirectory(spec)
        dir.mkdirs()
        File(dir, "conf").mkdirs()
        assertFalse(asrModelManager.isValidModelDirectory(dir))
        assertTrue(asrModelManager.inspectModelStatus(spec) is AsrModelAvailability.NotInstalled)

        // Valid installed layout -> Ready
        installFakeValidModel(spec)
        val ready = asrModelManager.inspectModelStatus(spec)
        assertTrue(ready is AsrModelAvailability.Ready)
        assertTrue((ready as AsrModelAvailability.Ready).diskBytes >= 2048L)

        assertTrue(asrModelManager.deleteModel(spec))
        assertTrue(asrModelManager.inspectModelStatus(spec) is AsrModelAvailability.NotInstalled)
    }

    @Test
    fun `auto caption segmentation wraps lines honors word timestamps and preserves segment-only timestamps`() {
        val result = AsrTranscriptResult(
            languageCode = "en",
            assetId = "a1",
            hasAudioTrack = true,
            isSilentOrEmpty = false,
            engineName = "Test ASR",
            segments = listOf(
                AsrTranscriptSegment(
                    text = "alpha beta gamma delta",
                    startMs = 500L,
                    endMs = 2_500L,
                    words = listOf(
                        AsrWordToken("1", "alpha", 500L, 900L),
                        AsrWordToken("2", "beta", 900L, 1_300L),
                        AsrWordToken("3", "gamma", 1_350L, 1_900L),
                        AsrWordToken("4", "delta", 1_900L, 2_500L),
                    ),
                ),
                AsrTranscriptSegment(
                    text = "segment only text",
                    startMs = 4_000L,
                    endMs = 5_500L,
                    words = emptyList(),
                    isTimingEstimated = true,
                ),
            ),
        )
        val cues = AutoCaptionSegmentation.segmentTranscript(
            result,
            CaptionGenerationConfig(maxCharsPerLine = 16, maxLinesPerCaption = 1, clipStartOffsetMs = 1_000L),
        )
        assertEquals(3, cues.size)
        assertEquals(1_500L, cues[0].startMs)
        assertEquals("segment only\ntext", cues[2].text)
        assertTrue(cues[2].words.isEmpty())
        assertTrue(cues[2].isTimingEstimated)
    }

    @Test
    fun `viewModel auto caption reports missing model unsupported runtime silent audio and supports cancel`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val (projectId, _) = seedProjectWithVideoAsset("Error & Cancel Test")
        val fakeExtractor = FakePcmAudioExtractor(context)

        // 1. Model unavailable error
        val vmNoModel = ProjectWorkspaceViewModel(
            projectId = projectId,
            projectRepository = repository,
            preferencesRepository = preferencesRepository,
            pcmAudioExtractor = fakeExtractor,
            asrModelManager = asrModelManager,
            speechRecognitionEngine = FakeSpeechRecognitionEngine(),
            appContext = context,
        )
        val job1 = backgroundScope.launch(dispatcher) { vmNoModel.uiState.collect {} }
        vmNoModel.uiState.first { !it.isLoading && it.allAssets.isNotEmpty() }
        vmNoModel.startAutoCaptionTranscription()
        advanceUntilIdle()
        val noModelErr = vmNoModel.uiState.value.autoCaptionState.jobState
        assertTrue(noModelErr is AutoCaptionJobState.Error)
        assertTrue((noModelErr as AutoCaptionJobState.Error).message.contains("not installed", ignoreCase = true))
        job1.cancel()

        // 2. Runtime unsupported error
        installFakeValidModel()
        val vmUnsupported = ProjectWorkspaceViewModel(
            projectId = projectId,
            projectRepository = repository,
            preferencesRepository = preferencesRepository,
            pcmAudioExtractor = fakeExtractor,
            asrModelManager = asrModelManager,
            speechRecognitionEngine = FakeSpeechRecognitionEngine(runtimeSupported = false),
            appContext = context,
        )
        val job2 = backgroundScope.launch(dispatcher) { vmUnsupported.uiState.collect {} }
        vmUnsupported.uiState.first { !it.isLoading && it.allAssets.isNotEmpty() }
        vmUnsupported.startAutoCaptionTranscription()
        advanceUntilIdle()
        val unsupportedErr = vmUnsupported.uiState.value.autoCaptionState.jobState
        assertTrue(unsupportedErr is AutoCaptionJobState.Error)
        assertTrue((unsupportedErr as AutoCaptionJobState.Error).message.contains("unavailable", ignoreCase = true))
        job2.cancel()

        // 3. Silent track & cancellation
        val vmSilent = ProjectWorkspaceViewModel(
            projectId = projectId,
            projectRepository = repository,
            preferencesRepository = preferencesRepository,
            pcmAudioExtractor = fakeExtractor,
            asrModelManager = asrModelManager,
            speechRecognitionEngine = FakeSpeechRecognitionEngine(isSilent = true),
            appContext = context,
        )
        val job3 = backgroundScope.launch(dispatcher) { vmSilent.uiState.collect {} }
        vmSilent.uiState.first { !it.isLoading && it.allAssets.isNotEmpty() }
        vmSilent.startAutoCaptionTranscription()
        advanceUntilIdle()
        val silentErr = vmSilent.uiState.value.autoCaptionState.jobState
        assertTrue(silentErr is AutoCaptionJobState.Error)
        assertTrue((silentErr as AutoCaptionJobState.Error).message.contains("No speech detected", ignoreCase = true))
        job3.cancel()

        // 4. Cancellation while running
        val gate = CompletableDeferred<Unit>()
        val vmCancel = ProjectWorkspaceViewModel(
            projectId = projectId,
            projectRepository = repository,
            preferencesRepository = preferencesRepository,
            pcmAudioExtractor = fakeExtractor,
            asrModelManager = asrModelManager,
            speechRecognitionEngine = FakeSpeechRecognitionEngine(suspendUntilGate = gate),
            appContext = context,
        )
        val job4 = backgroundScope.launch(dispatcher) { vmCancel.uiState.collect {} }
        vmCancel.uiState.first { !it.isLoading && it.allAssets.isNotEmpty() }
        vmCancel.startAutoCaptionTranscription()
        assertTrue(vmCancel.uiState.value.autoCaptionState.jobState is AutoCaptionJobState.Running)
        vmCancel.cancelAutoCaptionTranscription()
        advanceUntilIdle()
        assertTrue(vmCancel.uiState.value.autoCaptionState.jobState is AutoCaptionJobState.Cancelled)
        job4.cancel()
    }

    @Test
    fun `viewModel auto caption and lyric studio commit undo redo and persist word timings across reopen`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        installFakeValidModel()
        val (projectId, assetId) = seedProjectWithVideoAsset("RoundTrip Persist")
        val fakeExtractor = FakePcmAudioExtractor(context)
        val fakeAsr = FakeSpeechRecognitionEngine(
            cannedSegments = listOf(
                AsrTranscriptSegment(
                    text = "Cut faster every frame",
                    startMs = 200L,
                    endMs = 2_200L,
                    words = listOf(
                        AsrWordToken("w1", "Cut", 200L, 650L),
                        AsrWordToken("w2", "faster", 650L, 1_150L),
                        AsrWordToken("w3", "every", 1_150L, 1_650L),
                        AsrWordToken("w4", "frame", 1_650L, 2_200L),
                    ),
                ),
            ),
        )

        val viewModel = ProjectWorkspaceViewModel(
            projectId = projectId,
            projectRepository = repository,
            preferencesRepository = preferencesRepository,
            pcmAudioExtractor = fakeExtractor,
            asrModelManager = asrModelManager,
            speechRecognitionEngine = fakeAsr,
            appContext = context,
        )
        val collectJob = backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        viewModel.uiState.first { !it.isLoading && it.allAssets.isNotEmpty() }

        // Auto Caption: transcribe -> edit cue text -> commit as single undoable operation
        viewModel.selectCaptionSourceAsset(assetId)
        viewModel.startAutoCaptionTranscription()
        advanceUntilIdle()

        val completedCues = viewModel.uiState.value.autoCaptionState.cues
        assertEquals(1, completedCues.size)
        viewModel.updateCaptionCueText(completedCues.first().id, "Cut sharper every frame")
        viewModel.commitCaptionsToTimeline()
        advanceUntilIdle()

        val afterCommitState = viewModel.uiState.first { state ->
            state.project?.tracks?.filter { it.type == TrackType.TEXT }?.sumOf { it.clips.size } == 1
        }
        val afterCommitClips = afterCommitState.project!!.tracks
            .filter { it.type == TrackType.TEXT }
            .flatMap { it.clips }
        assertEquals(1, afterCommitClips.size)
        val captionClip = afterCommitClips.first()
        assertEquals(TextContentType.CAPTION, captionClip.textSpec!!.contentType)
        assertTrue(captionClip.textSpec.text.contains("sharper"))
        assertEquals(4, captionClip.textSpec.timedWords.size)

        // Undo removes caption batch without deleting asset; Redo restores it
        viewModel.undo()
        advanceUntilIdle()
        val afterUndoState = viewModel.uiState.first { state ->
            state.project?.tracks?.filter { it.type == TrackType.TEXT }?.sumOf { it.clips.size } == 0
        }
        assertEquals(0, afterUndoState.project!!.tracks.filter { it.type == TrackType.TEXT }.sumOf { it.clips.size })
        assertEquals(1, afterUndoState.allAssets.size)
        viewModel.redo()
        advanceUntilIdle()
        val afterRedoState = viewModel.uiState.first { state ->
            state.project?.tracks?.filter { it.type == TrackType.TEXT }?.sumOf { it.clips.size } == 1
        }
        assertEquals(1, afterRedoState.project!!.tracks.filter { it.type == TrackType.TEXT }.sumOf { it.clips.size })

        // Lyric Studio: import lyrics -> assign playhead timing -> enable Invert Lyric -> commit
        viewModel.importLyricsText("Midnight skyline burning bright")
        viewModel.seekTo(3_000L)
        viewModel.assignLyricLineStartAtPlayhead()
        viewModel.seekTo(5_000L)
        viewModel.assignLyricLineEndAtPlayhead()
        viewModel.distributeSelectedLineWordsAsEstimated()
        viewModel.setLyricUseInvertEffect(true)
        viewModel.commitLyricsToTimeline()
        advanceUntilIdle()

        viewModel.uiState.first { state ->
            state.project?.tracks?.filter { it.type == TrackType.TEXT }?.sumOf { it.clips.size } == 2
        }

        // Reopen persisted project directly from Room DB -> both caption & lyric clips survive restart
        val reopened = repository.observeProject(projectId).first()!!
        val reopenedTextClips = reopened.tracks
            .filter { it.type == TrackType.TEXT }
            .flatMap { it.clips }
            .sortedBy { it.timelineStartMs }
        assertEquals(2, reopenedTextClips.size)
        assertEquals(TextContentType.CAPTION, reopenedTextClips[0].textSpec?.contentType)
        assertEquals(TextContentType.LYRIC, reopenedTextClips[1].textSpec?.contentType)
        assertEquals(TextRenderMode.INVERT_VIDEO, reopenedTextClips[1].textSpec?.renderMode)
        assertTrue(reopenedTextClips[1].textSpec?.isTimingEstimated == true)
        assertEquals(4, reopenedTextClips[1].textSpec?.timedWords?.size)

        // Lock TEXT track -> committing another lyric batch is blocked
        val textTrackId = reopened.tracks.first { it.type == TrackType.TEXT }.id
        viewModel.toggleTrackLock(textTrackId)
        viewModel.uiState.first { state ->
            state.project?.tracks?.firstOrNull { it.id == textTrackId }?.isLocked == true
        }
        viewModel.commitLyricsToTimeline()
        advanceUntilIdle()
        val afterLockedCommitCount = viewModel.uiState.value.project!!.tracks
            .filter { it.type == TrackType.TEXT }
            .sumOf { it.clips.size }
        assertEquals(2, afterLockedCommitCount)

        collectJob.cancel()
    }

    @Test
    fun `text layer bitmap renderer generates solid white mask for invert lyric and normal tinted karaoke bitmap`() {
        val renderer = TextLayerBitmapRenderer(context)
        val invertSpec = EffectsEngine.defaultKaraokeLyricSpec("INVERT TEST").copy(
            renderMode = TextRenderMode.INVERT_VIDEO,
            invertStrength = 0.8f,
            invertEdgeSoftness = 0.2f,
            timedWords = listOf(
                TimedTextWord("i1", "INVERT", 0L, 500L),
                TimedTextWord("i2", "TEST", 500L, 1_000L),
            ),
        )
        val maskFrame = renderer.render(
            spec = invertSpec,
            transform = LayerTransform(),
            clipRelativeMs = 250L,
            canvasWidth = 1080,
            canvasHeight = 1920,
        )
        assertNotNull(maskFrame)
        assertTrue(maskFrame!!.isInvertMask)
        assertEquals(0.8f, maskFrame.invertStrength, 0.001f)
        assertEquals(0.2f, maskFrame.invertEdgeSoftness, 0.001f)
        assertTrue(maskFrame.bitmap.width > 0 && maskFrame.bitmap.height > 0)
        assertTrue(maskFrame.cacheKey.startsWith("INV|"))
        assertTrue(maskFrame.isCacheManaged)

        // Identical frame parameters reuse the cached Bitmap instance without re-allocation
        val cachedFrame = renderer.render(
            spec = invertSpec,
            transform = LayerTransform(),
            clipRelativeMs = 300L, // same active word ("INVERT")
            canvasWidth = 1080,
            canvasHeight = 1920,
        )
        assertNotNull(cachedFrame)
        assertEquals(maskFrame.cacheKey, cachedFrame!!.cacheKey)
        assertTrue(cachedFrame.bitmap === maskFrame.bitmap)

        // Advancing playhead into second word updates karaoke cache signature and mask bitmap
        val secondWordFrame = renderer.render(
            spec = invertSpec,
            transform = LayerTransform(),
            clipRelativeMs = 650L,
            canvasWidth = 1080,
            canvasHeight = 1920,
        )
        assertNotNull(secondWordFrame)
        assertFalse(secondWordFrame!!.cacheKey == maskFrame.cacheKey)

        // Normal mode sets isInvertMask = false
        val normalFrame = renderer.render(
            spec = invertSpec.copy(renderMode = TextRenderMode.NORMAL),
            transform = LayerTransform(),
            clipRelativeMs = 250L,
            canvasWidth = 1080,
            canvasHeight = 1920,
        )
        assertNotNull(normalFrame)
        assertFalse(normalFrame!!.isInvertMask)
        assertTrue(normalFrame.cacheKey.startsWith("NRM|"))

        // Empty text returns null safely
        val emptyFrame = renderer.render(
            spec = invertSpec.copy(text = "   ", timedWords = emptyList()),
            transform = LayerTransform(),
            clipRelativeMs = 0L,
            canvasWidth = 1080,
            canvasHeight = 1920,
        )
        assertNull(emptyFrame)
    }

    private class FakePcmAudioExtractor(
        context: Context,
    ) : PcmAudioExtractor(context) {
        override suspend fun streamMonoPcm16kChunks(
            asset: MediaAsset,
            chunkSamplesCount: Int,
            onProgress: (Float) -> Unit,
            onChunk: suspend (ShortArray, Long) -> Unit,
        ): PcmStreamSummary {
            onChunk(ShortArray(16_000) { 1200 }, 0L)
            onProgress(1.0f)
            return PcmStreamSummary(
                chunksProcessed = 1,
                totalSamples16k = 16_000L,
                totalDurationMs = 1_000L,
                hasAudioTrack = true,
                peakAmplitude = 0.25f,
            )
        }
    }

    private class FakeSpeechRecognitionEngine(
        private val runtimeSupported: Boolean = true,
        private val isSilent: Boolean = false,
        private val cannedSegments: List<AsrTranscriptSegment> = emptyList(),
        private val suspendUntilGate: CompletableDeferred<Unit>? = null,
    ) : SpeechRecognitionEngine {
        override val engineDisplayName: String = "Fake Vosk Test Engine"
        override fun isRuntimeSupported(): Boolean = runtimeSupported

        override suspend fun transcribeAudio(
            assetId: String,
            modelSpec: AsrModelSpec,
            modelDirectory: File,
            streamMono16kPcm: suspend (suspend (ShortArray, Long) -> Unit) -> PcmStreamSummary,
            onProgress: (Float, String) -> Unit,
        ): Result<AsrTranscriptResult> {
            onProgress(0.25f, "Testing...")
            if (suspendUntilGate != null) {
                while (!suspendUntilGate.isCompleted) {
                    delay(50L)
                }
            }
            val summary = streamMono16kPcm { _, _ -> }
            return Result.success(
                AsrTranscriptResult(
                    languageCode = modelSpec.languageCode,
                    assetId = assetId,
                    segments = if (isSilent) emptyList() else cannedSegments,
                    hasAudioTrack = summary.hasAudioTrack,
                    isSilentOrEmpty = isSilent,
                    engineName = engineDisplayName,
                ),
            )
        }
    }
}
