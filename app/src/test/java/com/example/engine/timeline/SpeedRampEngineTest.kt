package com.example.engine.timeline

import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectEngine
import com.example.engine.project.SpeedPoint
import com.example.engine.project.SpeedRampPreset
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRampEngineTest {

    private val projectEngine = ProjectEngine()
    private val timelineEngine = TimelineEngine()

    private fun sampleVideoAsset(durationMs: Long = 6_000L) = MediaAsset(
        id = "v-ramp-1",
        projectId = "proj-ramp",
        type = MediaType.VIDEO,
        displayName = "ramp.mp4",
        sourceUri = "content://media/ramp",
        managedFilePath = "/tmp/ramp.mp4",
        mimeType = "video/mp4",
        durationMs = durationMs,
        width = 1920,
        height = 1080,
        fps = 30f,
        bitrate = 4_000_000L,
        fileSizeBytes = 2_000_000L,
        status = MediaStatus.READY,
        importedAt = 1_000L,
    )

    @Test
    fun `normalizePoints clamps speeds and anchors endpoints cleanly`() {
        val normalized = SpeedRampEngine.normalizePoints(
            points = listOf(
                SpeedPoint(0.25f, 8.0f), // exceeds MAX_SPEED -> 4.0f
                SpeedPoint(0.75f, 0.05f), // below MIN_SPEED -> 0.25f
            ),
        )
        assertEquals(4, normalized.size)
        assertEquals(0f, normalized.first().normalizedTime, 0.0001f)
        assertEquals(4.0f, normalized.first().speed, 0.001f)
        assertEquals(0.25f, normalized[1].normalizedTime, 0.001f)
        assertEquals(0.25f, normalized[2].speed, 0.001f)
        assertEquals(1f, normalized.last().normalizedTime, 0.0001f)
    }

    @Test
    fun `piecewise linear speed integral preserves source span cleanly`() {
        // Linear ramp from 1.0x to 3.0x over 1.0 unit -> exact average speed = 2.0x
        val linearRamp = listOf(SpeedPoint(0f, 1.0f), SpeedPoint(1f, 3.0f))
        val avg = SpeedRampEngine.averageSpeed(linearRamp)
        assertEquals(2.0f, avg, 0.001f)

        // At normalized t = 0.5, speed(0.5) = 2.0, integral from 0..0.5 of (1 + 2*tau) = 0.5 + 0.25 = 0.75
        val integralHalf = SpeedRampEngine.integrateNormalizedSourceUnits(linearRamp, 0.5f)
        assertEquals(0.75f, integralHalf, 0.001f)

        // Preset curves have valid average speeds and monotonic integrals
        for (preset in listOf(SpeedRampPreset.MONTAGE, SpeedRampPreset.HERO_MOMENT, SpeedRampPreset.BULLET_TIME)) {
            val pts = SpeedRampEngine.presetPoints(preset)
            val a = SpeedRampEngine.integrateNormalizedSourceUnits(pts, 0.25f)
            val b = SpeedRampEngine.integrateNormalizedSourceUnits(pts, 0.75f)
            val c = SpeedRampEngine.integrateNormalizedSourceUnits(pts, 1.0f)
            assertTrue("$preset integral must be strictly increasing", a > 0f && b > a && c > b)
        }
    }

    @Test
    fun `clipRelativeToSourceMs traverses monotonically forward and backwards when reversed`() {
        val clip = TimelineClipRef(
            clipId = "c1",
            assetId = "v-ramp-1",
            trackId = "t-video",
            timelineStartMs = 0L,
            sourceStartMs = 1_000L,
            durationMs = 2_000L,
            speed = 1.0f,
            sourceTotalDurationMs = 6_000L,
            speedRampPreset = SpeedRampPreset.HERO_MOMENT,
            speedRampPoints = SpeedRampEngine.presetPoints(SpeedRampPreset.HERO_MOMENT),
            isReversed = false,
        )

        val fwdStart = SpeedRampEngine.clipRelativeToSourceMs(clip, 0L, 6_000L)
        val fwdMid = SpeedRampEngine.clipRelativeToSourceMs(clip, 1_000L, 6_000L)
        val fwdEnd = SpeedRampEngine.clipRelativeToSourceMs(clip, 2_000L, 6_000L)
        assertEquals(1_000L, fwdStart)
        assertTrue(fwdMid > fwdStart)
        assertTrue(fwdEnd > fwdMid)

        val reversedClip = clip.copy(isReversed = true)
        val revStart = SpeedRampEngine.clipRelativeToSourceMs(reversedClip, 0L, 6_000L)
        val revMid = SpeedRampEngine.clipRelativeToSourceMs(reversedClip, 1_000L, 6_000L)
        val revEnd = SpeedRampEngine.clipRelativeToSourceMs(reversedClip, 2_000L, 6_000L)
        assertEquals(1_000L, revEnd)
        assertTrue("Reversed clip must start near source end", revStart > revMid)
        assertTrue("Reversed clip source timestamp must decrease", revMid > revEnd)
    }

    @Test
    fun `timelineEngine updates speed ramp reflows duration and splits reversed clips accurately`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Ramp Split",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val asset = sampleVideoAsset(6_000L)
        val (doc1, inserted) = timelineEngine.insertAssetAsClip(doc0, asset)
        val clipId = inserted!!.clipId

        val pts = SpeedRampEngine.presetPoints(SpeedRampPreset.FLASH_IN)
        val doc2 = timelineEngine.updateClipSpeedRamp(doc1, clipId, SpeedRampPreset.FLASH_IN, pts, isImageClip = false)
        val ramped = doc2.tracks.first { it.type == TrackType.VIDEO }.clips.first()
        assertEquals(SpeedRampPreset.FLASH_IN, ramped.speedRampPreset)
        assertTrue("FLASH_IN increases average speed > 1x so timeline duration shrinks", ramped.durationMs < 6_000L)

        val doc3 = timelineEngine.updateClipReversed(doc2, clipId, isReversed = true, isImageClip = false)
        val reversed = doc3.tracks.first { it.type == TrackType.VIDEO }.clips.first()
        assertTrue(reversed.isReversed)

        // Split reversed + ramped clip near midpoint
        val splitAt = reversed.durationMs / 2L
        val (doc4, rightClipId) = timelineEngine.splitClipAt(doc3, clipId, splitAt)
        assertNotNull(rightClipId)
        val splitClips = doc4.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, splitClips.size)
        val left = splitClips[0]
        val right = splitClips[1]
        assertTrue(left.isReversed)
        assertTrue(right.isReversed)
        // Left half of a reversed clip plays the later tail of the source video, so left.sourceStartMs > right.sourceStartMs
        assertTrue("Reversed split preserves reverse source order", left.sourceStartMs > right.sourceStartMs)
        assertFalse(left.speedRampPoints.isEmpty())
        assertFalse(right.speedRampPoints.isEmpty())
    }
}
