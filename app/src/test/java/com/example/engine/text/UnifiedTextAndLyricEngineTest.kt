package com.example.engine.text

import com.example.core.common.TimelineTimebase
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.ProjectEngine
import com.example.engine.project.TextContentType
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TimedTextWord
import com.example.engine.project.TrackType
import com.example.engine.timeline.TimedTextClipRequest
import com.example.engine.timeline.TimelineCommandHistory
import com.example.engine.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedTextAndLyricEngineTest {

    private val projectEngine = ProjectEngine()
    private val timelineEngine = TimelineEngine()

    private fun baseSpec(
        mode: KaraokeHighlightMode = KaraokeHighlightMode.PROGRESSIVE,
    ): TextOverlaySpec = EffectsEngine.defaultKaraokeLyricSpec("One two three").copy(
        karaokeMode = mode,
        timedWords = listOf(
            TimedTextWord("w1", "One", startMs = 500L, endMs = 1_000L),
            TimedTextWord("w2", "two", startMs = 1_000L, endMs = 1_600L),
            TimedTextWord("w3", "three", startMs = 1_600L, endMs = 2_400L),
        ),
    )

    @Test
    fun `karaoke evaluation tracks before during after backward scrub and frame stepping deterministically`() {
        val spec = baseSpec(KaraokeHighlightMode.PROGRESSIVE)

        // Before first word starts
        val before = UnifiedTextEngine.evaluateKaraoke(spec, clipRelativeMs = 250L)
        assertEquals(-1, before.activeWordIndex)
        assertTrue(before.words.all { it.state == WordPlaybackState.INACTIVE })

        // Forward playback: inside second word
        val midSecond = UnifiedTextEngine.evaluateKaraoke(spec, clipRelativeMs = 1_300L)
        assertEquals(1, midSecond.activeWordIndex)
        assertEquals(WordPlaybackState.SPOKEN, midSecond.words[0].state)
        assertEquals(WordPlaybackState.ACTIVE, midSecond.words[1].state)
        assertEquals(0.5f, midSecond.words[1].progressFraction, 0.01f)
        assertEquals(WordPlaybackState.INACTIVE, midSecond.words[2].state)

        // After final word ends
        val after = UnifiedTextEngine.evaluateKaraoke(spec, clipRelativeMs = 2_600L)
        assertEquals(-1, after.activeWordIndex)
        assertTrue(after.words.all { it.state == WordPlaybackState.SPOKEN })

        // Scrubbing backward reverts highlight deterministically
        val scrubbedBack = UnifiedTextEngine.evaluateKaraoke(spec, clipRelativeMs = 600L)
        assertEquals(0, scrubbedBack.activeWordIndex)
        assertEquals(WordPlaybackState.ACTIVE, scrubbedBack.words[0].state)
        assertEquals(WordPlaybackState.INACTIVE, scrubbedBack.words[1].state)
        assertEquals(WordPlaybackState.INACTIVE, scrubbedBack.words[2].state)

        // Frame stepping at 30fps matches millisecond playhead without drift
        val frame31Ms = TimelineTimebase.stepFrameMillis(currentMs = 966L, deltaFrames = 1, fps = 30)
        val stepped = UnifiedTextEngine.evaluateKaraoke(spec, clipRelativeMs = frame31Ms)
        assertEquals(1, stepped.activeWordIndex)
    }

    @Test
    fun `karaoke ACTIVE_WORD mode keeps previously spoken words inactive and handles overlaps safely`() {
        val overlappingSpec = baseSpec(KaraokeHighlightMode.ACTIVE_WORD).copy(
            timedWords = listOf(
                TimedTextWord("w1", "Held", startMs = 0L, endMs = 1_200L),
                TimedTextWord("w2", "Overlap", startMs = 800L, endMs = 1_500L),
                TimedTextWord("w_neg", "Clamped", startMs = -200L, endMs = -50L),
            ),
        )
        // At 900ms both w1 and w2 span, intentional overlap policy activates latest-started word
        val eval = UnifiedTextEngine.evaluateKaraoke(overlappingSpec, clipRelativeMs = 900L)
        assertEquals(1_000L.let { 2 }, eval.activeWordIndex) // w_neg normalized to 0..40ms, then w1 0..1200, w2 800..1500
        assertEquals("Overlap", eval.words[eval.activeWordIndex].word.text)
        assertEquals(WordPlaybackState.INACTIVE, eval.words[1].state)

        // Empty text returns safe empty evaluation
        val emptyEval = UnifiedTextEngine.evaluateKaraoke(overlappingSpec.copy(text = "  "), 500L)
        assertFalse(emptyEval.hasTimedWords)
        assertTrue(emptyEval.words.isEmpty())
    }

    @Test
    fun `timeline batch insertion edits move trim split duplicate delete undo redo and lock protection work cleanly`() {
        val doc0 = projectEngine.createNewDocument("Text Timeline", AspectRatioPreset.VERTICAL_9_16, 30, EditorMode.ADVANCED)
        val existingReq = TimedTextClipRequest(
            timelineStartMs = 0L,
            durationMs = 1_000L,
            spec = EffectsEngine.defaultCaptionSpec("Existing"),
        )
        val (docWithExisting, firstInserted) = timelineEngine.insertTextClipsBatch(doc0, listOf(existingReq))
        assertEquals(1, firstInserted.size)

        // Batch insert two new lyric clips starting at 1000ms and 3500ms; existing clip at 0ms must not shift
        val batchReqs = listOf(
            TimedTextClipRequest(
                timelineStartMs = 1_000L,
                durationMs = 2_000L,
                spec = baseSpec().copy(text = "Line A", timedWords = listOf(
                    TimedTextWord("a1", "One", 100L, 700L),
                    TimedTextWord("a2", "two", 700L, 1_400L),
                    TimedTextWord("a3", "three", 1_400L, 1_900L),
                )),
            ),
            TimedTextClipRequest(
                timelineStartMs = 3_500L,
                durationMs = 1_500L,
                spec = EffectsEngine.defaultKaraokeLyricSpec("Line B"),
            ),
        )
        val (docBatch, batchInserted) = timelineEngine.insertTextClipsBatch(docWithExisting, batchReqs)
        assertEquals(2, batchInserted.size)
        val untouchedExisting = docBatch.tracks.flatMap { it.clips }.first { it.clipId == firstInserted.single().clipId }
        assertEquals(0L, untouchedExisting.timelineStartMs)

        val clipAId = batchInserted.first().clipId

        // Move clip preserves internal clip-local word timing
        val movedDoc = timelineEngine.moveClip(docBatch, clipAId, desiredStartMs = 1_200L, snappingEnabled = false).document
        val movedClip = movedDoc.tracks.flatMap { it.clips }.first { it.clipId == clipAId }
        assertEquals(1_200L, movedClip.timelineStartMs)
        assertEquals(100L, movedClip.textSpec!!.timedWords.first().startMs)

        // Split clip at 1_200 + 800 = 2_000ms partitions word timings cleanly within resulting clip durations
        val (splitDoc, _) = timelineEngine.splitClipAt(movedDoc, clipAId, splitTimelineMs = 2_000L)
        val splitPair = splitDoc.tracks.flatMap { it.clips }
            .filter { it.timelineStartMs >= 1_200L && it.timelineStartMs < 3_500L && it.clipKind == com.example.engine.project.ClipKind.TEXT }
            .sortedBy { it.timelineStartMs }
        assertEquals(2, splitPair.size)
        val leftHalf = splitPair[0]
        val rightHalf = splitPair[1]
        assertTrue(leftHalf.textSpec!!.timedWords.all { it.startMs >= 0L && it.endMs <= leftHalf.durationMs })
        assertTrue(rightHalf.textSpec!!.timedWords.all { it.startMs >= 0L && it.endMs <= rightHalf.durationMs })

        // Trim right edge clamps/removes words beyond new duration
        val trimmedRight = timelineEngine.trimClipRight(
            document = splitDoc,
            clipId = rightHalf.clipId,
            desiredEndMs = rightHalf.timelineStartMs + 500L,
        ).document
        val trimmedRightClip = trimmedRight.tracks.flatMap { it.clips }.first { it.clipId == rightHalf.clipId }
        assertTrue(trimmedRightClip.textSpec!!.timedWords.all { it.endMs <= 500L })

        // Edit text & duplicate & delete
        val editedDoc = timelineEngine.updateClipText(trimmedRight, leftHalf.clipId, leftHalf.textSpec.copy(text = "Edited Line"))
        assertEquals("Edited Line", editedDoc.tracks.flatMap { it.clips }.first { it.clipId == leftHalf.clipId }.textSpec!!.text)
        val (dupDoc, _) = timelineEngine.duplicateClip(editedDoc, leftHalf.clipId)
        assertEquals(5, dupDoc.tracks.filter { it.type == TrackType.TEXT }.sumOf { it.clips.size })

        // Undo / redo via TimelineCommandHistory restores exact text and timing
        val history = TimelineCommandHistory()
        history.record("Insert Lyrics", docWithExisting, docBatch)
        assertEquals(3, docBatch.tracks.filter { it.type == TrackType.TEXT }.sumOf { it.clips.size })
        val undone = history.undo()!!.before
        assertEquals(1, undone.tracks.filter { it.type == TrackType.TEXT }.sumOf { it.clips.size })
        val redone = history.redo()!!.after
        assertEquals(3, redone.tracks.filter { it.type == TrackType.TEXT }.sumOf { it.clips.size })

        // Locked text track rejects batch insertion cleanly
        val lockedTextTrackId = doc0.tracks.first { it.type == TrackType.TEXT }.id
        val lockedDoc = timelineEngine.toggleTrackLock(doc0, lockedTextTrackId)
        val (afterLockedAttempt, lockedInserted) = timelineEngine.insertTextClipsBatch(
            document = lockedDoc,
            requests = batchReqs,
            targetTrackId = lockedTextTrackId,
        )
        assertTrue(lockedInserted.isEmpty())
        assertEquals(0, afterLockedAttempt.tracks.first { it.id == lockedTextTrackId }.clips.size)
    }

    @Test
    fun `lyric engine supports txt and lrc import line editing manual tap sync estimated words snapping and compilation`() {
        val raw = """
            [00:01.50]First golden light
            Second bright morning
        """.trimIndent()
        var lines = LyricEngine.parseLyricsText(raw)
        assertEquals(2, lines.size)
        assertEquals(1_500L, lines[0].startMs)
        assertEquals(4_500L, lines[0].endMs)

        // Edit text preserves line id & updates words
        lines = LyricEngine.editLineText(lines, lines[1].id, "Second bright dawn sky")
        assertEquals(4, lines[1].words.size)

        // Split & merge & move
        lines = LyricEngine.splitLine(lines, lines[1].id, splitAfterWordIndex = 1)
        assertEquals(3, lines.size)
        assertEquals("Second bright", lines[1].text)
        assertEquals("dawn sky", lines[2].text)

        lines = LyricEngine.moveLine(lines, lines[2].id, delta = -1)
        assertEquals("dawn sky", lines[1].text)
        lines = LyricEngine.moveLine(lines, lines[1].id, delta = 1)
        lines = LyricEngine.mergeWithNext(lines, lines[1].id)
        assertEquals("Second bright dawn sky", lines[1].text)

        // Snapping to beats & project FPS
        val snappedToBeat = LyricEngine.snapTimestampMs(
            timeMs = 2_050L,
            fps = 30,
            beatTimestampsMs = listOf(2_000L),
            snapToBeats = true,
            snapToFrames = true,
        )
        assertEquals(2_000L, snappedToBeat)

        // Tap assign line & individual word timing + offset shift
        lines = LyricEngine.assignLineStart(lines, lines[1].id, playheadMs = 5_000L)
        lines = LyricEngine.assignLineEnd(lines, lines[1].id, playheadMs = 7_500L)
        val firstWordId = lines[1].words.first().id
        lines = LyricEngine.assignWordStart(lines, lines[1].id, firstWordId, playheadMs = 5_000L)
        lines = LyricEngine.assignWordEnd(lines, lines[1].id, firstWordId, playheadMs = 5_550L)
        lines = LyricEngine.adjustWordDuration(lines, lines[1].id, firstWordId, newDurationMs = 600L)
        assertEquals(5_600L, lines[1].words.first().endMs)
        assertFalse(lines[1].isEstimated)

        // Explicitly estimated distribution flags line and words as isEstimated = true
        lines = LyricEngine.distributeWordTimingsAsEstimated(lines, lines[0].id)
        assertTrue(lines[0].isEstimated)
        assertTrue(lines[0].words.all { it.isEstimated })

        // Offset shift updates both line and words
        lines = LyricEngine.shiftLinesOffset(lines, setOf(lines[0].id), deltaMs = 500L)
        assertEquals(2_000L, lines[0].startMs)
        assertEquals(2_000L, lines[0].words.first().startMs)

        // Build timeline clip requests preserves clip-local word timing and INVERT_VIDEO styling
        val style = EffectsEngine.defaultKaraokeLyricSpec().copy(
            renderMode = TextRenderMode.INVERT_VIDEO,
            invertStrength = 0.9f,
        )
        val requests = LyricEngine.buildTimelineClipRequests(lines, styleTemplate = style, sourceAssetId = "song_1")
        assertEquals(2, requests.size)
        assertEquals(TextContentType.LYRIC, requests[0].spec.contentType)
        assertEquals(TextRenderMode.INVERT_VIDEO, requests[0].spec.renderMode)
        assertTrue(requests[0].spec.isTimingEstimated)
        assertEquals(0L, requests[0].spec.timedWords.first().startMs) // clip-local offset 0
        assertFalse(requests[1].spec.isTimingEstimated)

        // Reset timing clears timestamps cleanly
        val reset = LyricEngine.resetTiming(lines, setOf(lines[0].id))
        assertEquals(null, reset[0].startMs)
        assertFalse(reset[0].isEstimated)
        assertNotNull(reset[1].startMs)
    }
}
