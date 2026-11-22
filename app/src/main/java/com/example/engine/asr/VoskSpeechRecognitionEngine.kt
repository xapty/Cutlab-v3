package com.example.engine.asr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToLong

@Serializable
private data class VoskWordJson(
    val conf: Float = 1.0f,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val word: String = "",
)

@Serializable
private data class VoskResultJson(
    val result: List<VoskWordJson> = emptyList(),
    val text: String = "",
)

/**
 * Real offline on-device speech recognition engine powered by Vosk (`com.alphacephei:vosk-android`).
 *
 * - Runs completely on-device against 16 kHz mono PCM chunks decoded from local media.
 * - Uses a single-threaded dispatcher for native Vosk Recognizer calls per Vosk thread-safety rules.
 * - Produces word-level timestamps via `Recognizer.setWords(true)`.
 * - Fails explicitly if the model or native runtime is unavailable (never fabricates transcripts).
 */
class VoskSpeechRecognitionEngine : SpeechRecognitionEngine {

    override val engineDisplayName: String = "Vosk Offline ASR"

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val voskSingleThreadDispatcher = Dispatchers.IO.limitedParallelism(1)

    override fun isRuntimeSupported(): Boolean = runCatching {
        Class.forName("org.vosk.Recognizer")
        Class.forName("org.vosk.Model")
        true
    }.getOrDefault(false)

    override suspend fun transcribeAudio(
        assetId: String,
        modelSpec: AsrModelSpec,
        modelDirectory: File,
        streamMono16kPcm: suspend (onChunk: suspend (samples16k: ShortArray, chunkStartMs: Long) -> Unit) -> PcmStreamSummary,
        onProgress: (progress: Float, status: String) -> Unit,
    ): Result<AsrTranscriptResult> = withContext(voskSingleThreadDispatcher) {
        if (!modelDirectory.exists() || !modelDirectory.isDirectory) {
            return@withContext Result.failure(
                IllegalStateException("Offline model for ${modelSpec.languageLabel} is not installed."),
            )
        }

        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            onProgress(0.02f, "Loading ${modelSpec.displayName} offline model...")
            model = Model(modelDirectory.absolutePath)
            recognizer = Recognizer(model, StreamingMono16kResampler.TARGET_SAMPLE_RATE_HZ.toFloat()).apply {
                setWords(true)
            }

            val segments = mutableListOf<AsrTranscriptSegment>()
            var lastSegmentEndMs = 0L

            onProgress(0.08f, "Extracting & transcribing speech locally...")
            val summary = streamMono16kPcm { samples16k, chunkStartMs ->
                coroutineContext.ensureActive()
                if (samples16k.isEmpty()) return@streamMono16kPcm

                val finalizedBlock = recognizer.acceptWaveForm(samples16k, samples16k.size)
                if (finalizedBlock) {
                    val parsed = parseVoskBlock(
                        rawJson = recognizer.result,
                        fallbackStartMs = lastSegmentEndMs.coerceAtLeast(chunkStartMs),
                    )
                    if (parsed != null) {
                        segments += parsed
                        lastSegmentEndMs = parsed.endMs
                    }
                }
                val secs = (chunkStartMs / 1000L)
                onProgress(
                    0.10f,
                    "Transcribing audio locally (${secs}s processed)...",
                )
            }

            coroutineContext.ensureActive()
            if (!summary.hasAudioTrack) {
                return@withContext Result.success(
                    AsrTranscriptResult(
                        languageCode = modelSpec.languageCode,
                        assetId = assetId,
                        segments = emptyList(),
                        hasAudioTrack = false,
                        isSilentOrEmpty = true,
                        engineName = engineDisplayName,
                    ),
                )
            }

            val finalBlock = parseVoskBlock(
                rawJson = recognizer.finalResult,
                fallbackStartMs = lastSegmentEndMs,
            )
            if (finalBlock != null) {
                segments += finalBlock
            }

            onProgress(1.0f, "Transcription complete")
            val isSilent = summary.peakAmplitude < 0.002f || segments.isEmpty()
            Result.success(
                AsrTranscriptResult(
                    languageCode = modelSpec.languageCode,
                    assetId = assetId,
                    segments = segments,
                    hasAudioTrack = true,
                    isSilentOrEmpty = isSilent,
                    engineName = engineDisplayName,
                ),
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result.failure(t)
        } finally {
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
        }
    }

    internal fun parseVoskBlock(
        rawJson: String?,
        fallbackStartMs: Long,
    ): AsrTranscriptSegment? {
        if (rawJson.isNullOrBlank()) return null
        val decoded = runCatching { json.decodeFromString<VoskResultJson>(rawJson) }.getOrNull() ?: return null
        val cleanText = decoded.text.trim()
        if (cleanText.isEmpty() && decoded.result.isEmpty()) return null

        val words = decoded.result
            .filter { it.word.isNotBlank() }
            .map { w ->
                val startMs = (w.start * 1_000.0).roundToLong().coerceAtLeast(0L)
                val endMs = max(startMs + 40L, (w.end * 1_000.0).roundToLong())
                AsrWordToken(
                    id = UUID.randomUUID().toString(),
                    word = w.word.trim(),
                    startMs = startMs,
                    endMs = endMs,
                    confidence = w.conf.coerceIn(0f, 1f),
                    isEstimated = false,
                )
            }

        return if (words.isNotEmpty()) {
            val segStart = words.first().startMs
            val segEnd = max(segStart + 100L, words.last().endMs)
            val resolvedText = cleanText.ifBlank { words.joinToString(" ") { it.word } }
            AsrTranscriptSegment(
                text = resolvedText,
                startMs = segStart,
                endMs = segEnd,
                words = words,
                isTimingEstimated = false,
            )
        } else {
            val segStart = fallbackStartMs.coerceAtLeast(0L)
            val estDur = (cleanText.split(Regex("\\s+")).size * 380L).coerceIn(600L, 6_000L)
            AsrTranscriptSegment(
                text = cleanText,
                startMs = segStart,
                endMs = segStart + estDur,
                words = emptyList(),
                isTimingEstimated = true,
            )
        }
    }
}
