package com.example.engine.export

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.engine.project.ClipKind
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TrackType
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Path A exporter powered by AndroidX Media3 Transformer.
 * Executes hardware-accelerated transcode/trim/scale/speed pipelines for sequential timelines.
 */
@OptIn(UnstableApi::class)
class Media3TransformerExporter(
    private val context: Context,
) {

    suspend fun export(
        document: ProjectDocument,
        assetsById: Map<String, MediaAsset>,
        plan: ExportPlan,
        outputFile: File,
        onProgress: (progress: Float, statusMessage: String) -> Unit,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val thread = HandlerThread("CutLabMedia3Transformer").apply { start() }
        val handler = Handler(thread.looper)
        val finished = AtomicBoolean(false)
        val progressHolder = ProgressHolder()

        fun cleanup() {
            handler.removeCallbacksAndMessages(null)
            runCatching { thread.quitSafely() }
        }

        val composition = buildComposition(document, assetsById, plan)
        if (composition == null) {
            cleanup()
            continuation.resumeWithException(
                IllegalStateException("Timeline contains no valid media items for Media3 Transformer."),
            )
            return@suspendCancellableCoroutine
        }

        var transformerRef: Transformer? = null
        val pollRunnable = object : Runnable {
            override fun run() {
                if (finished.get()) return
                val tr = transformerRef ?: return
                val state = tr.getProgress(progressHolder)
                val normalized = when (state) {
                    Transformer.PROGRESS_STATE_AVAILABLE ->
                        (progressHolder.progress / 100f).coerceIn(0f, 0.96f)
                    Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> 0.04f
                    else -> 0.08f
                }
                val pct = (normalized * 100).toInt()
                onProgress(normalized, "Path A (Media3 Transformer): $pct%")
                handler.postDelayed(this, 180L)
            }
        }

        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) {
                handler.post {
                    runCatching { transformerRef?.cancel() }
                    cleanup()
                }
                runCatching { outputFile.delete() }
            }
        }

        handler.post {
            if (finished.get()) {
                cleanup()
                return@post
            }
            try {
                if (outputFile.exists()) outputFile.delete()
                outputFile.parentFile?.mkdirs()

                val transformer = Transformer.Builder(context)
                    .setLooper(thread.looper)
                    .setVideoMimeType(plan.codec.mimeType)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                if (finished.compareAndSet(false, true)) {
                                    cleanup()
                                    onProgress(0.96f, "Finalizing MP4 container...")
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                if (finished.compareAndSet(false, true)) {
                                    cleanup()
                                    runCatching { outputFile.delete() }
                                    val detail = exportException.message ?: exportException.errorCodeName
                                    continuation.resumeWithException(
                                        IllegalStateException("Media3 export error ($detail)", exportException),
                                    )
                                }
                            }
                        },
                    )
                    .build()

                transformerRef = transformer
                onProgress(0.02f, "Starting Media3 Transformer...")
                transformer.start(composition, outputFile.absolutePath)
                handler.postDelayed(pollRunnable, 180L)
            } catch (t: Throwable) {
                if (finished.compareAndSet(false, true)) {
                    cleanup()
                    runCatching { outputFile.delete() }
                    continuation.resumeWithException(t)
                }
            }
        }
    }

    private fun buildComposition(
        document: ProjectDocument,
        assetsById: Map<String, MediaAsset>,
        plan: ExportPlan,
    ): Composition? {
        val activeTracks = document.tracks.filterNot { it.isMuted }
        val videoTrack = activeTracks.firstOrNull {
            (it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY) && it.clips.isNotEmpty()
        }
        val audioTrack = activeTracks.firstOrNull {
            it.type == TrackType.AUDIO && it.clips.isNotEmpty()
        }

        val presentation = Presentation.createForWidthAndHeight(
            plan.outputWidth,
            plan.outputHeight,
            Presentation.LAYOUT_SCALE_TO_FIT,
        )

        val sequences = mutableListOf<EditedMediaItemSequence>()

        if (videoTrack != null) {
            val visualItems = videoTrack.clips
                .sortedBy { it.timelineStartMs }
                .mapNotNull { clip ->
                    if (clip.clipKind != ClipKind.MEDIA) return@mapNotNull null
                    val asset = assetsById[clip.assetId] ?: return@mapNotNull null
                    if (asset.status != MediaStatus.READY) return@mapNotNull null

                    val mediaBuilder = MediaItem.Builder().setUri(asset.playbackUri)
                    if (asset.type == MediaType.IMAGE) {
                        mediaBuilder.setImageDurationMs(clip.durationMs.coerceAtLeast(100L))
                    } else {
                        val srcStart = clip.sourceStartMs.coerceAtLeast(0L)
                        val srcEnd = (srcStart + com.example.core.common.TimelineTimebase.scaleTimelineToSourceMs(clip.durationMs, clip.speed))
                            .coerceAtLeast(srcStart + 50L)
                            .let { computedEnd ->
                                if (asset.durationMs > 0L) computedEnd.coerceAtMost(asset.durationMs) else computedEnd
                            }
                        mediaBuilder.setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(srcStart)
                                .setEndPositionMs(srcEnd)
                                .build(),
                        )
                    }

                    val speedChanged = abs(clip.speed - 1f) > 0.01f && asset.type == MediaType.VIDEO
                    val videoEffects = mutableListOf<Effect>(presentation)
                    if (speedChanged) {
                        videoEffects.add(SpeedChangeEffect(clip.speed.coerceIn(0.25f, 4.0f)))
                    }
                    val audioProcessors = mutableListOf<AudioProcessor>()
                    val removeAudio = asset.type == MediaType.IMAGE || clip.isMuted || clip.volume <= 0.001f
                    if (!removeAudio && speedChanged) {
                        val sonic = SonicAudioProcessor()
                        sonic.setSpeed(clip.speed.coerceIn(0.25f, 4.0f))
                        audioProcessors.add(sonic)
                    }

                    EditedMediaItem.Builder(mediaBuilder.build())
                        .setRemoveAudio(removeAudio)
                        .apply {
                            if (asset.type == MediaType.IMAGE) {
                                setFrameRate(plan.fps)
                            }
                        }
                        .setEffects(Effects(audioProcessors, videoEffects))
                        .build()
                }

            if (visualItems.isNotEmpty()) {
                sequences.add(EditedMediaItemSequence.Builder(visualItems).build())
            }
        }

        if (audioTrack != null) {
            val audioItems = audioTrack.clips
                .sortedBy { it.timelineStartMs }
                .mapNotNull { clip ->
                    if (clip.clipKind != ClipKind.MEDIA || clip.isMuted || clip.volume <= 0.001f) {
                        return@mapNotNull null
                    }
                    val asset = assetsById[clip.assetId] ?: return@mapNotNull null
                    if (asset.type == MediaType.IMAGE || asset.status != MediaStatus.READY) return@mapNotNull null

                    val srcStart = clip.sourceStartMs.coerceAtLeast(0L)
                    val srcEnd = (srcStart + com.example.core.common.TimelineTimebase.scaleTimelineToSourceMs(clip.durationMs, clip.speed))
                        .coerceAtLeast(srcStart + 50L)
                        .let { computedEnd ->
                            if (asset.durationMs > 0L) computedEnd.coerceAtMost(asset.durationMs) else computedEnd
                        }
                    val mediaItem = MediaItem.Builder()
                        .setUri(asset.playbackUri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(srcStart)
                                .setEndPositionMs(srcEnd)
                                .build(),
                        )
                        .build()

                    val audioProcessors = mutableListOf<AudioProcessor>()
                    if (abs(clip.speed - 1f) > 0.01f) {
                        val sonic = SonicAudioProcessor()
                        sonic.setSpeed(clip.speed.coerceIn(0.25f, 4.0f))
                        audioProcessors.add(sonic)
                    }

                    EditedMediaItem.Builder(mediaItem)
                        .setRemoveVideo(true)
                        .setEffects(Effects(audioProcessors, emptyList()))
                        .build()
                }
            if (audioItems.isNotEmpty()) {
                sequences.add(EditedMediaItemSequence.Builder(audioItems).build())
            }
        }

        if (sequences.isEmpty()) return null
        return Composition.Builder(sequences).build()
    }
}
