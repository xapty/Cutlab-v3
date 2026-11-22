package com.example.engine.audio

import com.example.engine.animation.AnimationEngine
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.AudioEffectPreset
import com.example.engine.project.ClipAudioEffect
import com.example.engine.project.EditorMode
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectEngine
import com.example.engine.project.TrackType
import com.example.engine.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioDspAndMixerTest {

    private val projectEngine = ProjectEngine()
    private val animationEngine = AnimationEngine()
    private val timelineEngine = TimelineEngine(animationEngine)
    private val mixer = MultiTrackAudioMixer(animationEngine)

    private fun sampleAsset(
        id: String,
        type: MediaType,
        durationMs: Long = 4_000L,
    ) = MediaAsset(
        id = id,
        projectId = "proj-audio",
        type = type,
        displayName = "$id.${if (type == MediaType.AUDIO) "wav" else "mp4"}",
        sourceUri = "content://media/$id",
        managedFilePath = "/tmp/$id",
        mimeType = if (type == MediaType.AUDIO) "audio/wav" else "video/mp4",
        durationMs = durationMs,
        width = if (type == MediaType.AUDIO) 0 else 1920,
        height = if (type == MediaType.AUDIO) 0 else 1080,
        fps = if (type == MediaType.AUDIO) 0f else 30f,
        bitrate = 128_000L,
        fileSizeBytes = 64_000L,
        status = MediaStatus.READY,
        importedAt = 1_000L,
    )

    private fun sineWaveMono(sampleRate: Int, freqHz: Double, durationMs: Long, amp: Float = 0.5f): FloatArray {
        val frames = ((durationMs * sampleRate) / 1_000L).toInt()
        return FloatArray(frames) { i ->
            (sin(2.0 * PI * freqHz * i / sampleRate) * amp).toFloat()
        }
    }

    @Test
    fun `resampleToStereoInterleaved converts mono 16kHz to stereo 44_1kHz and respects speed`() {
        val mono16k = sineWaveMono(sampleRate = 16_000, freqHz = 440.0, durationMs = 500L, amp = 0.6f)
        val normal = AudioDsp.resampleToStereoInterleaved(
            samples = mono16k,
            srcRate = 16_000,
            srcChannels = 1,
            dstRate = 44_100,
            speed = 1.0f,
        )
        val fast2x = AudioDsp.resampleToStereoInterleaved(
            samples = mono16k,
            srcRate = 16_000,
            srcChannels = 1,
            dstRate = 44_100,
            speed = 2.0f,
        )

        val normalFrames = normal.size / 2
        val fastFrames = fast2x.size / 2
        assertTrue(normalFrames in 21_900..22_200)
        assertTrue(fastFrames in 10_900..11_150)
        // Mono duplicates cleanly to both L and R
        assertEquals(normal[100], normal[101], 0.0001f)
    }

    @Test
    fun `applyClipAudioDsp applies equal-power stereo pan, EQ boost, and echo tail`() {
        val mono = sineWaveMono(sampleRate = 44_100, freqHz = 120.0, durationMs = 600L, amp = 0.4f)
        val stereo = AudioDsp.resampleToStereoInterleaved(mono, 44_100, 1, 44_100, 1.0f)

        val hardLeft = AudioDsp.applyClipAudioDsp(
            stereoInterleaved = stereo,
            sampleRate = 44_100,
            effect = ClipAudioEffect(pan = -1.0f),
        )
        var leftEnergy = 0.0
        var rightEnergy = 0.0
        for (i in 0 until hardLeft.size / 2) {
            leftEnergy += abs(hardLeft[i * 2])
            rightEnergy += abs(hardLeft[i * 2 + 1])
        }
        assertTrue(leftEnergy > 1_000.0)
        assertTrue(rightEnergy < 1.0)

        // Echo mix produces non-zero wet energy after an initial impulse
        val impulse = FloatArray(44_100 * 2)
        impulse[0] = 0.9f
        impulse[1] = 0.9f
        val echoed = AudioDsp.applyClipAudioDsp(
            stereoInterleaved = impulse,
            sampleRate = 44_100,
            effect = ClipAudioEffect(preset = AudioEffectPreset.ECHO_ROOM, echoMix = 0.5f),
        )
        val delayIdx = (44_100 * 0.22f).toInt() * 2
        assertTrue(abs(echoed[delayIdx]) > 0.2f)

        // Warm Bass boosts low-frequency 120Hz sine relative to un-EQ'd signal
        val warm = AudioDsp.applyClipAudioDsp(
            stereoInterleaved = stereo,
            sampleRate = 44_100,
            effect = ClipAudioEffect(preset = AudioEffectPreset.WARM_BASS),
        )
        val rawPeak = stereo.maxOf { abs(it) }
        val warmPeak = warm.drop(4_000).maxOf { abs(it) }
        assertTrue(warmPeak > rawPeak * 1.15f)
    }

    @Test
    fun `computePeakEnvelope, detectBeatTimestampsMs, and slicePeaks track rhythmic transients`() {
        val sampleRate = 10_000
        val durationMs = 2_000L
        val totalFrames = 20_000
        val mono = FloatArray(totalFrames) { 0.05f }
        // Inject 3 clear rhythmic transients at 400ms, 1000ms, 1600ms
        val pulseMs = listOf(400L, 1_000L, 1_600L)
        pulseMs.forEach { tMs ->
            val center = ((tMs * sampleRate) / 1_000L).toInt()
            for (k in (center - 120)..(center + 120)) {
                if (k in mono.indices) mono[k] = 0.92f
            }
        }

        val peaks = AudioDsp.computePeakEnvelope(mono, channelCount = 1, binCount = 100)
        assertEquals(100, peaks.size)
        val beats = AudioDsp.detectBeatTimestampsMs(peaks, durationMs = durationMs)
        assertEquals(3, beats.size)
        assertTrue(abs(beats[0] - 400L) <= 60L)
        assertTrue(abs(beats[1] - 1_000L) <= 60L)
        assertTrue(abs(beats[2] - 1_600L) <= 60L)

        val envelope = WaveformEnvelope(
            assetId = "beat-track",
            durationMs = durationMs,
            sampleRateHz = sampleRate,
            channelCount = 1,
            peaks = peaks,
            beatTimestampsMs = beats,
        )
        // Slicing second half [1000ms..2000ms] preserves peak near 1600ms
        val secondHalf = envelope.slicePeaks(sourceStartMs = 1_000L, sourceSpanMs = 1_000L, targetBars = 20)
        assertEquals(20, secondHalf.size)
        assertTrue(secondHalf.maxOrNull()!! > 0.8f)
    }

    @Test
    fun `multiTrackAudioMixer mixes concurrent tracks, enforces fades, keyframed volume, track mute, and soft limiter`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Mix Test",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val vAsset = sampleAsset("v-audio", MediaType.VIDEO, durationMs = 4_000L)
        val aAsset1 = sampleAsset("a-bgm", MediaType.AUDIO, durationMs = 4_000L)
        val aAsset2 = sampleAsset("a-muted", MediaType.AUDIO, durationMs = 4_000L)

        val (doc1, vClip) = timelineEngine.insertAssetAsClip(doc0, vAsset)
        val (doc2, aClip1) = timelineEngine.insertAssetAsClip(doc1, aAsset1)
        val (doc3, extraAudioTrack) = timelineEngine.addTrack(doc2, TrackType.AUDIO)
        val (doc4, aClip2) = timelineEngine.insertAssetAsClip(doc3, aAsset2, targetTrackId = extraAudioTrack.id)
        val doc5 = timelineEngine.toggleTrackMute(doc4, extraAudioTrack.id)

        // Video clip has fade-in of 500ms + volume 0.5; Audio clip has keyframed volume 0 -> 1 over 1000ms + hard Right pan
        var doc6 = timelineEngine.updateClipAudio(doc5, vClip!!.clipId, volume = 0.5f, isMuted = false, fadeInMs = 500L, fadeOutMs = 0L)
        doc6 = timelineEngine.updateClipAudioEffect(doc6, aClip1!!.clipId, ClipAudioEffect(pan = 1.0f))
        doc6 = timelineEngine.upsertKeyframe(doc6, aClip1.clipId, KeyframeProperty.VOLUME, 0L, 0.0f, KeyframeInterpolation.LINEAR).first
        doc6 = timelineEngine.upsertKeyframe(doc6, aClip1.clipId, KeyframeProperty.VOLUME, 1_000L, 1.0f, KeyframeInterpolation.LINEAR).first

        val vPcm = DecodedPcmBuffer(22_050, 1, sineWaveMono(22_050, 220.0, 1_000L, amp = 0.5f))
        val a1Pcm = DecodedPcmBuffer(44_100, 1, sineWaveMono(44_100, 880.0, 1_000L, amp = 0.5f))
        val mutedPcm = DecodedPcmBuffer(44_100, 1, FloatArray(44_100) { 0.99f })

        val mixed = mixer.mixWindowToPcm16(
            document = doc6,
            startMs = 0L,
            windowDurationMs = 1_000L,
            targetSampleRate = 44_100,
        ) { assetId, _, _ ->
            when (assetId) {
                vAsset.id -> vPcm
                aAsset1.id -> a1Pcm
                aAsset2.id -> mutedPcm // Must be ignored because extraAudioTrack is muted!
                else -> null
            }
        }

        assertEquals(44_100 * 2, mixed.size)
        // Near t = 0ms, video clip is in fade-in and audio clip keyframed volume is ~0 -> very low amplitude
        val startPeak = (0 until 40).maxOf { abs(mixed[it].toInt()) }
        // Near t = 900ms, both video (center) and audio (panned hard right) are loud, and right channel > left channel
        val tailOffset = (44_100 * 0.9f).toInt() * 2
        val tailPeakL = (0 until 200).maxOf { abs(mixed[tailOffset + it * 2].toInt()) }
        val tailPeakR = (0 until 200).maxOf { abs(mixed[tailOffset + it * 2 + 1].toInt()) }
        assertTrue(startPeak < 500)
        assertTrue(tailPeakR > tailPeakL * 1.4f)
        // Muted track had constant 0.99f, so if not muted it would saturate near 32767; verify left channel stayed below 12000
        assertTrue(tailPeakL < 12_000)

        // Verify computeLiveMeters accurately reports active tracks & right-heavy pan
        val frameAt900 = timelineEngine.evaluateFrame(doc6, 900L)
        val meters = mixer.computeLiveMeters(
            document = doc6,
            activeFrame = frameAt900,
            waveformsByAssetId = mapOf(
                vAsset.id to WaveformEnvelope(vAsset.id, 4_000L, 22_050, 1, List(40) { 0.6f }),
                aAsset1.id to WaveformEnvelope(aAsset1.id, 4_000L, 44_100, 1, List(40) { 0.8f }),
            ),
            assetsById = mapOf(vAsset.id to vAsset, aAsset1.id to aAsset1, aAsset2.id to aAsset2),
        )
        assertEquals(2, meters.activeSourceCount)
        assertEquals(4, meters.trackMeters.size)
        assertTrue(meters.rightLevel > meters.leftLevel)
        val mutedTrackMeter = meters.trackMeters.first { it.trackId == extraAudioTrack.id }
        assertTrue(mutedTrackMeter.isMuted)
        assertEquals(0f, mutedTrackMeter.peakLevel, 0.0001f)
    }

    @Test
    fun `chunked mixWindowToPcm16 with targetFrameCountOverride produces exact 1s sample count`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Chunk Continuity",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val aAsset = sampleAsset("a-cont", MediaType.AUDIO, durationMs = 2_000L)
        val (doc1, _) = timelineEngine.insertAssetAsClip(doc0, aAsset)
        val srcBuffer = DecodedPcmBuffer(44_100, 1, sineWaveMono(44_100, 440.0, 2_000L, amp = 0.5f))

        var writtenFrames = 0L
        var t = 0L
        while (t < 1_000L) {
            val nextT = (t + 40L).coerceAtMost(1_000L)
            val expectedFrames = com.example.core.common.TimelineTimebase.audioFramesDelta(
                windowEndMs = nextT,
                alreadyWrittenFrames = writtenFrames,
                sampleRate = 44_100,
            )
            val chunk = mixer.mixWindowToPcm16(
                document = doc1,
                startMs = t,
                windowDurationMs = nextT - t,
                targetSampleRate = 44_100,
                targetFrameCountOverride = expectedFrames,
            ) { _, _, _ -> srcBuffer }
            assertEquals(expectedFrames * 2, chunk.size)
            writtenFrames += chunk.size / 2
            t = nextT
        }
        assertEquals(44_100L, writtenFrames)
    }
}
