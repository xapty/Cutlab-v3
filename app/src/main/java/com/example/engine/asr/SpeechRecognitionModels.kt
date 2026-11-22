package com.example.engine.asr

import java.io.File
import java.util.UUID

data class AsrModelSpec(
    val id: String,
    val displayName: String,
    val languageCode: String,
    val languageLabel: String,
    val downloadUrl: String,
    val approximateSizeMb: Int,
    val note: String,
)

object AsrModelCatalog {
    val MODELS: List<AsrModelSpec> = listOf(
        AsrModelSpec(
            id = "vosk-model-small-en-us-0.15",
            displayName = "English (US) Compact",
            languageCode = "en",
            languageLabel = "English (US)",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            approximateSizeMb = 40,
            note = "Offline Vosk speech model with word timestamps",
        ),
        AsrModelSpec(
            id = "vosk-model-small-es-0.42",
            displayName = "Spanish Compact",
            languageCode = "es",
            languageLabel = "Español",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip",
            approximateSizeMb = 39,
            note = "Offline Vosk speech model with word timestamps",
        ),
        AsrModelSpec(
            id = "vosk-model-small-fr-0.22",
            displayName = "French Compact",
            languageCode = "fr",
            languageLabel = "Français",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip",
            approximateSizeMb = 41,
            note = "Offline Vosk speech model with word timestamps",
        ),
        AsrModelSpec(
            id = "vosk-model-small-de-0.15",
            displayName = "German Compact",
            languageCode = "de",
            languageLabel = "Deutsch",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
            approximateSizeMb = 45,
            note = "Offline Vosk speech model with word timestamps",
        ),
        AsrModelSpec(
            id = "vosk-model-small-ja-0.22",
            displayName = "Japanese Compact",
            languageCode = "ja",
            languageLabel = "日本語",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
            approximateSizeMb = 48,
            note = "Offline Vosk speech model with word timestamps",
        ),
    )

    fun defaultModel(): AsrModelSpec = MODELS.first()

    fun findById(id: String): AsrModelSpec? = MODELS.firstOrNull { it.id == id }
}

sealed interface AsrModelAvailability {
    data object NotInstalled : AsrModelAvailability
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AsrModelAvailability
    data class Ready(
        val modelDirPath: String,
        val diskBytes: Long,
    ) : AsrModelAvailability
    data class Error(val message: String) : AsrModelAvailability
}

data class AsrWordToken(
    val id: String = UUID.randomUUID().toString(),
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 1.0f,
    val isEstimated: Boolean = false,
)

data class AsrTranscriptSegment(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<AsrWordToken> = emptyList(),
    val isTimingEstimated: Boolean = false,
)

data class AsrTranscriptResult(
    val languageCode: String,
    val assetId: String,
    val segments: List<AsrTranscriptSegment>,
    val hasAudioTrack: Boolean,
    val isSilentOrEmpty: Boolean,
    val engineName: String,
)

data class PcmStreamSummary(
    val chunksProcessed: Int,
    val totalSamples16k: Long,
    val totalDurationMs: Long,
    val hasAudioTrack: Boolean,
    val peakAmplitude: Float,
)

interface SpeechRecognitionEngine {
    val engineDisplayName: String
    fun isRuntimeSupported(): Boolean

    suspend fun transcribeAudio(
        assetId: String,
        modelSpec: AsrModelSpec,
        modelDirectory: File,
        streamMono16kPcm: suspend (onChunk: suspend (samples16k: ShortArray, chunkStartMs: Long) -> Unit) -> PcmStreamSummary,
        onProgress: (progress: Float, status: String) -> Unit,
    ): Result<AsrTranscriptResult>
}
