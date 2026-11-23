package com.example.engine.text

import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.LayerTransform
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TimedTextWord
import com.example.engine.project.TimelineClipRef
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

enum class WordPlaybackState {
    INACTIVE,
    ACTIVE,
    SPOKEN,
}

data class EvaluatedTimedWord(
    val word: TimedTextWord,
    val state: WordPlaybackState,
    val progressFraction: Float,
)

data class KaraokeEvaluation(
    val words: List<EvaluatedTimedWord>,
    val activeWordIndex: Int,
    val hasTimedWords: Boolean,
) {
    /**
     * Compact state signature used for preview & export bitmap cache keys so we never
     * regenerate identical bitmaps across frames unless word highlight states change.
     */
    fun cacheSignature(): String = if (!hasTimedWords) {
        "static"
    } else {
        words.joinToString(",") { "${it.word.id}:${it.state.ordinal}" }
    }
}

/**
 * Pure-Kotlin unified text & word-timing engine shared across Timeline mutations,
 * Compose live preview, Bitmap export rendering, Lyric synchronization, and Auto Caption.
 */
object UnifiedTextEngine {

    const val MIN_WORD_DURATION_MS = 40L
    const val MIN_TEXT_POSITION_NORM = -1.5f
    const val MAX_TEXT_POSITION_NORM = 1.5f
    const val MIN_TEXT_SCALE = 0.1f
    const val MAX_TEXT_SCALE = 4.0f
    const val MIN_ROTATION_DEG = -360f
    const val MAX_ROTATION_DEG = 360f

    fun effectivePositionX(
        spec: TextOverlaySpec?,
        transform: LayerTransform,
    ): Float = ((spec?.offsetX ?: 0f) + transform.positionX)
        .coerceIn(MIN_TEXT_POSITION_NORM, MAX_TEXT_POSITION_NORM)

    fun effectivePositionY(
        spec: TextOverlaySpec?,
        transform: LayerTransform,
    ): Float = ((spec?.offsetY ?: 0f) + transform.positionY)
        .coerceIn(MIN_TEXT_POSITION_NORM, MAX_TEXT_POSITION_NORM)

    fun clampTransform(transform: LayerTransform): LayerTransform = transform.copy(
        positionX = transform.positionX.coerceIn(MIN_TEXT_POSITION_NORM, MAX_TEXT_POSITION_NORM),
        positionY = transform.positionY.coerceIn(MIN_TEXT_POSITION_NORM, MAX_TEXT_POSITION_NORM),
        scaleX = transform.scaleX.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
        scaleY = transform.scaleY.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE),
        rotationDeg = transform.rotationDeg.coerceIn(MIN_ROTATION_DEG, MAX_ROTATION_DEG),
        opacity = transform.opacity.coerceIn(0f, 1f),
        anchorX = transform.anchorX.coerceIn(0f, 1f),
        anchorY = transform.anchorY.coerceIn(0f, 1f),
    )

    fun effectiveTransform(
        spec: TextOverlaySpec?,
        transform: LayerTransform,
    ): LayerTransform = clampTransform(
        transform.copy(
            positionX = effectivePositionX(spec, transform),
            positionY = effectivePositionY(spec, transform),
        ),
    )

    fun normalizeClipTransformForEdit(
        clip: TimelineClipRef,
        nextEffectiveTransform: LayerTransform,
    ): TimelineClipRef {
        val clamped = clampTransform(nextEffectiveTransform)
        val clearedSpec = clip.textSpec?.copy(offsetX = 0f, offsetY = 0f)
        return clip.copy(
            transform = clamped,
            textSpec = clearedSpec,
        )
    }

    fun centerHorizontally(clip: TimelineClipRef): TimelineClipRef =
        normalizeClipTransformForEdit(
            clip = clip,
            nextEffectiveTransform = effectiveTransform(clip.textSpec, clip.transform).copy(positionX = 0f),
        )

    fun centerVertically(clip: TimelineClipRef): TimelineClipRef =
        normalizeClipTransformForEdit(
            clip = clip,
            nextEffectiveTransform = effectiveTransform(clip.textSpec, clip.transform).copy(positionY = 0f),
        )

    fun resetPosition(clip: TimelineClipRef): TimelineClipRef =
        normalizeClipTransformForEdit(
            clip = clip,
            nextEffectiveTransform = effectiveTransform(clip.textSpec, clip.transform).copy(
                positionX = 0f,
                positionY = 0f,
            ),
        )

    fun resetScale(clip: TimelineClipRef): TimelineClipRef =
        normalizeClipTransformForEdit(
            clip = clip,
            nextEffectiveTransform = effectiveTransform(clip.textSpec, clip.transform).copy(
                scaleX = 1f,
                scaleY = 1f,
                uniformScale = true,
            ),
        )

    fun resetRotation(clip: TimelineClipRef): TimelineClipRef =
        normalizeClipTransformForEdit(
            clip = clip,
            nextEffectiveTransform = effectiveTransform(clip.textSpec, clip.transform).copy(
                rotationDeg = 0f,
            ),
        )

    fun resetAllTransform(clip: TimelineClipRef): TimelineClipRef =
        normalizeClipTransformForEdit(
            clip = clip,
            nextEffectiveTransform = LayerTransform(),
        )

    /**
     * Normalizes clip-local word timestamps:
     * - Trims blank-only entries
     * - Ensures non-negative startMs
     * - Ensures endMs > startMs
     * - Sorts chronologically by (startMs, endMs)
     * - Optional clip duration clamp
     */
    fun normalizeWords(
        words: List<TimedTextWord>,
        maxDurationMs: Long? = null,
    ): List<TimedTextWord> {
        if (words.isEmpty()) return emptyList()
        val cap = maxDurationMs?.coerceAtLeast(MIN_WORD_DURATION_MS)
        return words
            .asSequence()
            .filter { it.text.isNotBlank() }
            .mapNotNull { raw ->
                val safeStart = raw.startMs.coerceAtLeast(0L)
                val safeEnd = max(safeStart + MIN_WORD_DURATION_MS, raw.endMs)
                if (cap != null && safeStart >= cap) {
                    null
                } else {
                    val clampedEnd = if (cap != null) min(safeEnd, cap) else safeEnd
                    if (clampedEnd <= safeStart) {
                        null
                    } else {
                        raw.copy(
                            text = raw.text.trim(),
                            startMs = safeStart,
                            endMs = clampedEnd,
                            confidence = raw.confidence.coerceIn(0f, 1f),
                        )
                    }
                }
            }
            .sortedWith(compareBy<TimedTextWord> { it.startMs }.thenBy { it.endMs })
            .toList()
    }

    /**
     * Evaluates deterministic word-level karaoke state at [clipRelativeMs].
     * Uses only the timeline playhead position (scrubbing backward/forward & frame stepping
     * stay strictly consistent with zero frame-count accumulation).
     *
     * Handles overlapping words intentionally by picking the latest-starting word containing
     * [clipRelativeMs] as the primary ACTIVE word while earlier started words within range are
     * marked SPOKEN (or ACTIVE in ACTIVE_WORD mode if needed—here single crisp ACTIVE focus).
     */
    fun evaluateKaraoke(
        spec: TextOverlaySpec,
        clipRelativeMs: Long,
    ): KaraokeEvaluation {
        if (spec.timedWords.isEmpty() || spec.text.isBlank()) {
            return KaraokeEvaluation(emptyList(), activeWordIndex = -1, hasTimedWords = false)
        }
        val words = normalizeWords(spec.timedWords)
        if (words.isEmpty()) {
            return KaraokeEvaluation(emptyList(), activeWordIndex = -1, hasTimedWords = false)
        }
        val safeMs = clipRelativeMs.coerceAtLeast(0L)
        if (spec.karaokeMode == KaraokeHighlightMode.NONE) {
            return KaraokeEvaluation(
                words = words.map { EvaluatedTimedWord(it, WordPlaybackState.INACTIVE, 0f) },
                activeWordIndex = -1,
                hasTimedWords = true,
            )
        }

        // Pick the latest-started word whose [startMs, endMs) covers safeMs (intentional overlap handling)
        val activeIdx = words.indexOfLast { w -> safeMs in w.startMs until w.endMs }
            .let { idx ->
                // Exact end boundary when safeMs == lastWord.endMs keeps nothing active or final spoken
                idx
            }

        val evaluated = words.mapIndexed { index, word ->
            val state = when {
                index == activeIdx -> WordPlaybackState.ACTIVE
                safeMs >= word.endMs -> {
                    if (spec.karaokeMode == KaraokeHighlightMode.PROGRESSIVE) {
                        WordPlaybackState.SPOKEN
                    } else {
                        WordPlaybackState.INACTIVE
                    }
                }
                activeIdx > index && safeMs >= word.startMs -> {
                    // Overlapped earlier word while a newer word took focus
                    if (spec.karaokeMode == KaraokeHighlightMode.PROGRESSIVE) {
                        WordPlaybackState.SPOKEN
                    } else {
                        WordPlaybackState.INACTIVE
                    }
                }
                else -> WordPlaybackState.INACTIVE
            }
            val span = (word.endMs - word.startMs).coerceAtLeast(1L)
            val progress = when (state) {
                WordPlaybackState.INACTIVE -> 0f
                WordPlaybackState.SPOKEN -> 1f
                WordPlaybackState.ACTIVE -> ((safeMs - word.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            }
            EvaluatedTimedWord(word = word, state = state, progressFraction = progress)
        }

        return KaraokeEvaluation(
            words = evaluated,
            activeWordIndex = activeIdx,
            hasTimedWords = true,
        )
    }

    /**
     * Generates evenly distributed word timings over [durationMs], explicitly flagged with
     * [TimedTextWord.isEstimated] = true so UI never misrepresents estimates as exact ASR timing.
     */
    fun estimateUniformWordTimings(
        text: String,
        durationMs: Long,
        startOffsetMs: Long = 0L,
    ): List<TimedTextWord> {
        val tokens = tokenizeWords(text)
        if (tokens.isEmpty()) return emptyList()
        val safeDur = durationMs.coerceAtLeast(tokens.size * MIN_WORD_DURATION_MS)
        val baseStart = startOffsetMs.coerceAtLeast(0L)
        val totalChars = tokens.sumOf { it.length.coerceAtLeast(1) }.coerceAtLeast(1)

        var cursorMs = baseStart
        val endLimit = baseStart + safeDur
        return tokens.mapIndexed { index, token ->
            val wordStart = cursorMs
            val wordEnd = if (index == tokens.lastIndex) {
                endLimit
            } else {
                val weight = token.length.coerceAtLeast(1).toDouble() / totalChars.toDouble()
                val estDur = (safeDur * weight).toLong().coerceAtLeast(MIN_WORD_DURATION_MS)
                min(endLimit - (tokens.lastIndex - index) * MIN_WORD_DURATION_MS, wordStart + estDur)
            }
            cursorMs = wordEnd
            TimedTextWord(
                id = UUID.randomUUID().toString(),
                text = token,
                startMs = wordStart,
                endMs = max(wordStart + 1L, wordEnd),
                isEstimated = true,
                confidence = 0f,
            )
        }
    }

    fun tokenizeWords(text: String): List<String> =
        text.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    /**
     * Adjusts clip-local timed words when trimming a text clip's left edge by [deltaTimelineMs]
     * (positive = trimmed inward / shortened, negative = extended outward).
     */
    fun adjustSpecForTrimLeft(
        spec: TextOverlaySpec,
        deltaTimelineMs: Long,
        newDurationMs: Long,
    ): TextOverlaySpec {
        if (spec.timedWords.isEmpty()) return spec
        val shifted = spec.timedWords.mapNotNull { w ->
            val newStart = w.startMs - deltaTimelineMs
            val newEnd = w.endMs - deltaTimelineMs
            if (newEnd <= 0L || newStart >= newDurationMs) {
                null
            } else {
                val clampedStart = newStart.coerceAtLeast(0L)
                val clampedEnd = newEnd.coerceAtMost(newDurationMs)
                if (clampedEnd <= clampedStart) null else w.copy(startMs = clampedStart, endMs = clampedEnd)
            }
        }
        val normalized = normalizeWords(shifted, newDurationMs)
        val nextText = if (normalized.isNotEmpty()) normalized.joinToString(" ") { it.text } else spec.text
        return spec.copy(
            text = nextText,
            timedWords = normalized,
        )
    }

    /**
     * Adjusts clip-local timed words when trimming a text clip's right edge to [newDurationMs].
     */
    fun adjustSpecForTrimRight(
        spec: TextOverlaySpec,
        newDurationMs: Long,
    ): TextOverlaySpec {
        if (spec.timedWords.isEmpty()) return spec
        val normalized = normalizeWords(spec.timedWords, newDurationMs)
        val nextText = if (normalized.isNotEmpty()) normalized.joinToString(" ") { it.text } else spec.text
        return spec.copy(
            text = nextText,
            timedWords = normalized,
        )
    }

    /**
     * Partitions clip-local timed words when splitting a text clip at [leftDurationMs] into
     * left `[0, leftDurationMs)` and right `[0, rightDurationMs)` halves.
     */
    fun partitionSpecForSplit(
        spec: TextOverlaySpec,
        leftDurationMs: Long,
        rightDurationMs: Long,
    ): Pair<TextOverlaySpec, TextOverlaySpec> {
        if (spec.timedWords.isEmpty()) return spec to spec

        val leftWords = normalizeWords(
            words = spec.timedWords.mapNotNull { w ->
                if (w.startMs >= leftDurationMs) {
                    null
                } else {
                    val end = min(w.endMs, leftDurationMs)
                    if (end <= w.startMs) null else w.copy(endMs = end)
                }
            },
            maxDurationMs = leftDurationMs,
        )
        val rightWords = normalizeWords(
            words = spec.timedWords.mapNotNull { w ->
                if (w.endMs <= leftDurationMs) {
                    null
                } else {
                    val start = (w.startMs - leftDurationMs).coerceAtLeast(0L)
                    val end = (w.endMs - leftDurationMs).coerceAtMost(rightDurationMs)
                    if (end <= start) null else w.copy(
                        id = UUID.randomUUID().toString(),
                        startMs = start,
                        endMs = end,
                    )
                }
            },
            maxDurationMs = rightDurationMs,
        )

        val leftSpec = spec.copy(
            text = if (leftWords.isNotEmpty()) leftWords.joinToString(" ") { it.text } else spec.text,
            timedWords = leftWords,
        )
        val rightSpec = spec.copy(
            text = if (rightWords.isNotEmpty()) rightWords.joinToString(" ") { it.text } else spec.text,
            timedWords = rightWords,
        )
        return leftSpec to rightSpec
    }
}
