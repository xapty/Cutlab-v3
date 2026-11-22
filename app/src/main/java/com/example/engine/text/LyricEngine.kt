package com.example.engine.text

import com.example.core.common.TimelineTimebase
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.TextContentType
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TimedTextWord
import com.example.engine.timeline.TimedTextClipRequest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class LyricWordDraft(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val isEstimated: Boolean = false,
) {
    val isTimed: Boolean
        get() = startMs != null && endMs != null && endMs > startMs
}

data class LyricLineDraft(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val words: List<LyricWordDraft> = UnifiedTextEngine.tokenizeWords(text).map { LyricWordDraft(text = it) },
    val isEstimated: Boolean = false,
) {
    val isTimed: Boolean
        get() = startMs != null && endMs != null && endMs > startMs

    val hasWordTimings: Boolean
        get() = words.isNotEmpty() && words.all { it.isTimed }
}

/**
 * Pure-Kotlin Manual Lyric Editor & Synchronization Engine.
 * Supports plain-text / .txt lyric parsing, line split/merge/reorder, playhead tap
 * assignment, beat/frame snapping, offset shifting, explicitly labeled estimated
 * word distribution, and compilation into timeline TEXT clips.
 */
object LyricEngine {

    private val lrcLineRegex = Regex("""^((?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?])+)\s*(.*)$""")
    private val lrcTagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseLyricsText(rawText: String): List<LyricLineDraft> {
        val rawLines = rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (rawLines.isEmpty()) return emptyList()

        val parsed = rawLines.mapNotNull { line ->
            val lrcMatch = lrcLineRegex.matchEntire(line)
            if (lrcMatch != null) {
                val tags = lrcMatch.groupValues[1]
                val body = lrcMatch.groupValues[2].trim()
                if (body.isEmpty()) return@mapNotNull null
                val firstTag = lrcTagRegex.find(tags)
                val tsMs = firstTag?.let { m ->
                    val mins = m.groupValues[1].toLongOrNull() ?: 0L
                    val secs = m.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = m.groupValues[3]
                    val fracMs = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    mins * 60_000L + secs * 1_000L + fracMs
                }
                LyricLineDraft(
                    text = body,
                    startMs = tsMs,
                    endMs = null,
                    words = UnifiedTextEngine.tokenizeWords(body).map { LyricWordDraft(text = it) },
                )
            } else {
                LyricLineDraft(
                    text = line,
                    words = UnifiedTextEngine.tokenizeWords(line).map { LyricWordDraft(text = it) },
                )
            }
        }

        // Fill endMs for LRC timestamped lines using next line start (or +3000ms for last)
        return parsed.mapIndexed { idx, item ->
            if (item.startMs != null && item.endMs == null) {
                val nextStart = parsed.getOrNull(idx + 1)?.startMs
                val endMs = if (nextStart != null && nextStart > item.startMs) {
                    nextStart
                } else {
                    item.startMs + 3_000L
                }
                item.copy(endMs = endMs)
            } else {
                item
            }
        }
    }

    fun editLineText(
        lines: List<LyricLineDraft>,
        lineId: String,
        newText: String,
    ): List<LyricLineDraft> {
        val cleaned = newText.trim()
        val tokens = UnifiedTextEngine.tokenizeWords(cleaned)
        return lines.map { line ->
            if (line.id != lineId) {
                line
            } else {
                val updatedWords = tokens.mapIndexed { idx, token ->
                    val existing = line.words.getOrNull(idx)
                    if (existing != null && existing.text == token) {
                        existing
                    } else {
                        LyricWordDraft(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            text = token,
                            startMs = existing?.startMs,
                            endMs = existing?.endMs,
                            isEstimated = existing?.isEstimated ?: false,
                        )
                    }
                }
                line.copy(text = cleaned, words = updatedWords)
            }
        }
    }

    fun splitLine(
        lines: List<LyricLineDraft>,
        lineId: String,
        splitAfterWordIndex: Int,
    ): List<LyricLineDraft> {
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx < 0) return lines
        val line = lines[idx]
        if (line.words.size < 2) return lines
        val splitIdx = splitAfterWordIndex.coerceIn(0, line.words.lastIndex - 1)
        val firstWords = line.words.subList(0, splitIdx + 1)
        val secondWords = line.words.subList(splitIdx + 1, line.words.size)

        val splitBoundaryMs = firstWords.lastOrNull()?.endMs
            ?: secondWords.firstOrNull()?.startMs
            ?: if (line.startMs != null && line.endMs != null) {
                line.startMs + ((line.endMs - line.startMs) / 2L)
            } else null

        val firstLine = LyricLineDraft(
            text = firstWords.joinToString(" ") { it.text },
            startMs = line.startMs,
            endMs = splitBoundaryMs?.let { max(line.startMs ?: 0L, it) },
            words = firstWords,
            isEstimated = line.isEstimated,
        )
        val secondLine = LyricLineDraft(
            text = secondWords.joinToString(" ") { it.text },
            startMs = splitBoundaryMs,
            endMs = line.endMs?.let { max((splitBoundaryMs ?: 0L) + 100L, it) },
            words = secondWords,
            isEstimated = line.isEstimated,
        )
        return lines.toMutableList().apply {
            removeAt(idx)
            add(idx, secondLine)
            add(idx, firstLine)
        }
    }

    fun mergeWithNext(
        lines: List<LyricLineDraft>,
        lineId: String,
    ): List<LyricLineDraft> {
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx < 0 || idx >= lines.lastIndex) return lines
        val first = lines[idx]
        val second = lines[idx + 1]
        val mergedWords = first.words + second.words
        val mergedStart = listOfNotNull(first.startMs, second.startMs).minOrNull()
        val mergedEnd = listOfNotNull(first.endMs, second.endMs).maxOrNull()
        val merged = LyricLineDraft(
            id = first.id,
            text = mergedWords.joinToString(" ") { it.text }.ifBlank { "${first.text} ${second.text}".trim() },
            startMs = mergedStart,
            endMs = mergedEnd,
            words = mergedWords,
            isEstimated = first.isEstimated || second.isEstimated,
        )
        return lines.toMutableList().apply {
            removeAt(idx + 1)
            set(idx, merged)
        }
    }

    fun moveLine(
        lines: List<LyricLineDraft>,
        lineId: String,
        delta: Int,
    ): List<LyricLineDraft> {
        val idx = lines.indexOfFirst { it.id == lineId }
        if (idx < 0) return lines
        val targetIdx = (idx + delta).coerceIn(0, lines.lastIndex)
        if (targetIdx == idx) return lines
        return lines.toMutableList().apply {
            val moved = removeAt(idx)
            add(targetIdx, moved)
        }
    }

    fun deleteLine(
        lines: List<LyricLineDraft>,
        lineId: String,
    ): List<LyricLineDraft> = lines.filterNot { it.id == lineId }

    fun assignLineStart(
        lines: List<LyricLineDraft>,
        lineId: String,
        playheadMs: Long,
        defaultDurationMs: Long = 2_400L,
    ): List<LyricLineDraft> {
        val safeStart = playheadMs.coerceAtLeast(0L)
        return lines.map { line ->
            if (line.id != lineId) {
                line
            } else {
                val nextEnd = when {
                    line.endMs != null && line.endMs > safeStart + 100L -> line.endMs
                    else -> safeStart + defaultDurationMs.coerceAtLeast(300L)
                }
                line.copy(startMs = safeStart, endMs = nextEnd, isEstimated = false)
            }
        }
    }

    fun assignLineEnd(
        lines: List<LyricLineDraft>,
        lineId: String,
        playheadMs: Long,
    ): List<LyricLineDraft> {
        val safeEnd = playheadMs.coerceAtLeast(100L)
        return lines.map { line ->
            if (line.id != lineId) {
                line
            } else {
                val safeStart = (line.startMs ?: (safeEnd - 2_000L).coerceAtLeast(0L)).coerceAtMost(safeEnd - 100L)
                line.copy(startMs = safeStart, endMs = safeEnd, isEstimated = false)
            }
        }
    }

    fun assignWordStart(
        lines: List<LyricLineDraft>,
        lineId: String,
        wordId: String,
        playheadMs: Long,
        defaultWordDurationMs: Long = 350L,
    ): List<LyricLineDraft> {
        val safeStart = playheadMs.coerceAtLeast(0L)
        return lines.map { line ->
            if (line.id != lineId) return@map line
            val wordIdx = line.words.indexOfFirst { it.id == wordId }
            if (wordIdx < 0) return@map line

            val updatedWords = line.words.mapIndexed { idx, word ->
                when {
                    idx == wordIdx -> {
                        val nextEnd = word.endMs?.takeIf { it > safeStart + 40L }
                            ?: (safeStart + defaultWordDurationMs)
                        word.copy(startMs = safeStart, endMs = nextEnd, isEstimated = false)
                    }
                    // Auto-close immediately preceding word if user is tapping sequentially
                    idx == wordIdx - 1 && word.startMs != null && safeStart > word.startMs + 40L && word.isEstimated -> {
                        word.copy(endMs = safeStart)
                    }
                    else -> word
                }
            }
            val nextLineStart = listOfNotNull(line.startMs, safeStart).minOrNull()
            val maxWordEnd = updatedWords.mapNotNull { it.endMs }.maxOrNull()
            val nextLineEnd = listOfNotNull(line.endMs, maxWordEnd).maxOrNull()
            line.copy(
                startMs = nextLineStart,
                endMs = nextLineEnd,
                words = updatedWords,
                isEstimated = updatedWords.any { it.isEstimated },
            )
        }
    }

    fun assignWordEnd(
        lines: List<LyricLineDraft>,
        lineId: String,
        wordId: String,
        playheadMs: Long,
    ): List<LyricLineDraft> {
        val safeEnd = playheadMs.coerceAtLeast(40L)
        return lines.map { line ->
            if (line.id != lineId) return@map line
            val updatedWords = line.words.map { word ->
                if (word.id != wordId) {
                    word
                } else {
                    val safeStart = (word.startMs ?: (safeEnd - 300L).coerceAtLeast(0L))
                        .coerceAtMost(safeEnd - 40L)
                    word.copy(startMs = safeStart, endMs = safeEnd, isEstimated = false)
                }
            }
            val nextLineStart = listOfNotNull(line.startMs, updatedWords.mapNotNull { it.startMs }.minOrNull()).minOrNull()
            val nextLineEnd = listOfNotNull(line.endMs, safeEnd).maxOrNull()
            line.copy(
                startMs = nextLineStart,
                endMs = nextLineEnd,
                words = updatedWords,
                isEstimated = updatedWords.any { it.isEstimated },
            )
        }
    }

    fun adjustWordDuration(
        lines: List<LyricLineDraft>,
        lineId: String,
        wordId: String,
        newDurationMs: Long,
    ): List<LyricLineDraft> {
        val safeDur = newDurationMs.coerceAtLeast(UnifiedTextEngine.MIN_WORD_DURATION_MS)
        return lines.map { line ->
            if (line.id != lineId) return@map line
            val baseStart = line.startMs ?: 0L
            val updatedWords = line.words.map { word ->
                if (word.id != wordId) {
                    word
                } else {
                    val wStart = word.startMs ?: baseStart
                    word.copy(
                        startMs = wStart,
                        endMs = wStart + safeDur,
                        isEstimated = false,
                    )
                }
            }
            val maxWordEnd = updatedWords.mapNotNull { it.endMs }.maxOrNull()
            line.copy(
                startMs = line.startMs ?: baseStart,
                endMs = maxOf(line.endMs ?: 0L, maxWordEnd ?: 0L).takeIf { it > 0L },
                words = updatedWords,
                isEstimated = updatedWords.any { it.isEstimated },
            )
        }
    }

    /**
     * Distributes word timings proportionally by character length across the line's duration.
     * Explicitly marks all resulting words AND the line with [isEstimated] = true.
     */
    fun distributeWordTimingsAsEstimated(
        lines: List<LyricLineDraft>,
        lineId: String,
        fallbackStartMs: Long = 0L,
        fallbackDurationMs: Long = 2_400L,
    ): List<LyricLineDraft> {
        return lines.map { line ->
            if (line.id != lineId) return@map line
            val start = (line.startMs ?: fallbackStartMs).coerceAtLeast(0L)
            val end = (line.endMs?.takeIf { it > start + 100L } ?: (start + fallbackDurationMs))
            val est = UnifiedTextEngine.estimateUniformWordTimings(
                text = line.text,
                durationMs = end - start,
                startOffsetMs = start,
            )
            val wordDrafts = est.map { w ->
                LyricWordDraft(
                    id = w.id,
                    text = w.text,
                    startMs = w.startMs,
                    endMs = w.endMs,
                    isEstimated = true,
                )
            }
            line.copy(
                startMs = start,
                endMs = end,
                words = wordDrafts,
                isEstimated = true,
            )
        }
    }

    fun shiftLinesOffset(
        lines: List<LyricLineDraft>,
        targetLineIds: Set<String>,
        deltaMs: Long,
    ): List<LyricLineDraft> {
        if (targetLineIds.isEmpty() || deltaMs == 0L) return lines
        return lines.map { line ->
            if (line.id !in targetLineIds) return@map line
            val shiftedStart = line.startMs?.let { (it + deltaMs).coerceAtLeast(0L) }
            val actualDelta = if (line.startMs != null && shiftedStart != null) shiftedStart - line.startMs else deltaMs
            val shiftedEnd = line.endMs?.let { max((shiftedStart ?: 0L) + 100L, it + actualDelta) }
            val shiftedWords = line.words.map { word ->
                val wStart = word.startMs?.let { (it + actualDelta).coerceAtLeast(0L) }
                val wEnd = word.endMs?.let { max((wStart ?: 0L) + 40L, it + actualDelta) }
                word.copy(startMs = wStart, endMs = wEnd)
            }
            line.copy(startMs = shiftedStart, endMs = shiftedEnd, words = shiftedWords)
        }
    }

    fun resetTiming(
        lines: List<LyricLineDraft>,
        targetLineIds: Set<String>,
    ): List<LyricLineDraft> {
        if (targetLineIds.isEmpty()) return lines
        return lines.map { line ->
            if (line.id !in targetLineIds) return@map line
            line.copy(
                startMs = null,
                endMs = null,
                isEstimated = false,
                words = line.words.map { w ->
                    w.copy(startMs = null, endMs = null, isEstimated = false)
                },
            )
        }
    }

    fun snapTimestampMs(
        timeMs: Long,
        fps: Int,
        beatTimestampsMs: List<Long> = emptyList(),
        snapToBeats: Boolean = false,
        snapToFrames: Boolean = true,
        beatSnapWindowMs: Long = 140L,
    ): Long {
        val safeMs = timeMs.coerceAtLeast(0L)
        if (snapToBeats && beatTimestampsMs.isNotEmpty()) {
            val nearestBeat = beatTimestampsMs.minByOrNull { abs(it - safeMs) }
            if (nearestBeat != null && abs(nearestBeat - safeMs) <= beatSnapWindowMs) {
                return if (snapToFrames) snapToProjectFrame(nearestBeat, fps) else nearestBeat
            }
        }
        return if (snapToFrames) snapToProjectFrame(safeMs, fps) else safeMs
    }

    fun snapToProjectFrame(timeMs: Long, fps: Int): Long {
        val nearestFrame = TimelineTimebase.millisToNearestFrame(timeMs.coerceAtLeast(0L), fps)
        return TimelineTimebase.frameToMillis(nearestFrame, fps)
    }

    /**
     * Converts synchronized lyric lines into clip-local [TimedTextClipRequest]s for
     * undoable insertion into a timeline TEXT track.
     */
    fun buildTimelineClipRequests(
        lines: List<LyricLineDraft>,
        styleTemplate: TextOverlaySpec,
        fallbackStartMs: Long = 0L,
        defaultLineDurationMs: Long = 2_400L,
        sourceAssetId: String? = null,
    ): List<TimedTextClipRequest> {
        if (lines.isEmpty()) return emptyList()
        var cursorMs = fallbackStartMs.coerceAtLeast(0L)
        return lines.mapNotNull { line ->
            if (line.text.isBlank()) return@mapNotNull null
            val absStart = (line.startMs ?: cursorMs).coerceAtLeast(0L)
            val absEnd = (line.endMs?.takeIf { it > absStart + 100L }
                ?: (absStart + defaultLineDurationMs))
            val durationMs = (absEnd - absStart).coerceAtLeast(100L)
            cursorMs = absEnd

            val localWords = if (line.words.any { it.isTimed }) {
                UnifiedTextEngine.normalizeWords(
                    words = line.words.mapNotNull { w ->
                        val ws = w.startMs ?: return@mapNotNull null
                        val we = w.endMs ?: return@mapNotNull null
                        TimedTextWord(
                            id = w.id,
                            text = w.text,
                            startMs = (ws - absStart).coerceAtLeast(0L),
                            endMs = (we - absStart).coerceAtMost(durationMs),
                            isEstimated = w.isEstimated,
                            confidence = if (w.isEstimated) 0f else 1f,
                        )
                    },
                    maxDurationMs = durationMs,
                )
            } else {
                emptyList()
            }

            val resolvedKaraoke = if (localWords.isNotEmpty() && styleTemplate.karaokeMode == KaraokeHighlightMode.NONE) {
                KaraokeHighlightMode.PROGRESSIVE
            } else {
                styleTemplate.karaokeMode
            }

            TimedTextClipRequest(
                timelineStartMs = absStart,
                durationMs = durationMs,
                spec = styleTemplate.copy(
                    text = line.text,
                    contentType = TextContentType.LYRIC,
                    timedWords = localWords,
                    karaokeMode = resolvedKaraoke,
                    sourceAssetId = sourceAssetId ?: styleTemplate.sourceAssetId,
                    isTimingEstimated = line.isEstimated || localWords.any { it.isEstimated },
                ),
            )
        }
    }
}
