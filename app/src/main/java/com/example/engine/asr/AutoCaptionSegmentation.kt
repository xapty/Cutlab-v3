package com.example.engine.asr

import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.TextContentType
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TimedTextWord
import com.example.engine.text.UnifiedTextEngine
import com.example.engine.timeline.TimedTextClipRequest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class CaptionGenerationConfig(
    val maxCharsPerLine: Int = 34,
    val maxLinesPerCaption: Int = 2,
    val karaokeMode: KaraokeHighlightMode = KaraokeHighlightMode.ACTIVE_WORD,
    val clipStartOffsetMs: Long = 0L,
)

data class EditableCaptionCue(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val words: List<AsrWordToken> = emptyList(),
    val isTimingEstimated: Boolean = false,
)

/**
 * Pure-Kotlin segmentation & cue conversion pipeline turning ASR transcription output
 * into editable timeline caption cues and [TimedTextClipRequest] batches.
 *
 * Handles both space-delimited Latin scripts (Indonesian, English) and space-less CJK
 * scripts (Japanese Kanji, Hiragana, Katakana, punctuation) without dropping characters
 * or leaving stale word timestamps after overlap shifts.
 */
object AutoCaptionSegmentation {

    fun segmentTranscript(
        result: AsrTranscriptResult,
        config: CaptionGenerationConfig = CaptionGenerationConfig(),
    ): List<EditableCaptionCue> {
        if (result.segments.isEmpty()) return emptyList()
        val maxChars = config.maxCharsPerLine.coerceIn(14, 60)
        val maxCharsPerClip = (maxChars * config.maxLinesPerCaption.coerceIn(1, 3))
        val cues = mutableListOf<EditableCaptionCue>()

        for (seg in result.segments) {
            val cleanText = seg.text.trim()
            if (cleanText.isEmpty()) continue

            if (seg.words.isEmpty()) {
                // Segment-only ASR output: wrap text cleanly (including long/CJK lines) without inventing word timings
                val wrapped = wrapLines(cleanText, maxChars)
                val start = (seg.startMs + config.clipStartOffsetMs).coerceAtLeast(0L)
                val end = max(start + 200L, seg.endMs + config.clipStartOffsetMs)
                cues += EditableCaptionCue(
                    text = wrapped,
                    startMs = start,
                    endMs = end,
                    words = emptyList(),
                    isTimingEstimated = seg.isTimingEstimated,
                )
                continue
            }

            // Group real timestamped words into cue chunks honoring maxCharsPerClip and pause gaps
            val currentWords = mutableListOf<AsrWordToken>()
            var currentLen = 0

            fun flushCurrentCue() {
                if (currentWords.isEmpty()) return
                val cueStart = (currentWords.first().startMs + config.clipStartOffsetMs).coerceAtLeast(0L)
                val cueEnd = max(cueStart + 160L, currentWords.last().endMs + config.clipStartOffsetMs)
                val joined = joinTokensNaturally(currentWords.map { it.word })
                val wrapped = wrapLines(joined, maxChars)
                cues += EditableCaptionCue(
                    text = wrapped,
                    startMs = cueStart,
                    endMs = cueEnd,
                    words = currentWords.map { w ->
                        w.copy(
                            startMs = (w.startMs + config.clipStartOffsetMs).coerceAtLeast(0L),
                            endMs = (w.endMs + config.clipStartOffsetMs).coerceAtLeast(0L),
                        )
                    },
                    isTimingEstimated = currentWords.any { it.isEstimated },
                )
                currentWords.clear()
                currentLen = 0
            }

            for (word in seg.words) {
                val prev = currentWords.lastOrNull()
                val pauseGapMs = if (prev != null) word.startMs - prev.endMs else 0L
                val separatorCost = if (currentWords.isEmpty() || containsCjk(word.word)) 0 else 1
                val nextLen = currentLen + separatorCost + word.word.length
                if (currentWords.isNotEmpty() && (nextLen > maxCharsPerClip || pauseGapMs > 900L)) {
                    flushCurrentCue()
                }
                currentWords += word
                currentLen = if (currentWords.size == 1) word.word.length else currentLen + separatorCost + word.word.length
            }
            flushCurrentCue()
        }

        // Guarantee monotonic non-overlapping cue ordering AND shift word timestamps atomically!
        val ordered = mutableListOf<EditableCaptionCue>()
        for (cue in cues.sortedBy { it.startMs }) {
            val prev = ordered.lastOrNull()
            val adjustedStart = if (prev != null) max(cue.startMs, prev.endMs) else cue.startMs
            val shiftDelta = (adjustedStart - cue.startMs).coerceAtLeast(0L)
            val shiftedEnd = max(adjustedStart + 120L, cue.endMs + shiftDelta)
            val shiftedWords = if (shiftDelta == 0L || cue.words.isEmpty()) {
                cue.words
            } else {
                cue.words.map { w ->
                    val ws = (w.startMs + shiftDelta).coerceAtLeast(adjustedStart)
                    val we = max(ws + 40L, w.endMs + shiftDelta)
                    w.copy(startMs = ws, endMs = we)
                }
            }
            ordered += cue.copy(
                startMs = adjustedStart,
                endMs = shiftedEnd,
                words = shiftedWords,
            )
        }
        return ordered
    }

    fun buildClipRequests(
        cues: List<EditableCaptionCue>,
        styleTemplate: TextOverlaySpec,
        sourceLanguage: String?,
        sourceAssetId: String?,
        karaokeMode: KaraokeHighlightMode = styleTemplate.karaokeMode,
    ): List<TimedTextClipRequest> {
        return cues.mapNotNull { cue ->
            if (cue.text.isBlank()) return@mapNotNull null
            val durationMs = (cue.endMs - cue.startMs).coerceAtLeast(120L)
            val localWords = if (cue.words.isNotEmpty()) {
                UnifiedTextEngine.normalizeWords(
                    words = cue.words.map { w ->
                        TimedTextWord(
                            id = w.id,
                            text = w.word,
                            startMs = (w.startMs - cue.startMs).coerceAtLeast(0L),
                            endMs = min(durationMs, max(1L, w.endMs - cue.startMs)),
                            isEstimated = w.isEstimated,
                            confidence = w.confidence,
                        )
                    },
                    maxDurationMs = durationMs,
                )
            } else {
                emptyList()
            }

            TimedTextClipRequest(
                timelineStartMs = cue.startMs.coerceAtLeast(0L),
                durationMs = durationMs,
                spec = styleTemplate.copy(
                    text = cue.text,
                    contentType = TextContentType.CAPTION,
                    timedWords = localWords,
                    karaokeMode = if (localWords.isNotEmpty()) karaokeMode else KaraokeHighlightMode.NONE,
                    sourceLanguage = sourceLanguage,
                    sourceAssetId = sourceAssetId,
                    isTimingEstimated = cue.isTimingEstimated,
                ),
            )
        }
    }

    fun wrapLines(text: String, maxCharsPerLine: Int): String {
        val safeMax = maxCharsPerLine.coerceAtLeast(4)
        val rawTokens = UnifiedTextEngine.tokenizeWords(text)
        if (rawTokens.isEmpty()) return ""

        // Split oversized unspaced runs (such as continuous Japanese phrases) along punctuation or char width
        val expandedTokens = mutableListOf<String>()
        for (token in rawTokens) {
            if (token.length <= safeMax) {
                expandedTokens += token
            } else {
                expandedTokens += splitLongToken(token, safeMax)
            }
        }

        val lines = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        for (token in expandedTokens) {
            val isCjkChunk = containsCjk(token)
            val joinWithSpace = current.isNotEmpty() && !(isCjkChunk && containsCjk(current.toString()))
            val addedLen = token.length + if (joinWithSpace) 1 else 0
            if (current.isEmpty()) {
                current.append(token)
            } else if (current.length + addedLen <= safeMax) {
                if (joinWithSpace) current.append(' ')
                current.append(token)
            } else {
                lines += current
                current = StringBuilder(token)
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.joinToString("\n") { it.toString() }
    }

    private fun joinTokensNaturally(tokens: List<String>): String {
        if (tokens.isEmpty()) return ""
        val sb = StringBuilder()
        for (token in tokens) {
            if (sb.isEmpty()) {
                sb.append(token)
            } else if (containsCjk(token) && containsCjk(sb.toString())) {
                sb.append(token)
            } else {
                sb.append(' ').append(token)
            }
        }
        return sb.toString()
    }

    private fun splitLongToken(token: String, maxLen: Int): List<String> {
        val chunks = mutableListOf<String>()
        var idx = 0
        while (idx < token.length) {
            val hardEnd = min(token.length, idx + maxLen)
            if (hardEnd == token.length) {
                chunks += token.substring(idx)
                break
            }
            // Prefer breaking right after Japanese/CJK punctuation within the window
            var breakPos = -1
            for (scan in hardEnd - 1 downTo idx + (maxLen / 2).coerceAtLeast(1)) {
                val ch = token[scan]
                if (ch == '。' || ch == '、' || ch == '！' || ch == '？' || ch == ',' || ch == '.') {
                    breakPos = scan + 1
                    break
                }
            }
            val actualEnd = if (breakPos > idx) breakPos else hardEnd
            chunks += token.substring(idx, actualEnd)
            idx = actualEnd
        }
        return chunks
    }

    private fun containsCjk(text: String): Boolean = text.any { ch ->
        val code = ch.code
        code in 0x3000..0x30FF || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF
    }
}
