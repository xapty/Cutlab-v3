package com.example.engine.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.example.engine.asr.PcmStreamSummary
import com.example.engine.asr.StreamingMono16kResampler
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Extracts normalized waveform envelopes, beat timestamps, and bounded PCM segments
 * from video/audio assets using MediaExtractor + MediaCodec without loading entire files into RAM.
 */
open class PcmAudioExtractor(
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val memoryWaveforms = ConcurrentHashMap<String, WaveformEnvelope>()
    private val waveformDir = File(context.cacheDir, "waveforms").apply { mkdirs() }

    fun trimMemoryCache() {
        memoryWaveforms.clear()
    }

    suspend fun extractWaveform(
        asset: MediaAsset,
        binCount: Int = 120,
    ): WaveformEnvelope? = withContext(Dispatchers.IO) {
        if (asset.type == MediaType.IMAGE || asset.status != MediaStatus.READY) return@withContext null

        memoryWaveforms[asset.id]?.let { return@withContext it }

        val cacheFile = File(waveformDir, "${asset.id}.json")
        if (cacheFile.exists()) {
            runCatching {
                json.decodeFromString<WaveformEnvelope>(cacheFile.readText())
            }.getOrNull()?.let { cached ->
                memoryWaveforms[asset.id] = cached
                return@withContext cached
            }
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (!setExtractorSource(extractor, asset)) return@withContext null
            val audioTrackIdx = findAudioTrackIndex(extractor)
            if (audioTrackIdx < 0) return@withContext null

            extractor.selectTrack(audioTrackIdx)
            val inputFormat = extractor.getTrackFormat(audioTrackIdx)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            var sampleRate = inputFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = inputFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                asset.durationMs.coerceAtLeast(1_000L) * 1_000L
            }
            val durationMs = (durationUs / 1_000L).coerceAtLeast(asset.durationMs.coerceAtLeast(1_000L))

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val peaks = FloatArray(binCount)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var decodedFramesTotal = 0L

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        sampleRate = outFmt.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = outFmt.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val sampleCount = shortBuf.remaining()
                            val safeChannels = channelCount.coerceAtLeast(1)
                            val frameCount = sampleCount / safeChannels
                            val ptsMs = (bufferInfo.presentationTimeUs / 1_000L).coerceAtLeast(0L)
                            val baseBin = ((ptsMs.toFloat() / durationMs.toFloat()) * (binCount - 1))
                                .toInt().coerceIn(0, binCount - 1)

                            var chunkPeak = 0f
                            val stride = max(1, safeChannels * 4)
                            var s = 0
                            while (s < sampleCount) {
                                val normalized = abs(shortBuf.get(s).toInt() / 32768f)
                                if (normalized > chunkPeak) chunkPeak = normalized
                                s += stride
                            }
                            if (chunkPeak > peaks[baseBin]) {
                                peaks[baseBin] = chunkPeak.coerceIn(0f, 1f)
                            }
                            decodedFramesTotal += frameCount
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }

            if (decodedFramesTotal == 0L) return@withContext null

            val peakList = peaks.toList()
            val beats = AudioDsp.detectBeatTimestampsMs(peakList, durationMs)
            val envelope = WaveformEnvelope(
                assetId = asset.id,
                durationMs = durationMs,
                sampleRateHz = sampleRate,
                channelCount = channelCount,
                peaks = peakList,
                beatTimestampsMs = beats,
            )
            memoryWaveforms[asset.id] = envelope
            runCatching { cacheFile.writeText(json.encodeToString(envelope)) }
            envelope
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Decodes a bounded window of audio samples starting near [sourceStartMs] for [sourceDurationMs],
     * normalized into [FloatArray] PCM ready for resampling & multi-track mixing.
     */
    suspend fun decodePcmSegment(
        asset: MediaAsset,
        sourceStartMs: Long,
        sourceDurationMs: Long,
        maxFramesCap: Int = 44_100 * 12,
    ): DecodedPcmBuffer? = withContext(Dispatchers.IO) {
        if (asset.type == MediaType.IMAGE || asset.status != MediaStatus.READY) return@withContext null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (!setExtractorSource(extractor, asset)) return@withContext null
            val audioTrackIdx = findAudioTrackIndex(extractor)
            if (audioTrackIdx < 0) return@withContext null

            extractor.selectTrack(audioTrackIdx)
            val inputFormat = extractor.getTrackFormat(audioTrackIdx)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            var sampleRate = inputFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = inputFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val startUs = (sourceStartMs.coerceAtLeast(0L) * 1_000L)
            val endUs = startUs + (sourceDurationMs.coerceAtLeast(100L) * 1_000L)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val collected = ArrayList<Float>(minOf(maxFramesCap * channelCount, 131_072))
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos && (collected.size / channelCount.coerceAtLeast(1)) < maxFramesCap) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        val ptsUs = extractor.sampleTime
                        if (sampleSize < 0 || ptsUs > endUs + 200_000L) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        sampleRate = outFmt.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = outFmt.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= startUs - 30_000L) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floatBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                while (floatBuf.hasRemaining()) {
                                    collected.add(floatBuf.get().coerceIn(-1f, 1f))
                                }
                            } else {
                                val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                while (shortBuf.hasRemaining()) {
                                    collected.add((shortBuf.get().toInt() / 32768f).coerceIn(-1f, 1f))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                            bufferInfo.presentationTimeUs >= endUs
                        ) {
                            sawOutputEos = true
                        }
                    }
                }
            }

            if (collected.isEmpty()) return@withContext null
            DecodedPcmBuffer(
                sampleRateHz = sampleRate,
                channelCount = channelCount.coerceAtLeast(1),
                interleavedSamples = collected.toFloatArray(),
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Streams decoded audio from [asset] in bounded 16 kHz mono PCM-16 chunks without loading the
     * full audio track into RAM. Preserves continuous sample timestamps across chunks and handles
     * coroutine cancellation, silence, and missing audio tracks cleanly.
     */
    open suspend fun streamMonoPcm16kChunks(
        asset: MediaAsset,
        chunkSamplesCount: Int = 16_000,
        onProgress: (Float) -> Unit = {},
        onChunk: suspend (samples16k: ShortArray, chunkStartMs: Long) -> Unit,
    ): PcmStreamSummary = withContext(Dispatchers.IO) {
        if (asset.type == MediaType.IMAGE || asset.status != MediaStatus.READY) {
            return@withContext PcmStreamSummary(0, 0L, 0L, hasAudioTrack = false, peakAmplitude = 0f)
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (!setExtractorSource(extractor, asset)) {
                return@withContext PcmStreamSummary(0, 0L, 0L, hasAudioTrack = false, peakAmplitude = 0f)
            }
            val audioTrackIdx = findAudioTrackIndex(extractor)
            if (audioTrackIdx < 0) {
                return@withContext PcmStreamSummary(0, 0L, 0L, hasAudioTrack = false, peakAmplitude = 0f)
            }

            extractor.selectTrack(audioTrackIdx)
            val inputFormat = extractor.getTrackFormat(audioTrackIdx)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return@withContext PcmStreamSummary(0, 0L, 0L, hasAudioTrack = false, peakAmplitude = 0f)
            var sampleRate = inputFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channelCount = inputFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1_000L)
            } else {
                (asset.durationMs.coerceAtLeast(1_000L) * 1_000L)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var resampler = StreamingMono16kResampler(sampleRate, channelCount)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            val safeChunkSize = chunkSamplesCount.coerceIn(1_600, 48_000)
            val staging = ShortArray(safeChunkSize)
            var stagingCount = 0
            var totalSamplesWritten = 0L
            var chunksProcessed = 0
            var peakAmplitude = 0f

            suspend fun emitStagingChunk() {
                if (stagingCount <= 0) return
                val outSlice = if (stagingCount == safeChunkSize) staging.copyOf() else staging.copyOf(stagingCount)
                val startMs = (totalSamplesWritten * 1_000L) / StreamingMono16kResampler.TARGET_SAMPLE_RATE_HZ
                onChunk(outSlice, startMs)
                totalSamplesWritten += outSlice.size.toLong()
                chunksProcessed++
                stagingCount = 0
            }

            while (!sawOutputEos) {
                coroutineContext.ensureActive()
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        sampleRate = outFmt.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = outFmt.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        resampler = StreamingMono16kResampler(sampleRate, channelCount)
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val mono16k = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floatBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                val arr = FloatArray(floatBuf.remaining())
                                floatBuf.get(arr)
                                resampler.processInterleavedFloats(arr)
                            } else {
                                val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val arr = ShortArray(shortBuf.remaining())
                                shortBuf.get(arr)
                                resampler.processInterleavedShorts(arr)
                            }

                            var offset = 0
                            while (offset < mono16k.size) {
                                val sample = mono16k[offset]
                                val amp = abs(sample.toInt() / 32768f)
                                if (amp > peakAmplitude) peakAmplitude = amp
                                staging[stagingCount++] = sample
                                offset++
                                if (stagingCount >= safeChunkSize) {
                                    emitStagingChunk()
                                }
                            }

                            val progress = (bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat())
                                .coerceIn(0f, 0.99f)
                            onProgress(progress)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }

            emitStagingChunk()
            onProgress(1f)
            val totalDurationMs = (totalSamplesWritten * 1_000L) / StreamingMono16kResampler.TARGET_SAMPLE_RATE_HZ
            PcmStreamSummary(
                chunksProcessed = chunksProcessed,
                totalSamples16k = totalSamplesWritten,
                totalDurationMs = totalDurationMs,
                hasAudioTrack = true,
                peakAmplitude = peakAmplitude,
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun setExtractorSource(extractor: MediaExtractor, asset: MediaAsset): Boolean {
        val managed = asset.managedFilePath?.let { File(it) }?.takeIf { it.exists() && it.canRead() }
        if (managed != null) {
            extractor.setDataSource(managed.absolutePath)
            return true
        }
        val uri = runCatching { Uri.parse(asset.sourceUri) }.getOrNull() ?: return false
        extractor.setDataSource(context, uri, null)
        return true
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun MediaFormat.integerOrDefault(key: String, default: Int): Int {
        return if (containsKey(key)) getInteger(key) else default
    }
}
