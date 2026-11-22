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
 */
object AutoCaptionSegmentation {

    fun segmentTranscript(
        result: AsrTranscriptResult,
        config: CaptionGenerationConfig = CaptionGenerationConfig(),
    ): List<EditableCaptionCue> {
        if (result.segments.isEmpty()) return emptyList()
        val maxCharsPerClip = (config.maxCharsPerLine.coerceIn(14, 60) * config.maxLinesPerCaption.coerceIn(1, 3))
        val cues = mutableListOf<EditableCaptionCue>()

        for (seg in result.segments) {
            val cleanText = seg.text.trim()
            if (cleanText.isEmpty()) continue

            if (seg.words.isEmpty()) {
                // Segment-only ASR output: wrap text cleanly without fabricating fake word timings
                val wrapped = wrapLines(cleanText, config.maxCharsPerLine.coerceIn(14, 60))
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
                val joined = currentWords.joinToString(" ") { it.word }
                val wrapped = wrapLines(joined, config.maxCharsPerLine.coerceIn(14, 60))
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
                val nextLen = if (currentWords.isEmpty()) word.word.length else currentLen + 1 + word.word.length
                if (currentWords.isNotEmpty() && (nextLen > maxCharsPerClip || pauseGapMs > 900L)) {
                    flushCurrentCue()
                }
                currentWords += word
                currentLen = if (currentWords.size == 1) word.word.length else currentLen + 1 + word.word.length
            }
            flushCurrentCue()
        }

        // Guarantee monotonic non-overlapping cue ordering
        val ordered = mutableListOf<EditableCaptionCue>()
        for (cue in cues.sortedBy { it.startMs }) {
            val prev = ordered.lastOrNull()
            val adjustedStart = if (prev != null) max(cue.startMs, prev.endMs) else cue.startMs
            val adjustedEnd = max(adjustedStart + 120L, cue.endMs)
            ordered += cue.copy(startMs = adjustedStart, endMs = adjustedEnd)
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
        val words = UnifiedTextEngine.tokenizeWords(text)
        if (words.isEmpty()) return ""
        val lines = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        for (w in words) {
            if (current.isEmpty()) {
                current.append(w)
            } else if (current.length + 1 + w.length <= maxCharsPerLine) {
                current.append(' ').append(w)
            } else {
                lines += current
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.joinToString("\n") { it.toString() }
    }
}
