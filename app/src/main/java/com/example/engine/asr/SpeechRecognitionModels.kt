package com.example.engine.asr

import java.io.File
import java.util.UUID

enum class AsrLanguageOption(
    val code: String,
    val displayName: String,
    val nativeLabel: String,
    val whisperLanguageParam: String,
) {
    AUTO(
        code = "auto",
        displayName = "Auto Detect",
        nativeLabel = "Auto Detect",
        whisperLanguageParam = "",
    ),
    INDONESIAN(
        code = "id",
        displayName = "Indonesian",
        nativeLabel = "Bahasa Indonesia",
        whisperLanguageParam = "id",
    ),
    ENGLISH(
        code = "en",
        displayName = "English",
        nativeLabel = "English",
        whisperLanguageParam = "en",
    ),
    JAPANESE(
        code = "ja",
        displayName = "Japanese",
        nativeLabel = "日本語",
        whisperLanguageParam = "ja",
    ),
    ;

    companion object {
        fun fromCode(code: String?): AsrLanguageOption =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
    }
}

data class AsrModelSpec(
    val id: String,
    val displayName: String,
    val languageCode: String,
    val languageLabel: String,
    val downloadUrl: String,
    val approximateSizeMb: Int,
    val note: String,
    val archiveType: String = "tar.bz2",
    val encoderFileName: String = "",
    val decoderFileName: String = "",
    val tokensFileName: String = "",
    val isDefault: Boolean = false,
)

object AsrModelCatalog {
    val MODELS: List<AsrModelSpec> = listOf(
        AsrModelSpec(
            id = "whisper-tiny-multilingual-int8",
            displayName = "Whisper Tiny (Multilingual INT8)",
            languageCode = "multilingual",
            languageLabel = "Multilingual (ID · EN · JA)",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
            approximateSizeMb = 111,
            note = "Fast offline sherpa-onnx Whisper INT8 model supporting Auto, ID, EN, JA & 99+ languages",
            archiveType = "tar.bz2",
            encoderFileName = "tiny-encoder.int8.onnx",
            decoderFileName = "tiny-decoder.int8.onnx",
            tokensFileName = "tiny-tokens.txt",
            isDefault = true,
        ),
        AsrModelSpec(
            id = "whisper-base-multilingual-int8",
            displayName = "Whisper Base (Multilingual INT8)",
            languageCode = "multilingual",
            languageLabel = "Multilingual Higher Accuracy",
            downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
            approximateSizeMb = 198,
            note = "Higher-accuracy offline sherpa-onnx Whisper Base INT8 model for challenging audio & accents",
            archiveType = "tar.bz2",
            encoderFileName = "base-encoder.int8.onnx",
            decoderFileName = "base-decoder.int8.onnx",
            tokensFileName = "base-tokens.txt",
            isDefault = false,
        ),
    )

    fun defaultModel(): AsrModelSpec = MODELS.firstOrNull { it.isDefault } ?: MODELS.first()

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
    val detectedLanguageCode: String? = null,
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
        languageOption: AsrLanguageOption = AsrLanguageOption.AUTO,
        streamMono16kPcm: suspend (onChunk: suspend (samples16k: ShortArray, chunkStartMs: Long) -> Unit) -> PcmStreamSummary,
        onProgress: (progress: Float, status: String) -> Unit,
    ): Result<AsrTranscriptResult>
}
