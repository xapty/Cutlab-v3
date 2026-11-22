package com.example.engine.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.example.core.common.TimelineTimebase
import com.example.core.graphics.CompositorShaders
import com.example.core.graphics.GlCompositorMath
import com.example.core.graphics.TransitionVisualParams
import com.example.engine.animation.AnimationEngine
import com.example.engine.audio.DecodedPcmBuffer
import com.example.engine.audio.MultiTrackAudioMixer
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.compositor.CompositorEngine
import com.example.engine.compositor.ResolvedVisualLayer
import com.example.engine.effects.Lut3DEngine
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.LayerMask
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import com.example.engine.timeline.ActiveClipLayer
import com.example.engine.timeline.SpeedRampEngine
import com.example.engine.timeline.TimelineEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Path B exporter: Full multi-layer OpenGL ES 2.0 + GLSL compositor writing directly into a
 * MediaCodec H.264/H.265 Surface + synchronized MultiTrackAudioMixer PCM -> AAC MediaCodec + MediaMuxer.
 * Preserves multi-track overlays, blend modes, SDF masks, shaders, keyframes, adjustment layers,
 * transitions, kinetic typography, and audio fades/EQ/pan/mixing identically to live preview.
 */
class CustomGlVideoExporter(
    private val context: Context,
    private val animationEngine: AnimationEngine = AnimationEngine(),
    private val timelineEngine: TimelineEngine = TimelineEngine(animationEngine),
    private val compositorEngine: CompositorEngine = CompositorEngine(animationEngine),
    private val pcmAudioExtractor: PcmAudioExtractor = PcmAudioExtractor(context),
    private val audioMixer: MultiTrackAudioMixer = MultiTrackAudioMixer(animationEngine),
    private val textBitmapRenderer: TextLayerBitmapRenderer = TextLayerBitmapRenderer(context),
) {
    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_CHUNK_MS = 250L
    }

    suspend fun export(
        document: ProjectDocument,
        assetsById: Map<String, MediaAsset>,
        plan: ExportPlan,
        outputFile: File,
        onProgress: (progress: Float, statusMessage: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (outputFile.exists()) outputFile.delete()
        outputFile.parentFile?.mkdirs()

        val outW = plan.outputWidth
        val outH = plan.outputHeight
        val fps = plan.fps.coerceIn(24, 60)
        val totalFrames = TimelineTimebase.frameCountForDurationMs(plan.totalDurationMs, fps).coerceAtLeast(1)

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        val glEnv = GlOffscreenEncoderEnv(outW, outH)
        val videoSampler = VideoFrameSampler(context, max(outW, outH).coerceAtMost(1920))
        val imageBitmapCache = HashMap<String, Bitmap>()
        val pcmChunkCache = SlidingClipPcmCache(pcmAudioExtractor, assetsById)

        val muxerBridge = MuxerBridge(expectAudio = plan.hasAudio)

        try {
            onProgress(0.01f, "Initializing GL compositor & hardware encoder...")

            val videoFormat = MediaFormat.createVideoFormat(plan.codec.mimeType, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(plan.codec.mimeType).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            if (plan.hasAudio) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNELS,
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, plan.audioBitrateBps)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
                }
                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            glEnv.init(inputSurface)

            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()

            var nextAudioWindowStartMs = 0L
            var totalAudioFramesFed = 0L
            var audioEosQueued = !plan.hasAudio
            var audioDrainDone = !plan.hasAudio
            var lastProgressEmitMs = 0L

            fun drainEncodersOnce(endVideo: Boolean = false) {
                val startedNow = muxerBridge.drainCodec(
                    codec = videoEncoder!!,
                    bufferInfo = videoInfo,
                    muxer = muxer!!,
                    isVideo = true,
                    waitTimeoutUs = if (endVideo) 5_000L else 0L,
                )
                if (startedNow) muxerStarted = true

                if (audioEncoder != null && !audioDrainDone) {
                    val aStarted = muxerBridge.drainCodec(
                        codec = audioEncoder,
                        bufferInfo = audioInfo,
                        muxer = muxer,
                        isVideo = false,
                        waitTimeoutUs = 0L,
                    )
                    if (aStarted) muxerStarted = true
                    if (muxerBridge.audioEosSeen) audioDrainDone = true
                }
            }

            fun feedAudioChunk(windowStartMs: Long, windowDurMs: Long) {
                val aCodec = audioEncoder ?: return
                val windowEndMs = windowStartMs + windowDurMs
                val exactFrames = TimelineTimebase.audioFramesDelta(
                    windowEndMs = windowEndMs,
                    alreadyWrittenFrames = totalAudioFramesFed,
                    sampleRate = AUDIO_SAMPLE_RATE,
                ).coerceAtLeast(1)
                val pcm16 = audioMixer.mixWindowToPcm16(
                    document = document,
                    startMs = windowStartMs,
                    windowDurationMs = windowDurMs,
                    targetSampleRate = AUDIO_SAMPLE_RATE,
                    targetFrameCountOverride = exactFrames,
                ) { assetId, srcStartMs, srcDurMs ->
                    pcmChunkCache.getPcmSlice(assetId, srcStartMs, srcDurMs)
                }

                val byteData = ByteArray(pcm16.size * 2)
                ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm16)
                var offset = 0
                while (offset < byteData.size) {
                    coroutineContext.ensureActive()
                    val inIdx = aCodec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = aCodec.getInputBuffer(inIdx) ?: break
                        inBuf.clear()
                        val toWrite = min(inBuf.remaining(), byteData.size - offset)
                        inBuf.put(byteData, offset, toWrite)
                        val ptsUs = (totalAudioFramesFed * 1_000_000L) / AUDIO_SAMPLE_RATE
                        aCodec.queueInputBuffer(inIdx, 0, toWrite, ptsUs, 0)
                        totalAudioFramesFed += (toWrite / (AUDIO_CHANNELS * 2)).toLong()
                        offset += toWrite
                    } else {
                        drainEncodersOnce()
                    }
                }
                drainEncodersOnce()
            }

            val canvasAspect = outW.toFloat() / outH.toFloat().coerceAtLeast(1f)
            val quantStepUs = (1_000_000L / fps).coerceAtLeast(16_666L)

            for (frameIdx in 0 until totalFrames) {
                coroutineContext.ensureActive()

                val timelineMs = TimelineTimebase.frameToMillis(frameIdx.toLong(), fps)
                    .coerceIn(0L, plan.totalDurationMs.coerceAtLeast(0L))
                val ptsNs = TimelineTimebase.frameToPtsNs(frameIdx.toLong(), fps)

                val activeFrame = timelineEngine.evaluateFrame(document, timelineMs)
                val resolved = compositorEngine.resolveFrame(activeFrame, assetsById, outW, outH)

                glEnv.beginCompositionFrame()

                // 1. Optional outgoing transition layer
                val outLayer = resolved.outgoingLayer
                val primaryLayer = resolved.layers.firstOrNull()
                if (outLayer != null && primaryLayer?.activeTransition != null && primaryLayer.transitionProgress < 1f) {
                    val outParams = GlCompositorMath.computeOutgoingTransitionParams(primaryLayer)
                    val transParams = TransitionVisualParams(
                        alpha = outParams.alpha,
                        shiftX = outParams.shiftX,
                        blurRadius = outParams.blurRadius,
                    )
                    val outBmp = resolveVisualBitmap(outLayer, videoSampler, imageBitmapCache, quantStepUs)
                    if (outBmp != null) {
                        glEnv.compositeVisualLayer(
                            bitmap = outBmp,
                            layer = outLayer,
                            canvasAspect = canvasAspect,
                            timeSec = timelineMs / 1000f,
                            transitionParams = transParams,
                        )
                    }
                }

                // 2. Active visual layers bottom-to-top
                for (layer in resolved.layers) {
                    val transParams = GlCompositorMath.computeTransitionVisualParams(layer)
                    val bmp = resolveVisualBitmap(layer, videoSampler, imageBitmapCache, quantStepUs)
                    if (bmp != null) {
                        glEnv.compositeVisualLayer(
                            bitmap = bmp,
                            layer = layer,
                            canvasAspect = canvasAspect,
                            timeSec = timelineMs / 1000f,
                            transitionParams = transParams,
                        )
                    }
                }

                // 3. Text, karaoke & Invert Lyric layers above visuals with matching GPU shaders
                for (textLayer in resolved.textLayers) {
                    val spec = animationEngine.evaluateTextSpec(textLayer.clip, textLayer.clipRelativeMs) ?: continue
                    val animTransform = animationEngine.evaluateTransform(textLayer.clip, textLayer.clipRelativeMs)
                    val rendered = textBitmapRenderer.render(
                        spec = spec,
                        transform = animTransform,
                        clipRelativeMs = textLayer.clipRelativeMs,
                        canvasWidth = outW,
                        canvasHeight = outH,
                    ) ?: continue
                    try {
                        if (spec.renderMode == TextRenderMode.INVERT_VIDEO) {
                            glEnv.compositeInvertTextLayer(rendered = rendered)
                        } else {
                            glEnv.compositeTextLayer(
                                rendered = rendered,
                                blendMode = textLayer.clip.blendMode,
                            )
                        }
                    } finally {
                        if (!rendered.isCacheManaged && !rendered.bitmap.isRecycled) {
                            rendered.bitmap.recycle()
                        }
                    }
                }

                glEnv.submitFrame(ptsNs)
                drainEncodersOnce()

                // Interleave audio windows alongside video progression
                if (plan.hasAudio && nextAudioWindowStartMs <= timelineMs) {
                    val endMs = min(nextAudioWindowStartMs + AUDIO_CHUNK_MS, plan.totalDurationMs)
                    val durMs = (endMs - nextAudioWindowStartMs).coerceAtLeast(1L)
                    feedAudioChunk(nextAudioWindowStartMs, durMs)
                    nextAudioWindowStartMs = endMs
                }

                val nowMs = System.currentTimeMillis()
                if (nowMs - lastProgressEmitMs >= 140L || frameIdx == totalFrames - 1) {
                    lastProgressEmitMs = nowMs
                    val fraction = ((frameIdx + 1).toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                    val overall = (fraction * 0.92f).coerceIn(0.02f, 0.92f)
                    onProgress(
                        overall,
                        "Path B (Custom GL): frame ${frameIdx + 1}/$totalFrames (${(overall * 100).toInt()}%)",
                    )
                }
            }

            // Flush any trailing audio remainder
            if (plan.hasAudio && audioEncoder != null) {
                while (nextAudioWindowStartMs < plan.totalDurationMs) {
                    coroutineContext.ensureActive()
                    val endMs = min(nextAudioWindowStartMs + AUDIO_CHUNK_MS, plan.totalDurationMs)
                    val durMs = (endMs - nextAudioWindowStartMs).coerceAtLeast(1L)
                    feedAudioChunk(nextAudioWindowStartMs, durMs)
                    nextAudioWindowStartMs = endMs
                }
                while (!audioEosQueued) {
                    coroutineContext.ensureActive()
                    val inIdx = audioEncoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val ptsUs = (totalAudioFramesFed * 1_000_000L) / AUDIO_SAMPLE_RATE
                        audioEncoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        audioEosQueued = true
                    } else {
                        drainEncodersOnce()
                    }
                }
            }

            onProgress(0.95f, "Finalizing encoded streams...")
            videoEncoder.signalEndOfInputStream()

            var drainIters = 0
            while ((!muxerBridge.videoEosSeen || !audioDrainDone) && drainIters < 2_500) {
                coroutineContext.ensureActive()
                drainEncodersOnce(endVideo = true)
                if (muxerBridge.videoEosSeen && (audioDrainDone || !plan.hasAudio)) break
                drainIters++
            }
        } catch (ce: CancellationException) {
            runCatching { outputFile.delete() }
            throw ce
        } catch (t: Throwable) {
            runCatching { outputFile.delete() }
            throw t
        } finally {
            runCatching {
                if (muxerStarted) {
                    muxer?.stop()
                }
            }
            runCatching { muxer?.release() }
            runCatching { videoEncoder?.stop() }
            runCatching { videoEncoder?.release() }
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            runCatching { inputSurface?.release() }
            glEnv.release()
            videoSampler.release()
            imageBitmapCache.values.forEach { if (!it.isRecycled) it.recycle() }
            imageBitmapCache.clear()
            pcmChunkCache.clear()
            textBitmapRenderer.clearCache()
        }
    }

    private fun resolveVisualBitmap(
        layer: ResolvedVisualLayer,
        videoSampler: VideoFrameSampler,
        imageCache: MutableMap<String, Bitmap>,
        quantStepUs: Long,
    ): Bitmap? {
        val asset = layer.asset ?: return null
        if (asset.status != MediaStatus.READY) return null
        return if (asset.type == MediaType.VIDEO) {
            val sourceMs = SpeedRampEngine.clipRelativeToSourceMs(
                clip = layer.sourceLayer.clip,
                clipRelativeMs = layer.sourceLayer.clipRelativeMs,
                assetDurationMs = asset.durationMs,
            )
            videoSampler.sampleFrame(asset, sourceMs * 1_000L, quantStepUs)
        } else {
            val cached = imageCache[asset.id]
            if (cached != null && !cached.isRecycled) return cached
            val decoded = decodeSampledImageBitmap(asset, maxDimension = 1920) ?: return null
            imageCache[asset.id] = decoded
            decoded
        }
    }

    private fun decodeSampledImageBitmap(asset: MediaAsset, maxDimension: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(asset)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) return null
            var sampleSize = 1
            while (max(rawW / sampleSize, rawH / sampleSize) > maxDimension) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            openStream(asset)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    private fun openStream(asset: MediaAsset) =
        asset.managedFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.inputStream() else null
        } ?: context.contentResolver.openInputStream(Uri.parse(asset.sourceUri))

    private class MuxerBridge(
        private val expectAudio: Boolean,
    ) {
        private var videoTrackIdx = -1
        private var audioTrackIdx = -1
        private var muxerStarted = false
        var videoEosSeen = false
        var audioEosSeen = false

        private data class PendingPacket(
            val isVideo: Boolean,
            val data: ByteBuffer,
            val info: MediaCodec.BufferInfo,
        )

        private val pending = ArrayList<PendingPacket>()

        fun drainCodec(
            codec: MediaCodec,
            bufferInfo: MediaCodec.BufferInfo,
            muxer: MediaMuxer,
            isVideo: Boolean,
            waitTimeoutUs: Long,
        ): Boolean {
            var loopTimeout = waitTimeoutUs
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, loopTimeout)
                loopTimeout = 0L
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return muxerStarted
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        if (isVideo && videoTrackIdx < 0) {
                            videoTrackIdx = muxer.addTrack(fmt)
                        } else if (!isVideo && audioTrackIdx < 0) {
                            audioTrackIdx = muxer.addTrack(fmt)
                        }
                        if (!muxerStarted && videoTrackIdx >= 0 && (!expectAudio || audioTrackIdx >= 0)) {
                            muxer.start()
                            muxerStarted = true
                            flushPending(muxer)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (outBuf != null && bufferInfo.size > 0 && !isConfig) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            if (muxerStarted) {
                                val trackIdx = if (isVideo) videoTrackIdx else audioTrackIdx
                                if (trackIdx >= 0) {
                                    muxer.writeSampleData(trackIdx, outBuf, bufferInfo)
                                }
                            } else {
                                val copy = ByteBuffer.allocateDirect(bufferInfo.size)
                                copy.put(outBuf)
                                copy.flip()
                                val copyInfo = MediaCodec.BufferInfo().apply {
                                    set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                }
                                pending.add(PendingPacket(isVideo, copy, copyInfo))
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            if (isVideo) videoEosSeen = true else audioEosSeen = true
                            return muxerStarted
                        }
                    }
                }
            }
        }

        private fun flushPending(muxer: MediaMuxer) {
            if (pending.isEmpty()) return
            pending.sortBy { it.info.presentationTimeUs }
            for (pkt in pending) {
                val trackIdx = if (pkt.isVideo) videoTrackIdx else audioTrackIdx
                if (trackIdx >= 0) {
                    muxer.writeSampleData(trackIdx, pkt.data, pkt.info)
                }
            }
            pending.clear()
        }
    }

    private class VideoFrameSampler(
        private val context: Context,
        private val maxSidePx: Int,
    ) {
        private val retrievers = HashMap<String, MediaMetadataRetriever>()
        private val frameCache = object : LinkedHashMap<String, Bitmap>(10, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
                if (size > 8 && eldest != null) {
                    if (!eldest.value.isRecycled) eldest.value.recycle()
                    return true
                }
                return false
            }
        }

        fun sampleFrame(asset: MediaAsset, timeUs: Long, quantStepUs: Long): Bitmap? {
            val safeDurationUs = (asset.durationMs - 20L).coerceAtLeast(0L) * 1_000L
            val clampedUs = if (safeDurationUs > 0L) timeUs.coerceIn(0L, safeDurationUs) else timeUs.coerceAtLeast(0L)
            val quantizedUs = (clampedUs / quantStepUs) * quantStepUs
            val cacheKey = "${asset.id}_$quantizedUs"
            frameCache[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }

            val retriever = getOrCreateRetriever(asset) ?: return null
            val rawW = asset.width.takeIf { it > 0 } ?: 1080
            val rawH = asset.height.takeIf { it > 0 } ?: 1920
            val scale = min(1f, maxSidePx.toFloat() / max(rawW, rawH).toFloat())
            val dstW = (rawW * scale).roundToInt().coerceAtLeast(64)
            val dstH = (rawH * scale).roundToInt().coerceAtLeast(64)

            val bitmap = runCatching {
                retriever.getScaledFrameAtTime(
                    clampedUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    dstW,
                    dstH,
                ) ?: retriever.getScaledFrameAtTime(
                    clampedUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    dstW,
                    dstH,
                ) ?: retriever.getFrameAtTime(
                    clampedUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            }.getOrNull() ?: return null

            frameCache[cacheKey] = bitmap
            return bitmap
        }

        private fun getOrCreateRetriever(asset: MediaAsset): MediaMetadataRetriever? {
            retrievers[asset.id]?.let { return it }
            val retriever = MediaMetadataRetriever()
            val attached = runCatching {
                val managed = asset.managedFilePath?.let { File(it) }?.takeIf { it.exists() && it.canRead() }
                if (managed != null) {
                    retriever.setDataSource(managed.absolutePath)
                } else {
                    retriever.setDataSource(context, Uri.parse(asset.sourceUri))
                }
                true
            }.getOrDefault(false)
            if (!attached) {
                runCatching { retriever.release() }
                return null
            }
            retrievers[asset.id] = retriever
            return retriever
        }

        fun release() {
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
            frameCache.values.forEach { if (!it.isRecycled) it.recycle() }
            frameCache.clear()
        }
    }

    private class SlidingClipPcmCache(
        private val extractor: PcmAudioExtractor,
        private val assetsById: Map<String, MediaAsset>,
    ) {
        private val windowSizeMs = 2_500L
        private val cache = object : LinkedHashMap<String, Pair<Long, DecodedPcmBuffer>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, DecodedPcmBuffer>>?): Boolean {
                return size > 6
            }
        }

        fun getPcmSlice(
            assetId: String,
            sourceStartMs: Long,
            sourceDurationMs: Long,
        ): DecodedPcmBuffer? {
            val asset = assetsById[assetId] ?: return null
            if (asset.type == MediaType.IMAGE || asset.status != MediaStatus.READY) return null

            val chunkIndex = (sourceStartMs / windowSizeMs).coerceAtLeast(0L)
            val key = "${assetId}_$chunkIndex"
            val cached = cache[key] ?: run {
                val chunkStartMs = chunkIndex * windowSizeMs
                val decoded = runBlocking {
                    extractor.decodePcmSegment(
                        asset = asset,
                        sourceStartMs = chunkStartMs,
                        sourceDurationMs = windowSizeMs + 400L,
                        maxFramesCap = 48_000 * 5,
                    )
                } ?: return null
                val entry = chunkStartMs to decoded
                cache[key] = entry
                entry
            }

            val (baseMs, fullBuf) = cached
            val relMs = (sourceStartMs - baseMs).coerceAtLeast(0L)
            val startFrame = ((relMs * fullBuf.sampleRateHz) / 1000L).toInt().coerceAtLeast(0)
            val neededFrames = ((sourceDurationMs * fullBuf.sampleRateHz) / 1000L).toInt().coerceAtLeast(1)
            val totalFrames = fullBuf.interleavedSamples.size / fullBuf.channelCount.coerceAtLeast(1)
            if (startFrame >= totalFrames) return null

            val endFrame = min(totalFrames, startFrame + neededFrames)
            val startIdx = startFrame * fullBuf.channelCount
            val endIdx = endFrame * fullBuf.channelCount
            return DecodedPcmBuffer(
                sampleRateHz = fullBuf.sampleRateHz,
                channelCount = fullBuf.channelCount,
                interleavedSamples = fullBuf.interleavedSamples.copyOfRange(startIdx, endIdx),
            )
        }

        fun clear() = cache.clear()
    }

    private class GlOffscreenEncoderEnv(
        private val width: Int,
        private val height: Int,
    ) {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var program2d = 0
        private var programBlend = 0
        private var programBlit = 0
        private var programInvertText = 0

        private val fboIds = IntArray(3)
        private val fboTexIds = IntArray(3)
        private val uploadTexId = IntArray(1)
        private var dummyLutTexId = 0
        private val lutTextureCache = object : LinkedHashMap<String, Int>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
                if (size > 8 && eldest != null) {
                    GLES20.glDeleteTextures(1, intArrayOf(eldest.value), 0)
                    return true
                }
                return false
            }
        }

        private var baseFboIdx = 0
        private var scratchBaseIdx = 1
        private val layerFboIdx = 2

        private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f,
                ),
            ).apply { position(0) }

        fun init(encoderSurface: Surface) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to obtain EGL14 display" }
            val version = IntArray(2)
            require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "Unable to initialize EGL14" }

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            require(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                "Unable to choose recordable EGLConfig"
            }
            val eglConfig = configs[0] ?: error("No matching recordable EGLConfig")

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, surfAttribs, 0)
            require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed"
            }

            program2d = linkProgram(CompositorShaders.LAYER_VERTEX_SHADER, CompositorShaders.LAYER_FRAGMENT_SHADER_2D)
            programBlend = linkProgram(CompositorShaders.BLIT_VERTEX_SHADER, CompositorShaders.BLEND_FRAGMENT_SHADER)
            programBlit = linkProgram(CompositorShaders.BLIT_VERTEX_SHADER, CompositorShaders.PASSTHROUGH_FRAGMENT_SHADER)
            programInvertText = linkProgram(CompositorShaders.LAYER_VERTEX_SHADER, CompositorShaders.INVERT_TEXT_FRAGMENT_SHADER)

            GLES20.glGenFramebuffers(3, fboIds, 0)
            GLES20.glGenTextures(3, fboTexIds, 0)
            for (i in 0..2) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[i])
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    width,
                    height,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null,
                )
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    fboTexIds[i],
                    0,
                )
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            GLES20.glGenTextures(1, uploadTexId, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTexId[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val dummyArr = IntArray(1)
            GLES20.glGenTextures(1, dummyArr, 0)
            dummyLutTexId = dummyArr[0]
            if (dummyLutTexId != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyLutTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                val px = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
                    setPixel(0, 0, android.graphics.Color.BLACK)
                }
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, px, 0)
                px.recycle()
            }
        }

        fun beginCompositionFrame() {
            baseFboIdx = 0
            scratchBaseIdx = 1
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[baseFboIdx])
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        fun compositeVisualLayer(
            bitmap: Bitmap,
            layer: ResolvedVisualLayer,
            canvasAspect: Float,
            timeSec: Float,
            transitionParams: TransitionVisualParams,
        ) {
            if (bitmap.isRecycled) return
            val texW = bitmap.width.coerceAtLeast(1)
            val texH = bitmap.height.coerceAtLeast(1)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[layerFboIdx])
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program2d)
            bindQuadAttributes(program2d)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTexId[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uTexture"), 0)

            val texMatrix = FloatArray(16).apply {
                Matrix.setIdentityM(this, 0)
                Matrix.translateM(this, 0, 0f, 1f, 0f)
                Matrix.scaleM(this, 0, 1f, -1f, 1f)
            }
            val mvp = GlCompositorMath.buildLayerMvpMatrix(
                layer = layer,
                assetWidth = texW,
                assetHeight = texH,
                canvasAspect = canvasAspect,
                transitionParams = transitionParams,
            )
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program2d, "uMvpMatrix"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program2d, "uTexMatrix"), 1, false, texMatrix, 0)

            val colorUniforms = GlCompositorMath.buildColorUniforms(layer.effectiveAdjustments)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program2d, "uColorMatrix"), 1, false, colorUniforms.matrix4, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program2d, "uColorOffset"), 1, colorUniforms.offset4, 0)

            val combinedOpacity = (layer.effectiveOpacity * transitionParams.alpha).coerceIn(0f, 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uOpacity"), combinedOpacity)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uVignette"), layer.effectiveAdjustments.vignette)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uShaderType"), layer.effectiveShader.type.shaderId)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uShaderIntensity"), layer.effectiveShader.intensity)
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program2d, "uBlurAmount"),
                (layer.effectiveAdjustments.blurRadius + transitionParams.blurRadius).coerceIn(0f, 24f),
            )
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uSharpenAmount"), layer.effectiveAdjustments.sharpen)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uTimeSec"), timeSec)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program2d, "uTexelSize"), 1f / texW, 1f / texH)

            val ck = layer.effectiveChromaKey
            val ckRed = ((ck.keyColorArgb ushr 16) and 0xFF) / 255f
            val ckGreen = ((ck.keyColorArgb ushr 8) and 0xFF) / 255f
            val ckBlue = (ck.keyColorArgb and 0xFF) / 255f
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uChromaEnabled"), if (ck.enabled) 1 else 0)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program2d, "uChromaRgb"), ckRed, ckGreen, ckBlue)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uChromaSimilarity"), ck.similarity)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uChromaSmoothness"), ck.smoothness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uChromaSpill"), ck.spillSuppression)

            val lutTexId = getOrLoadLutTexture(layer.effectiveLut)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            if (lutTexId != 0 && layer.effectiveLut.isActive) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uLutEnabled"), 1)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uLutIntensity"), layer.effectiveLut.intensity)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyLutTexId)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uLutEnabled"), 0)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uLutIntensity"), 0f)
            }
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uLutTexture"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            val wipeActive = layer.activeTransition?.type == TransitionType.WIPE_RIGHT && layer.transitionProgress < 1f
            val mask: LayerMask = layer.mask
            if (wipeActive) {
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uMaskShape"), 3)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(program2d, "uMaskCenter"), layer.transitionProgress, 0.5f)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(program2d, "uMaskSize"), 1f, 1f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uMaskRotationRad"), 0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uMaskFeather"), 0.02f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uMaskCornerRadius"), 0f)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uMaskInverted"), 0)
            } else {
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uMaskShape"), mask.shape.shaderId)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(program2d, "uMaskCenter"), mask.centerX, 1f - mask.centerY)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(program2d, "uMaskSize"), mask.width, mask.height)
                GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program2d, "uMaskRotationRad"),
                    Math.toRadians(mask.rotationDeg.toDouble()).toFloat(),
                )
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uMaskFeather"), mask.feather)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uMaskCornerRadius"), mask.cornerRadius)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uMaskInverted"), if (mask.inverted) 1 else 0)
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            blendLayerFboIntoBase(layer.blendMode.shaderId)
        }

        private fun getOrLoadLutTexture(spec: ClipLutSpec): Int {
            if (!spec.isActive) return 0
            val key = Lut3DEngine.cacheKey(spec)
            lutTextureCache[key]?.let { return it }
            val table = Lut3DEngine.resolveLutTable(spec) ?: return 0
            val stripBmp = Lut3DEngine.packTableToGlStripBitmap(table)
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val texId = texIds[0]
            if (texId == 0) {
                stripBmp.recycle()
                return 0
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, stripBmp, 0)
            stripBmp.recycle()
            lutTextureCache[key] = texId
            return texId
        }

        fun compositeTextLayer(
            rendered: RenderedTextBitmapFrame,
            blendMode: BlendModePreset,
        ) {
            val bmp = rendered.bitmap
            if (bmp.isRecycled) return

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[layerFboIdx])
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program2d)
            bindQuadAttributes(program2d)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTexId[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uTexture"), 0)

            val texMatrix = FloatArray(16).apply {
                Matrix.setIdentityM(this, 0)
                Matrix.translateM(this, 0, 0f, 1f, 0f)
                Matrix.scaleM(this, 0, 1f, -1f, 1f)
            }

            val mvp = GlCompositorMath.buildTextLayerMvpMatrix(
                bitmapWidth = bmp.width,
                bitmapHeight = bmp.height,
                canvasWidth = width,
                canvasHeight = height,
                centerOffsetXNorm = rendered.centerOffsetXNorm,
                centerOffsetYNorm = rendered.centerOffsetYNorm,
                combinedScaleX = rendered.combinedScaleX,
                combinedScaleY = rendered.combinedScaleY,
                rotationDeg = rendered.rotationDeg,
                anchorX = rendered.anchorX,
                anchorY = rendered.anchorY,
            )
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program2d, "uMvpMatrix"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program2d, "uTexMatrix"), 1, false, texMatrix, 0)

            val identityMat4 = GlCompositorMath.identityMatrix4()
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program2d, "uColorMatrix"), 1, false, identityMat4, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program2d, "uColorOffset"), 0f, 0f, 0f, 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uOpacity"), rendered.combinedAlpha)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uVignette"), 0f)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uShaderType"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uShaderIntensity"), 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uBlurAmount"), 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uSharpenAmount"), 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program2d, "uTimeSec"), 0f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program2d, "uTexelSize"), 1f / bmp.width, 1f / bmp.height)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uChromaEnabled"), 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uLutEnabled"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyLutTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uLutTexture"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program2d, "uMaskShape"), 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            blendLayerFboIntoBase(blendMode.shaderId)
        }

        fun compositeInvertTextLayer(
            rendered: RenderedTextBitmapFrame,
        ) {
            val bmp = rendered.bitmap
            if (bmp.isRecycled || programInvertText == 0) return

            // 1. Copy base FBO pixels into scratch FBO so regions outside the text quad stay untouched
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[scratchBaseIdx])
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(programBlit)
            bindQuadAttributes(programBlit)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[baseFboIdx])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlit, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // 2. Run Invert Lyric shader reading fboTexIds[baseFboIdx] & glyph mask texture
            GLES20.glUseProgram(programInvertText)
            bindQuadAttributes(programInvertText)

            val texMatrix = FloatArray(16).apply {
                Matrix.setIdentityM(this, 0)
                Matrix.translateM(this, 0, 0f, 1f, 0f)
                Matrix.scaleM(this, 0, 1f, -1f, 1f)
            }
            val mvp = GlCompositorMath.buildTextLayerMvpMatrix(
                bitmapWidth = bmp.width,
                bitmapHeight = bmp.height,
                canvasWidth = width,
                canvasHeight = height,
                centerOffsetXNorm = rendered.centerOffsetXNorm,
                centerOffsetYNorm = rendered.centerOffsetYNorm,
                combinedScaleX = rendered.combinedScaleX,
                combinedScaleY = rendered.combinedScaleY,
                rotationDeg = rendered.rotationDeg,
                anchorX = rendered.anchorX,
                anchorY = rendered.anchorY,
            )
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programInvertText, "uMvpMatrix"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(programInvertText, "uTexMatrix"), 1, false, texMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[baseFboIdx])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programInvertText, "uBaseTex"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTexId[0])
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programInvertText, "uMaskTex"), 1)

            GLES20.glUniform1f(GLES20.glGetUniformLocation(programInvertText, "uInvertStrength"), rendered.invertStrength)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programInvertText, "uEdgeSoftness"), rendered.invertEdgeSoftness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(programInvertText, "uOpacity"), rendered.combinedAlpha)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(programInvertText, "uMaskTexelSize"),
                1f / bmp.width.coerceAtLeast(1),
                1f / bmp.height.coerceAtLeast(1),
            )

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

            val tmp = baseFboIdx
            baseFboIdx = scratchBaseIdx
            scratchBaseIdx = tmp
        }

        fun submitFrame(ptsNs: Long) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(programBlit)
            bindQuadAttributes(programBlit)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[baseFboIdx])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlit, "uTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        private fun blendLayerFboIntoBase(blendModeId: Int) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[scratchBaseIdx])
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(programBlend)
            bindQuadAttributes(programBlend)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[baseFboIdx])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlend, "uBaseTex"), 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[layerFboIdx])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlend, "uTopTex"), 1)

            GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlend, "uBlendMode"), blendModeId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            val tmp = baseFboIdx
            baseFboIdx = scratchBaseIdx
            scratchBaseIdx = tmp
        }

        private fun bindQuadAttributes(program: Int) {
            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val uvHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            quadVertices.position(0)
            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
            quadVertices.position(2)
            GLES20.glEnableVertexAttribArray(uvHandle)
            GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        }

        private fun linkProgram(vertSrc: String, fragSrc: String): Int {
            val v = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
            val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, v)
            GLES20.glAttachShader(program, f)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e("CutLabExportGL", "Link failed: ${GLES20.glGetProgramInfoLog(program)}")
            }
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            return program
        }

        private fun compileShader(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("CutLabExportGL", "Compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            }
            return shader
        }

        fun release() {
            if (uploadTexId[0] != 0) {
                GLES20.glDeleteTextures(1, uploadTexId, 0)
                uploadTexId.fill(0)
            }
            lutTextureCache.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            lutTextureCache.clear()
            if (dummyLutTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(dummyLutTexId), 0)
                dummyLutTexId = 0
            }
            if (fboIds[0] != 0) {
                GLES20.glDeleteFramebuffers(3, fboIds, 0)
                fboIds.fill(0)
            }
            if (fboTexIds[0] != 0) {
                GLES20.glDeleteTextures(3, fboTexIds, 0)
                fboTexIds.fill(0)
            }
            listOf(program2d, programBlend, programBlit, programInvertText).forEach {
                if (it != 0) GLES20.glDeleteProgram(it)
            }
            program2d = 0
            programBlend = 0
            programBlit = 0
            programInvertText = 0

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }
    }
}
