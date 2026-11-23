package com.example.engine.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Offline on-device speech recognition engine powered by `sherpa-onnx` + Multilingual Whisper ONNX
 * (`com.github.k2-fsa:sherpa-onnx`).
 *
 * Design guarantees:
 * - Decouples model files (`tiny` / `base` multilingual INT8) from requested language (`auto`, `id`, `en`, `ja`).
 * - Streams 16 kHz mono PCM from local media into bounded 28-second Whisper float windows (never loads whole long media in RAM).
 * - Enforces single-model lifecycle via [Mutex] + single-thread dispatcher (`limitedParallelism(1)`), releasing streams and recognizer in `finally`.
 * - Preserves full UTF-8 Unicode (including Japanese Kanji, Hiragana, Katakana, and CJK punctuation).
 * - Never fabricates word-level timestamps when Whisper output omits reliable token timestamps (`isTimingEstimated = true`).
 */
class SherpaWhisperSpeechRecognitionEngine : SpeechRecognitionEngine {

    override val engineDisplayName: String = "sherpa-onnx Whisper"

    private val recognizerMutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sherpaSingleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)

    override fun isRuntimeSupported(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        Class.forName("com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig")
        true
    }.getOrDefault(false)

    override suspend fun transcribeAudio(
        assetId: String,
        modelSpec: AsrModelSpec,
        modelDirectory: File,
        languageOption: AsrLanguageOption,
        streamMono16kPcm: suspend (onChunk: suspend (samples16k: ShortArray, chunkStartMs: Long) -> Unit) -> PcmStreamSummary,
        onProgress: (progress: Float, status: String) -> Unit,
    ): Result<AsrTranscriptResult> = recognizerMutex.withLock {
        withContext(sherpaSingleThreadDispatcher) {
            val resolvedFiles = resolveWhisperModelFiles(modelDirectory, modelSpec)
                ?: return@withContext Result.failure(
                    IllegalStateException("Offline Whisper model files for ${modelSpec.displayName} are missing or corrupt."),
                )

            var recognizer: OfflineRecognizer? = null
            try {
                onProgress(0.03f, "Loading ${modelSpec.displayName} model...")
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = resolvedFiles.encoderFile.absolutePath,
                    decoder = resolvedFiles.decoderFile.absolutePath,
                    language = languageOption.whisperLanguageParam,
                    task = "transcribe",
                    tailPaddings = DEFAULT_TAIL_PADDINGS,
                )
                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = resolvedFiles.tokensFile.absolutePath,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                    debug = false,
                    modelType = "whisper",
                )
                val recognizerConfig = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE_HZ,
                        featureDim = WHISPER_FEATURE_DIM,
                    ),
                    modelConfig = modelConfig,
                )
                recognizer = OfflineRecognizer(config = recognizerConfig)

                onProgress(0.10f, "Recognizing speech locally (${languageOption.displayName})...")

                val windowBuffer = FloatArray(MAX_WINDOW_SAMPLES)
                var windowFill = 0
                var windowStartSample = 0L
                var totalProcessedSamples = 0L
                val segments = mutableListOf<AsrTranscriptSegment>()
                var firstDetectedLang: String? = null

                fun decodeCurrentWindow(forceEndMs: Long?) {
                    if (windowFill < MIN_DECODE_SAMPLES) {
                        windowStartSample += windowFill
                        windowFill = 0
                        return
                    }
                    val samplesForDecode = if (windowFill == windowBuffer.size) {
                        windowBuffer
                    } else {
                        windowBuffer.copyOf(windowFill)
                    }
                    val windowStartMs = (windowStartSample * 1000L) / SAMPLE_RATE_HZ
                    val windowEndMs = forceEndMs ?: (((windowStartSample + windowFill) * 1000L) / SAMPLE_RATE_HZ)

                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samplesForDecode, SAMPLE_RATE_HZ)
                        recognizer.decode(stream)
                        val result = recognizer.getResult(stream)
                        val parsedLang = result.lang.trim().takeIf { it.isNotEmpty() }
                        if (firstDetectedLang == null && parsedLang != null) {
                            firstDetectedLang = parsedLang
                        }
                        val seg = buildSegmentFromWhisperResult(
                            text = result.text,
                            tokens = result.tokens,
                            timestamps = result.timestamps,
                            windowStartMs = windowStartMs,
                            windowEndMs = windowEndMs,
                        )
                        if (seg != null) {
                            segments += seg
                        }
                    } finally {
                        runCatching { stream.release() }
                    }

                    windowStartSample += windowFill
                    windowFill = 0
                }

                val summary = streamMono16kPcm { chunkShorts, _ ->
                    coroutineContext.ensureActive()
                    var srcOffset = 0
                    while (srcOffset < chunkShorts.size) {
                        coroutineContext.ensureActive()
                        val remainingInWindow = MAX_WINDOW_SAMPLES - windowFill
                        val toCopy = min(remainingInWindow, chunkShorts.size - srcOffset)
                        for (i in 0 until toCopy) {
                            windowBuffer[windowFill + i] = chunkShorts[srcOffset + i].toFloat() / 32768.0f
                        }
                        windowFill += toCopy
                        srcOffset += toCopy
                        totalProcessedSamples += toCopy

                        if (windowFill >= MAX_WINDOW_SAMPLES) {
                            decodeCurrentWindow(forceEndMs = null)
                            val secs = totalProcessedSamples / SAMPLE_RATE_HZ
                            onProgress(
                                (0.15f + (secs.coerceAtMost(300L) / 300f) * 0.70f).coerceIn(0.15f, 0.88f),
                                "Recognizing speech (${secs}s processed)...",
                            )
                        }
                    }
                }

                coroutineContext.ensureActive()
                if (!summary.hasAudioTrack) {
                    return@withContext Result.success(
                        AsrTranscriptResult(
                            languageCode = languageOption.code,
                            assetId = assetId,
                            segments = emptyList(),
                            hasAudioTrack = false,
                            isSilentOrEmpty = true,
                            engineName = engineDisplayName,
                            detectedLanguageCode = null,
                        ),
                    )
                }

                if (windowFill >= MIN_DECODE_SAMPLES) {
                    onProgress(0.92f, "Finalizing Whisper speech segment...")
                    decodeCurrentWindow(
                        forceEndMs = summary.totalDurationMs.takeIf { it > 0L },
                    )
                }

                onProgress(1.0f, "Transcription complete")
                val isSilent = summary.peakAmplitude < 0.002f || segments.isEmpty()
                val resolvedLanguage = if (languageOption == AsrLanguageOption.AUTO) {
                    firstDetectedLang ?: AsrLanguageOption.AUTO.code
                } else {
                    languageOption.code
                }

                Result.success(
                    AsrTranscriptResult(
                        languageCode = languageOption.code,
                        assetId = assetId,
                        segments = segments,
                        hasAudioTrack = true,
                        isSilentOrEmpty = isSilent,
                        engineName = engineDisplayName,
                        detectedLanguageCode = resolvedLanguage,
                    ),
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Result.failure(t)
            } finally {
                runCatching { recognizer?.release() }
            }
        }
    }

    internal fun resolveWhisperModelFiles(
        dir: File,
        spec: AsrModelSpec,
    ): ResolvedWhisperModelFiles? {
        if (!dir.exists() || !dir.isDirectory) return null
        val encoderName = spec.encoderFileName.ifBlank { "tiny-encoder.int8.onnx" }
        val decoderName = spec.decoderFileName.ifBlank { "tiny-decoder.int8.onnx" }
        val tokensName = spec.tokensFileName.ifBlank { "tiny-tokens.txt" }

        val candidates = buildList {
            add(dir)
            dir.listFiles()?.filter { it.isDirectory }?.forEach { add(it) }
        }
        for (root in candidates) {
            val encoder = File(root, encoderName)
            val decoder = File(root, decoderName)
            val tokens = File(root, tokensName)
            if (encoder.isFile && encoder.length() > AsrModelManager.MIN_ONNX_WEIGHT_BYTES &&
                decoder.isFile && decoder.length() > AsrModelManager.MIN_ONNX_WEIGHT_BYTES &&
                tokens.isFile && tokens.length() > AsrModelManager.MIN_TOKENS_BYTES
            ) {
                return ResolvedWhisperModelFiles(
                    encoderFile = encoder,
                    decoderFile = decoder,
                    tokensFile = tokens,
                )
            }
        }
        return null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val WHISPER_FEATURE_DIM = 80
        const val MAX_WINDOW_SECONDS = 28
        const val MAX_WINDOW_SAMPLES = SAMPLE_RATE_HZ * MAX_WINDOW_SECONDS
        const val MIN_DECODE_SAMPLES = 320 // 20 ms minimum
        const val DEFAULT_TAIL_PADDINGS = 1000

        /**
         * Converts a raw Whisper decode window output into an [AsrTranscriptSegment].
         *
         * Preserves Unicode / Japanese text verbatim and behaves honestly regarding timestamps:
         * - Only emits non-estimated word tokens when `tokens` and `timestamps` align cleanly and
         *   carry strictly non-negative, non-degenerate timestamps.
         * - Otherwise leaves `words = emptyList()` and sets `isTimingEstimated = true` at the
         *   decoded window level rather than fabricating fake exact word timestamps.
         */
        fun buildSegmentFromWhisperResult(
            text: String?,
            tokens: Array<String>?,
            timestamps: FloatArray?,
            windowStartMs: Long,
            windowEndMs: Long,
        ): AsrTranscriptSegment? {
            val cleanText = text?.trim().orEmpty()
            if (cleanText.isEmpty()) return null
            val safeWinStart = windowStartMs.coerceAtLeast(0L)
            val safeWinEnd = max(safeWinStart + 250L, windowEndMs)

            val hasAlignedTokenTimestamps = tokens != null &&
                timestamps != null &&
                tokens.isNotEmpty() &&
                tokens.size == timestamps.size &&
                timestamps.any { it > 0.001f }

            if (!hasAlignedTokenTimestamps) {
                return AsrTranscriptSegment(
                    text = cleanText,
                    startMs = safeWinStart,
                    endMs = safeWinEnd,
                    words = emptyList(),
                    isTimingEstimated = true,
                )
            }

            // Merge Whisper BPE / wordpiece tokens when real timestamps exist
            val words = mutableListOf<AsrWordToken>()
            val currentWord = StringBuilder()
            var currentStartMs = safeWinStart
            var currentEndMs = safeWinStart

            for (i in tokens.indices) {
                val rawToken = tokens[i]
                if (rawToken.isEmpty() || rawToken.startsWith("<|")) continue
                val tsMs = safeWinStart + (timestamps[i].coerceAtLeast(0f) * 1000.0).roundToLong()
                val startsNewWord = rawToken.startsWith(" ") || rawToken.startsWith("\u2581") || isCjkToken(rawToken.trim())
                val trimmed = rawToken.replace("\u2581", "").trim()
                if (trimmed.isEmpty()) continue

                if (startsNewWord && currentWord.isNotEmpty()) {
                    val wordEnd = max(currentStartMs + 40L, max(tsMs, currentEndMs))
                    words += AsrWordToken(
                        id = UUID.randomUUID().toString(),
                        word = currentWord.toString(),
                        startMs = currentStartMs,
                        endMs = min(safeWinEnd, wordEnd),
                        confidence = 1.0f,
                        isEstimated = false,
                    )
                    currentWord.clear()
                    currentStartMs = tsMs
                    currentEndMs = tsMs + 120L
                }
                if (currentWord.isEmpty()) {
                    currentStartMs = tsMs
                }
                currentWord.append(trimmed)
                currentEndMs = max(currentStartMs + 60L, tsMs + 160L)
            }
            if (currentWord.isNotEmpty()) {
                words += AsrWordToken(
                    id = UUID.randomUUID().toString(),
                    word = currentWord.toString(),
                    startMs = currentStartMs,
                    endMs = min(safeWinEnd, max(currentStartMs + 60L, currentEndMs)),
                    confidence = 1.0f,
                    isEstimated = false,
                )
            }

            return if (words.isNotEmpty()) {
                AsrTranscriptSegment(
                    text = cleanText,
                    startMs = max(safeWinStart, words.first().startMs),
                    endMs = min(safeWinEnd, max(words.first().startMs + 200L, words.last().endMs)),
                    words = words,
                    isTimingEstimated = false,
                )
            } else {
                AsrTranscriptSegment(
                    text = cleanText,
                    startMs = safeWinStart,
                    endMs = safeWinEnd,
                    words = emptyList(),
                    isTimingEstimated = true,
                )
            }
        }

        private fun isCjkToken(text: String): Boolean {
            if (text.isEmpty()) return false
            val cp = text.codePointAt(0)
            return cp in 0x3040..0x30FF || // Hiragana & Katakana
                cp in 0x4E00..0x9FFF ||    // CJK Unified Ideographs
                cp in 0x3000..0x303F       // CJK punctuation
        }
    }
}
