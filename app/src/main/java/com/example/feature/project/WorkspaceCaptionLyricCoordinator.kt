package com.example.feature.project

import android.content.Context
import android.net.Uri
import com.example.engine.asr.AsrLanguageOption
import com.example.engine.asr.AsrModelAvailability
import com.example.engine.asr.AsrModelCatalog
import com.example.engine.asr.AsrModelManager
import com.example.engine.asr.AsrModelSpec
import com.example.engine.asr.AsrTranscriptResult
import com.example.engine.asr.AutoCaptionSegmentation
import com.example.engine.asr.CaptionGenerationConfig
import com.example.engine.asr.DeferredExperimentalLyricProvider
import com.example.engine.asr.EditableCaptionCue
import com.example.engine.asr.LyricAlignmentAvailability
import com.example.engine.asr.LyricRecognitionProvider
import com.example.engine.asr.SpeechRecognitionEngine
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TimelineClipRef
import com.example.engine.text.LyricEngine
import com.example.engine.text.LyricLineDraft
import com.example.engine.text.LyricWordDraft
import com.example.engine.timeline.TimedTextClipRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AutoCaptionJobState {
    data object Idle : AutoCaptionJobState
    data class LoadingModel(val modelName: String) : AutoCaptionJobState
    data class Running(val progress: Float, val statusMessage: String) : AutoCaptionJobState
    data class Completed(val cueCount: Int, val engineName: String) : AutoCaptionJobState
    data class Error(val message: String) : AutoCaptionJobState
    data object Cancelled : AutoCaptionJobState
}

data class AutoCaptionUiState(
    val models: List<AsrModelSpec> = AsrModelCatalog.MODELS,
    val modelStatuses: Map<String, AsrModelAvailability> = emptyMap(),
    val selectedModelId: String = AsrModelCatalog.defaultModel().id,
    val selectedLanguageCode: String = AsrLanguageOption.AUTO.code,
    val selectedSourceAssetId: String? = null,
    val jobState: AutoCaptionJobState = AutoCaptionJobState.Idle,
    val cues: List<EditableCaptionCue> = emptyList(),
    val maxCharsPerLine: Int = 32,
    val karaokeMode: KaraokeHighlightMode = KaraokeHighlightMode.ACTIVE_WORD,
    val stylePreset: TextStylePreset = TextStylePreset.MINIMAL_SUBTITLE,
    val useInvertEffect: Boolean = false,
) {
    val selectedModel: AsrModelSpec
        get() = AsrModelCatalog.findById(selectedModelId) ?: AsrModelCatalog.defaultModel()

    val selectedLanguage: AsrLanguageOption
        get() = AsrLanguageOption.fromCode(selectedLanguageCode)

    val selectedModelAvailability: AsrModelAvailability
        get() = modelStatuses[selectedModel.id] ?: AsrModelAvailability.NotInstalled
}

data class LyricStudioUiState(
    val lines: List<LyricLineDraft> = emptyList(),
    val selectedLineId: String? = null,
    val selectedWordId: String? = null,
    val selectedSourceAssetId: String? = null,
    val snapToFrames: Boolean = true,
    val snapToBeats: Boolean = false,
    val karaokeMode: KaraokeHighlightMode = KaraokeHighlightMode.PROGRESSIVE,
    val stylePreset: TextStylePreset = TextStylePreset.BOLD_TITLE,
    val useInvertEffect: Boolean = false,
    val recognitionAvailability: LyricAlignmentAvailability = DeferredExperimentalLyricProvider().checkAvailability(),
) {
    val selectedLine: LyricLineDraft?
        get() = lines.firstOrNull { it.id == selectedLineId } ?: lines.firstOrNull()

    val selectedWord: LyricWordDraft?
        get() = selectedLine?.words?.firstOrNull { it.id == selectedWordId }
}

private data class CaptionSettingsBundle(
    val cues: List<EditableCaptionCue>,
    val maxCharsPerLine: Int,
    val karaokeMode: KaraokeHighlightMode,
    val stylePreset: TextStylePreset,
    val useInvertEffect: Boolean,
    val selectedLanguageCode: String,
)

private data class LyricLinesBundle(
    val lines: List<LyricLineDraft>,
    val selectedLineId: String?,
    val selectedWordId: String?,
    val selectedSourceAssetId: String?,
)

private data class LyricSettingsBundle(
    val snapToFrames: Boolean,
    val snapToBeats: Boolean,
    val karaokeMode: KaraokeHighlightMode,
    val stylePreset: TextStylePreset,
    val useInvertEffect: Boolean,
)

class WorkspaceCaptionLyricCoordinator(
    private val scope: CoroutineScope,
    private val pcmAudioExtractor: PcmAudioExtractor? = null,
    private val asrModelManager: AsrModelManager? = null,
    private val speechRecognitionEngine: SpeechRecognitionEngine? = null,
    private val lyricRecognitionProvider: LyricRecognitionProvider = DeferredExperimentalLyricProvider(),
    private val appContext: Context? = null,
    private val emitMessage: suspend (String) -> Unit = {},
) {
    private val modelStatuses = MutableStateFlow<Map<String, AsrModelAvailability>>(emptyMap())
    private val selectedModelId = MutableStateFlow(AsrModelCatalog.defaultModel().id)
    private val selectedLanguageCode = MutableStateFlow(AsrLanguageOption.AUTO.code)
    private val selectedCaptionAssetId = MutableStateFlow<String?>(null)
    private val captionJobState = MutableStateFlow<AutoCaptionJobState>(AutoCaptionJobState.Idle)
    private val editableCues = MutableStateFlow<List<EditableCaptionCue>>(emptyList())
    private val captionMaxCharsPerLine = MutableStateFlow(32)
    private val captionKaraokeMode = MutableStateFlow(KaraokeHighlightMode.ACTIVE_WORD)
    private val captionStylePreset = MutableStateFlow(TextStylePreset.MINIMAL_SUBTITLE)
    private val captionUseInvertEffect = MutableStateFlow(false)

    private val lyricLines = MutableStateFlow<List<LyricLineDraft>>(emptyList())
    private val selectedLyricLineId = MutableStateFlow<String?>(null)
    private val selectedLyricWordId = MutableStateFlow<String?>(null)
    private val selectedLyricAssetId = MutableStateFlow<String?>(null)
    private val lyricSnapToFrames = MutableStateFlow(true)
    private val lyricSnapToBeats = MutableStateFlow(false)
    private val lyricKaraokeMode = MutableStateFlow(KaraokeHighlightMode.PROGRESSIVE)
    private val lyricStylePreset = MutableStateFlow(TextStylePreset.BOLD_TITLE)
    private val lyricUseInvertEffect = MutableStateFlow(false)

    private var downloadJobs = mutableMapOf<String, Job>()
    private var transcriptionJob: Job? = null
    private var lastTranscriptResult: AsrTranscriptResult? = null
    private var lastTranscriptClipOffsetMs: Long = 0L

    init {
        refreshModelStatuses()
    }

    private val captionStyleBundle = combine(
        editableCues,
        captionMaxCharsPerLine,
        captionKaraokeMode,
        captionStylePreset,
        captionUseInvertEffect,
    ) { cues, maxChars, karaoke, preset, invert ->
        CaptionSettingsBundle(
            cues = cues,
            maxCharsPerLine = maxChars,
            karaokeMode = karaoke,
            stylePreset = preset,
            useInvertEffect = invert,
            selectedLanguageCode = selectedLanguageCode.value,
        )
    }

    private val captionSettings = combine(
        captionStyleBundle,
        selectedLanguageCode,
    ) { bundle, langCode ->
        bundle.copy(selectedLanguageCode = langCode)
    }

    val autoCaptionState: StateFlow<AutoCaptionUiState> = combine(
        modelStatuses,
        selectedModelId,
        selectedCaptionAssetId,
        captionJobState,
        captionSettings,
    ) { statuses, modelId, sourceId, job, settings ->
        AutoCaptionUiState(
            models = AsrModelCatalog.MODELS,
            modelStatuses = statuses,
            selectedModelId = modelId,
            selectedLanguageCode = settings.selectedLanguageCode,
            selectedSourceAssetId = sourceId,
            jobState = job,
            cues = settings.cues,
            maxCharsPerLine = settings.maxCharsPerLine,
            karaokeMode = settings.karaokeMode,
            stylePreset = settings.stylePreset,
            useInvertEffect = settings.useInvertEffect,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = AutoCaptionUiState(),
    )

    private val lyricLinesBundle = combine(
        lyricLines,
        selectedLyricLineId,
        selectedLyricWordId,
        selectedLyricAssetId,
    ) { lines, lineId, wordId, assetId ->
        LyricLinesBundle(
            lines = lines,
            selectedLineId = lineId,
            selectedWordId = wordId,
            selectedSourceAssetId = assetId,
        )
    }

    private val lyricSettingsBundle = combine(
        lyricSnapToFrames,
        lyricSnapToBeats,
        lyricKaraokeMode,
        lyricStylePreset,
        lyricUseInvertEffect,
    ) { frames, beats, karaoke, preset, invert ->
        LyricSettingsBundle(
            snapToFrames = frames,
            snapToBeats = beats,
            karaokeMode = karaoke,
            stylePreset = preset,
            useInvertEffect = invert,
        )
    }

    val lyricStudioState: StateFlow<LyricStudioUiState> = combine(
        lyricLinesBundle,
        lyricSettingsBundle,
    ) { linesBundle, settings ->
        LyricStudioUiState(
            lines = linesBundle.lines,
            selectedLineId = linesBundle.selectedLineId,
            selectedWordId = linesBundle.selectedWordId,
            selectedSourceAssetId = linesBundle.selectedSourceAssetId,
            snapToFrames = settings.snapToFrames,
            snapToBeats = settings.snapToBeats,
            karaokeMode = settings.karaokeMode,
            stylePreset = settings.stylePreset,
            useInvertEffect = settings.useInvertEffect,
            recognitionAvailability = lyricRecognitionProvider.checkAvailability(),
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = LyricStudioUiState(
            recognitionAvailability = lyricRecognitionProvider.checkAvailability(),
        ),
    )

    fun refreshModelStatuses() {
        val manager = asrModelManager ?: return
        val next = AsrModelCatalog.MODELS.associate { model ->
            model.id to manager.inspectModelStatus(model)
        }
        modelStatuses.value = next
    }

    fun selectAsrModel(modelId: String) {
        if (AsrModelCatalog.findById(modelId) != null) {
            selectedModelId.value = modelId
        }
    }

    fun selectCaptionLanguage(languageCode: String) {
        selectedLanguageCode.value = AsrLanguageOption.fromCode(languageCode).code
    }

    fun selectCaptionSourceAsset(assetId: String?) {
        selectedCaptionAssetId.value = assetId
    }

    fun downloadModel(modelId: String = selectedModelId.value) {
        val manager = asrModelManager ?: run {
            scope.launch { emitMessage("ASR model manager unavailable") }
            return
        }
        val model = AsrModelCatalog.findById(modelId) ?: return
        if (downloadJobs[modelId]?.isActive == true) return

        downloadJobs[modelId] = scope.launch {
            modelStatuses.value = modelStatuses.value + (
                modelId to AsrModelAvailability.Downloading(0f, 0L, model.approximateSizeMb.toLong() * 1024L * 1024L)
                )
            try {
                val res = manager.downloadAndInstallModel(model) { progress, dlBytes, totalBytes ->
                    modelStatuses.value = modelStatuses.value + (
                        modelId to AsrModelAvailability.Downloading(progress, dlBytes, totalBytes)
                        )
                }
                res.onSuccess {
                    val inspected = manager.inspectModelStatus(model)
                    modelStatuses.value = modelStatuses.value + (modelId to inspected)
                    emitMessage("${model.displayName} ready for offline captions")
                }.onFailure { err ->
                    val message = err.message ?: "Model download failed"
                    modelStatuses.value = modelStatuses.value + (modelId to AsrModelAvailability.Error(message))
                    emitMessage(message)
                }
            } catch (_: CancellationException) {
                modelStatuses.value = modelStatuses.value + (modelId to manager.inspectModelStatus(model))
                emitMessage("Model download cancelled")
            } finally {
                downloadJobs.remove(modelId)
            }
        }
    }

    fun cancelModelDownload(modelId: String = selectedModelId.value) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        asrModelManager?.let { manager ->
            AsrModelCatalog.findById(modelId)?.let { spec ->
                modelStatuses.value = modelStatuses.value + (modelId to manager.inspectModelStatus(spec))
            }
        }
    }

    fun deleteModel(modelId: String = selectedModelId.value) {
        val manager = asrModelManager ?: return
        val model = AsrModelCatalog.findById(modelId) ?: return
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        manager.deleteModel(model)
        modelStatuses.value = modelStatuses.value + (modelId to manager.inspectModelStatus(model))
        scope.launch { emitMessage("Removed offline model ${model.displayName}") }
    }

    fun startTranscription(
        assets: List<MediaAsset>,
        timelineClips: List<TimelineClipRef>,
    ) {
        val extractor = pcmAudioExtractor
        val engine = speechRecognitionEngine
        val manager = asrModelManager
        if (extractor == null || engine == null || manager == null) {
            captionJobState.value = AutoCaptionJobState.Error("Offline ASR runtime is not initialized.")
            return
        }
        val audioCapableAssets = assets.filter { it.type != MediaType.IMAGE && it.status == MediaStatus.READY }
        val resolvedAsset = selectedCaptionAssetId.value?.let { id ->
            audioCapableAssets.firstOrNull { it.id == id }
        } ?: audioCapableAssets.firstOrNull()

        if (resolvedAsset == null) {
            captionJobState.value = AutoCaptionJobState.Error("Import a ready video or audio clip first.")
            return
        }
        selectedCaptionAssetId.value = resolvedAsset.id

        val model = AsrModelCatalog.findById(selectedModelId.value) ?: AsrModelCatalog.defaultModel()
        val availability = manager.inspectModelStatus(model)
        modelStatuses.value = modelStatuses.value + (model.id to availability)
        if (availability !is AsrModelAvailability.Ready) {
            captionJobState.value = AutoCaptionJobState.Error(
                "Offline model '${model.displayName}' is not installed. Tap Download Model first.",
            )
            return
        }
        if (!engine.isRuntimeSupported()) {
            captionJobState.value = AutoCaptionJobState.Error(
                "sherpa-onnx Whisper native ASR library is unavailable on this ABI/runtime.",
            )
            return
        }

        val matchingClipStart = timelineClips
            .filter { it.assetId == resolvedAsset.id }
            .minByOrNull { it.timelineStartMs }
            ?.timelineStartMs ?: 0L
        val languageOption = AsrLanguageOption.fromCode(selectedLanguageCode.value)

        transcriptionJob?.cancel()
        transcriptionJob = scope.launch {
            captionJobState.value = AutoCaptionJobState.LoadingModel(model.displayName)
            try {
                val res = engine.transcribeAudio(
                    assetId = resolvedAsset.id,
                    modelSpec = model,
                    modelDirectory = manager.getModelDirectory(model),
                    languageOption = languageOption,
                    streamMono16kPcm = { onChunk ->
                        extractor.streamMonoPcm16kChunks(resolvedAsset, onChunk = onChunk)
                    },
                    onProgress = { progress, status ->
                        captionJobState.value = if (status.startsWith("Loading", ignoreCase = true)) {
                            AutoCaptionJobState.LoadingModel(model.displayName)
                        } else {
                            AutoCaptionJobState.Running(progress, status)
                        }
                    },
                )
                res.onSuccess { transcript ->
                    lastTranscriptResult = transcript
                    lastTranscriptClipOffsetMs = matchingClipStart
                    if (!transcript.hasAudioTrack) {
                        editableCues.value = emptyList()
                        captionJobState.value = AutoCaptionJobState.Error(
                            "No audio track detected in '${resolvedAsset.displayName}'.",
                        )
                        return@onSuccess
                    }
                    if (transcript.isSilentOrEmpty || transcript.segments.isEmpty()) {
                        editableCues.value = emptyList()
                        captionJobState.value = AutoCaptionJobState.Error(
                            "No speech detected in audio (silent track or unrecognized speech).",
                        )
                        return@onSuccess
                    }
                    val segmented = AutoCaptionSegmentation.segmentTranscript(
                        result = transcript,
                        config = CaptionGenerationConfig(
                            maxCharsPerLine = captionMaxCharsPerLine.value,
                            maxLinesPerCaption = 2,
                            karaokeMode = captionKaraokeMode.value,
                            clipStartOffsetMs = matchingClipStart,
                        ),
                    )
                    editableCues.value = segmented
                    captionJobState.value = AutoCaptionJobState.Completed(
                        cueCount = segmented.size,
                        engineName = transcript.engineName,
                    )
                    emitMessage("Generated ${segmented.size} editable caption cue(s)")
                }.onFailure { err ->
                    val msg = err.message ?: "Offline speech transcription failed."
                    captionJobState.value = AutoCaptionJobState.Error(msg)
                    emitMessage(msg)
                }
            } catch (_: CancellationException) {
                captionJobState.value = AutoCaptionJobState.Cancelled
                emitMessage("Auto Caption cancelled")
            } finally {
                transcriptionJob = null
            }
        }
    }

    fun cancelTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        captionJobState.value = AutoCaptionJobState.Cancelled
    }

    fun updateCaptionMaxCharsPerLine(chars: Int) {
        val clamped = chars.coerceIn(16, 52)
        captionMaxCharsPerLine.value = clamped
        val raw = lastTranscriptResult
        if (raw != null && raw.segments.isNotEmpty()) {
            editableCues.value = AutoCaptionSegmentation.segmentTranscript(
                result = raw,
                config = CaptionGenerationConfig(
                    maxCharsPerLine = clamped,
                    maxLinesPerCaption = 2,
                    karaokeMode = captionKaraokeMode.value,
                    clipStartOffsetMs = lastTranscriptClipOffsetMs,
                ),
            )
        } else {
            editableCues.value = editableCues.value.map { cue ->
                cue.copy(text = AutoCaptionSegmentation.wrapLines(cue.text, clamped))
            }
        }
    }

    fun updateCaptionKaraokeMode(mode: KaraokeHighlightMode) {
        captionKaraokeMode.value = mode
    }

    fun updateCaptionStylePreset(preset: TextStylePreset) {
        captionStylePreset.value = preset
    }

    fun updateCaptionUseInvertEffect(enabled: Boolean) {
        captionUseInvertEffect.value = enabled
    }

    fun updateCueText(cueId: String, newText: String) {
        editableCues.value = editableCues.value.map { cue ->
            if (cue.id == cueId) cue.copy(text = newText) else cue
        }
    }

    fun deleteCue(cueId: String) {
        editableCues.value = editableCues.value.filterNot { it.id == cueId }
    }

    fun clearCaptionsDraft() {
        editableCues.value = emptyList()
        lastTranscriptResult = null
        captionJobState.value = AutoCaptionJobState.Idle
    }

    fun buildCaptionClipRequests(): List<TimedTextClipRequest> {
        val cues = editableCues.value
        if (cues.isEmpty()) return emptyList()
        val useInvert = captionUseInvertEffect.value
        val template = if (useInvert) {
            val basePreset = EffectsEngine.defaultTextSpec(captionStylePreset.value)
            EffectsEngine.defaultInvertLyricSpec().copy(
                stylePreset = captionStylePreset.value,
                fontScale = basePreset.fontScale,
                alignment = basePreset.alignment,
                offsetY = basePreset.offsetY,
                karaokeMode = captionKaraokeMode.value,
            )
        } else {
            val basePreset = EffectsEngine.defaultTextSpec(captionStylePreset.value)
            EffectsEngine.defaultCaptionSpec().copy(
                stylePreset = captionStylePreset.value,
                fontScale = basePreset.fontScale,
                textColorArgb = basePreset.textColorArgb,
                backgroundColorArgb = basePreset.backgroundColorArgb,
                alignment = basePreset.alignment,
                offsetY = basePreset.offsetY,
                karaokeMode = captionKaraokeMode.value,
            )
        }
        val resolvedLanguage = lastTranscriptResult?.detectedLanguageCode
            ?: lastTranscriptResult?.languageCode
            ?: selectedLanguageCode.value
        return AutoCaptionSegmentation.buildClipRequests(
            cues = cues,
            styleTemplate = template,
            sourceLanguage = resolvedLanguage,
            sourceAssetId = selectedCaptionAssetId.value,
            karaokeMode = captionKaraokeMode.value,
        )
    }

    // --- Manual Lyric Studio ---

    fun selectLyricSourceAsset(assetId: String?) {
        selectedLyricAssetId.value = assetId
    }

    fun importLyricsText(rawText: String, replaceExisting: Boolean = true) {
        val parsed = LyricEngine.parseLyricsText(rawText)
        val combined = if (replaceExisting) parsed else lyricLines.value + parsed
        lyricLines.value = combined
        selectedLyricLineId.value = combined.firstOrNull()?.id
        selectedLyricWordId.value = combined.firstOrNull()?.words?.firstOrNull()?.id
        scope.launch {
            if (parsed.isEmpty()) {
                emitMessage("No non-empty lyric lines found")
            } else {
                emitMessage("Loaded ${parsed.size} lyric line(s) into Lyric Studio")
            }
        }
    }

    fun importLyricsFromUri(uri: Uri) {
        val ctx = appContext ?: run {
            scope.launch { emitMessage("Context unavailable for .txt lyric import") }
            return
        }
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    }
                }.getOrNull()
            }
            if (content.isNullOrBlank()) {
                emitMessage("Could not read text from selected .txt lyric file")
            } else {
                importLyricsText(content, replaceExisting = true)
            }
        }
    }

    fun loadClipIntoLyricStudio(clip: TimelineClipRef) {
        val spec = clip.textSpec ?: return
        val words = if (spec.timedWords.isNotEmpty()) {
            spec.timedWords.map { w ->
                LyricWordDraft(
                    id = w.id,
                    text = w.text,
                    startMs = clip.timelineStartMs + w.startMs,
                    endMs = clip.timelineStartMs + w.endMs,
                    isEstimated = w.isEstimated,
                )
            }
        } else {
            LyricEngine.parseLyricsText(spec.text).firstOrNull()?.words.orEmpty()
        }
        val draftLine = LyricLineDraft(
            id = clip.clipId,
            text = spec.text,
            startMs = clip.timelineStartMs,
            endMs = clip.timelineStartMs + clip.durationMs,
            words = words,
            isEstimated = spec.isTimingEstimated,
        )
        lyricLines.value = listOf(draftLine)
        selectedLyricLineId.value = draftLine.id
        selectedLyricWordId.value = draftLine.words.firstOrNull()?.id
        lyricKaraokeMode.value = spec.karaokeMode
        lyricUseInvertEffect.value = spec.renderMode == TextRenderMode.INVERT_VIDEO
        lyricStylePreset.value = spec.stylePreset
    }

    fun selectLyricLine(lineId: String?) {
        selectedLyricLineId.value = lineId
        val line = lyricLines.value.firstOrNull { it.id == lineId }
        selectedLyricWordId.value = line?.words?.firstOrNull()?.id
    }

    fun selectLyricWord(lineId: String, wordId: String?) {
        selectedLyricLineId.value = lineId
        selectedLyricWordId.value = wordId
    }

    fun addEmptyLyricLine(text: String = "New lyric line") {
        val newLine = LyricLineDraft(text = text)
        val current = lyricLines.value
        val idx = current.indexOfFirst { it.id == selectedLyricLineId.value }
        val updated = current.toMutableList().apply {
            if (idx >= 0) add(idx + 1, newLine) else add(newLine)
        }
        lyricLines.value = updated
        selectedLyricLineId.value = newLine.id
        selectedLyricWordId.value = newLine.words.firstOrNull()?.id
    }

    fun editLyricLineText(lineId: String, newText: String) {
        lyricLines.value = LyricEngine.editLineText(lyricLines.value, lineId, newText)
    }

    fun splitSelectedWordLine() {
        val line = lyricStudioState.value.selectedLine ?: return
        val wordIdx = line.words.indexOfFirst { it.id == selectedLyricWordId.value }
            .takeIf { it >= 0 && it < line.words.lastIndex }
            ?: ((line.words.size / 2) - 1).coerceAtLeast(0)
        val updated = LyricEngine.splitLine(lyricLines.value, line.id, wordIdx)
        lyricLines.value = updated
    }

    fun mergeLineWithNext(lineId: String) {
        lyricLines.value = LyricEngine.mergeWithNext(lyricLines.value, lineId)
    }

    fun moveLyricLine(lineId: String, delta: Int) {
        lyricLines.value = LyricEngine.moveLine(lyricLines.value, lineId, delta)
    }

    fun deleteLyricLine(lineId: String) {
        val updated = LyricEngine.deleteLine(lyricLines.value, lineId)
        lyricLines.value = updated
        if (selectedLyricLineId.value == lineId) {
            selectedLyricLineId.value = updated.firstOrNull()?.id
            selectedLyricWordId.value = updated.firstOrNull()?.words?.firstOrNull()?.id
        }
    }

    fun assignLineStartAtPlayhead(playheadMs: Long, fps: Int, beatsMs: List<Long>) {
        val line = lyricStudioState.value.selectedLine ?: return
        val snapped = LyricEngine.snapTimestampMs(
            timeMs = playheadMs,
            fps = fps,
            beatTimestampsMs = beatsMs,
            snapToBeats = lyricSnapToBeats.value,
            snapToFrames = lyricSnapToFrames.value,
        )
        lyricLines.value = LyricEngine.assignLineStart(lyricLines.value, line.id, snapped)
    }

    fun assignLineEndAtPlayhead(playheadMs: Long, fps: Int, beatsMs: List<Long>) {
        val line = lyricStudioState.value.selectedLine ?: return
        val snapped = LyricEngine.snapTimestampMs(
            timeMs = playheadMs,
            fps = fps,
            beatTimestampsMs = beatsMs,
            snapToBeats = lyricSnapToBeats.value,
            snapToFrames = lyricSnapToFrames.value,
        )
        lyricLines.value = LyricEngine.assignLineEnd(lyricLines.value, line.id, snapped)
    }

    fun assignWordStartAtPlayhead(
        lineId: String,
        wordId: String,
        playheadMs: Long,
        fps: Int,
        beatsMs: List<Long>,
    ) {
        val snapped = LyricEngine.snapTimestampMs(
            timeMs = playheadMs,
            fps = fps,
            beatTimestampsMs = beatsMs,
            snapToBeats = lyricSnapToBeats.value,
            snapToFrames = lyricSnapToFrames.value,
        )
        val updated = LyricEngine.assignWordStart(lyricLines.value, lineId, wordId, snapped)
        lyricLines.value = updated
        // Auto-advance word focus to next word for rapid tap-syncing
        val line = updated.firstOrNull { it.id == lineId } ?: return
        val wordIdx = line.words.indexOfFirst { it.id == wordId }
        if (wordIdx in 0 until line.words.lastIndex) {
            selectedLyricWordId.value = line.words[wordIdx + 1].id
        }
    }

    fun assignWordEndAtPlayhead(
        lineId: String,
        wordId: String,
        playheadMs: Long,
        fps: Int,
        beatsMs: List<Long>,
    ) {
        val snapped = LyricEngine.snapTimestampMs(
            timeMs = playheadMs,
            fps = fps,
            beatTimestampsMs = beatsMs,
            snapToBeats = lyricSnapToBeats.value,
            snapToFrames = lyricSnapToFrames.value,
        )
        lyricLines.value = LyricEngine.assignWordEnd(lyricLines.value, lineId, wordId, snapped)
    }

    fun stepWordSelection(delta: Int) {
        val line = lyricStudioState.value.selectedLine ?: return
        if (line.words.isEmpty()) return
        val curIdx = line.words.indexOfFirst { it.id == selectedLyricWordId.value }.coerceAtLeast(0)
        val nextIdx = (curIdx + delta).coerceIn(0, line.words.lastIndex)
        selectedLyricWordId.value = line.words[nextIdx].id
    }

    fun adjustSelectedWordDuration(deltaMs: Long) {
        val line = lyricStudioState.value.selectedLine ?: return
        val word = lyricStudioState.value.selectedWord ?: return
        val currentDur = if (word.startMs != null && word.endMs != null) {
            (word.endMs - word.startMs)
        } else {
            350L
        }
        lyricLines.value = LyricEngine.adjustWordDuration(
            lines = lyricLines.value,
            lineId = line.id,
            wordId = word.id,
            newDurationMs = currentDur + deltaMs,
        )
    }

    fun distributeSelectedLineWordsAsEstimated(fallbackStartMs: Long) {
        val line = lyricStudioState.value.selectedLine ?: return
        lyricLines.value = LyricEngine.distributeWordTimingsAsEstimated(
            lines = lyricLines.value,
            lineId = line.id,
            fallbackStartMs = fallbackStartMs,
        )
        scope.launch {
            emitMessage("Estimated word timing applied (marked ESTIMATED — tap words to refine)")
        }
    }

    fun shiftLyricsOffset(deltaMs: Long, applyToAll: Boolean) {
        val lines = lyricLines.value
        val targets = if (applyToAll) {
            lines.map { it.id }.toSet()
        } else {
            setOfNotNull(lyricStudioState.value.selectedLine?.id)
        }
        lyricLines.value = LyricEngine.shiftLinesOffset(lines, targets, deltaMs)
    }

    fun resetLyricTiming(applyToAll: Boolean) {
        val lines = lyricLines.value
        val targets = if (applyToAll) {
            lines.map { it.id }.toSet()
        } else {
            setOfNotNull(lyricStudioState.value.selectedLine?.id)
        }
        lyricLines.value = LyricEngine.resetTiming(lines, targets)
    }

    fun setLyricSnapToFrames(enabled: Boolean) {
        lyricSnapToFrames.value = enabled
    }

    fun setLyricSnapToBeats(enabled: Boolean) {
        lyricSnapToBeats.value = enabled
    }

    fun setLyricKaraokeMode(mode: KaraokeHighlightMode) {
        lyricKaraokeMode.value = mode
    }

    fun setLyricStylePreset(preset: TextStylePreset) {
        lyricStylePreset.value = preset
    }

    fun setLyricUseInvertEffect(enabled: Boolean) {
        lyricUseInvertEffect.value = enabled
    }

    fun buildLyricClipRequests(fallbackStartMs: Long): List<TimedTextClipRequest> {
        val lines = lyricLines.value
        if (lines.isEmpty()) return emptyList()
        val template: TextOverlaySpec = if (lyricUseInvertEffect.value) {
            val basePreset = EffectsEngine.defaultTextSpec(lyricStylePreset.value)
            EffectsEngine.defaultInvertLyricSpec().copy(
                stylePreset = lyricStylePreset.value,
                fontScale = basePreset.fontScale,
                alignment = basePreset.alignment,
                offsetY = basePreset.offsetY,
                karaokeMode = lyricKaraokeMode.value,
            )
        } else {
            val basePreset = EffectsEngine.defaultTextSpec(lyricStylePreset.value)
            EffectsEngine.defaultKaraokeLyricSpec().copy(
                stylePreset = lyricStylePreset.value,
                fontScale = basePreset.fontScale,
                alignment = basePreset.alignment,
                offsetY = basePreset.offsetY,
                karaokeMode = lyricKaraokeMode.value,
            )
        }
        return LyricEngine.buildTimelineClipRequests(
            lines = lines,
            styleTemplate = template,
            fallbackStartMs = fallbackStartMs,
            sourceAssetId = selectedLyricAssetId.value,
        )
    }
}
