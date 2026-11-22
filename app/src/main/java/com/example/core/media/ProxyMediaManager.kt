package com.example.core.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface ProxyJobState {
    data object Idle : ProxyJobState
    data class Running(
        val assetId: String,
        val assetName: String,
        val progress: Float,
    ) : ProxyJobState
    data class Completed(
        val assetId: String,
        val assetName: String,
        val proxyPath: String,
    ) : ProxyJobState
    data class Failed(
        val assetId: String,
        val message: String,
    ) : ProxyJobState
}

/**
 * Generates lightweight 540p H.264 editing proxies into `filesDir/cutlab_proxies/`
 * using Media3 Transformer. Proxies are used solely for low-latency timeline preview
 * while exports always read the full-resolution original asset.
 */
@OptIn(UnstableApi::class)
class ProxyMediaManager(
    private val context: Context,
    private val projectRepository: ProjectRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ProxyJobState>(ProxyJobState.Idle)
    val state: StateFlow<ProxyJobState> = _state.asStateFlow()

    private var activeTransformer: Transformer? = null
    private var activeCancelled = AtomicBoolean(false)

    private val proxyRootDir: File
        get() = File(context.filesDir, "cutlab_proxies").apply { mkdirs() }

    fun generateProxyForAsset(asset: MediaAsset) {
        if (asset.type != MediaType.VIDEO) {
            _state.value = ProxyJobState.Failed(asset.id, "Proxy generation is only needed for video assets.")
            return
        }
        val current = _state.value
        if (current is ProxyJobState.Running) return

        val outputFile = File(proxyRootDir, "${asset.id}_540p.mp4")
        activeCancelled.set(false)
        _state.value = ProxyJobState.Running(asset.id, asset.displayName, 0.02f)

        scope.launch {
            try {
                transcodeTo540pProxy(asset, outputFile)
                if (!activeCancelled.get() && outputFile.exists() && outputFile.length() > 0L) {
                    projectRepository.updateAssetProxyPath(asset.id, outputFile.absolutePath)
                    _state.value = ProxyJobState.Completed(
                        assetId = asset.id,
                        assetName = asset.displayName,
                        proxyPath = outputFile.absolutePath,
                    )
                } else {
                    runCatching { outputFile.delete() }
                    _state.value = ProxyJobState.Idle
                }
            } catch (t: Throwable) {
                runCatching { outputFile.delete() }
                if (!activeCancelled.get()) {
                    _state.value = ProxyJobState.Failed(
                        assetId = asset.id,
                        message = t.message ?: "Proxy transcode failed.",
                    )
                } else {
                    _state.value = ProxyJobState.Idle
                }
            } finally {
                activeTransformer = null
            }
        }
    }

    fun cancelActiveProxyJob() {
        activeCancelled.set(true)
        mainHandler.post {
            runCatching { activeTransformer?.cancel() }
            activeTransformer = null
        }
        _state.value = ProxyJobState.Idle
    }

    suspend fun deleteProxyForAsset(asset: MediaAsset) {
        asset.proxyFilePath?.let { path ->
            runCatching { File(path).delete() }
        }
        projectRepository.updateAssetProxyPath(asset.id, null)
        if ((_state.value as? ProxyJobState.Completed)?.assetId == asset.id) {
            _state.value = ProxyJobState.Idle
        }
    }

    fun clearStatusBanner() {
        if (_state.value !is ProxyJobState.Running) {
            _state.value = ProxyJobState.Idle
        }
    }

    private suspend fun transcodeTo540pProxy(asset: MediaAsset, outputFile: File) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val finished = AtomicBoolean(false)
            val progressHolder = ProgressHolder()
            val (targetW, targetH) = compute540pDimensions(asset.width, asset.height)

            val pollRunnable = object : Runnable {
                override fun run() {
                    if (finished.get()) return
                    val tr = activeTransformer ?: return
                    val progressState = tr.getProgress(progressHolder)
                    val p = if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        (progressHolder.progress / 100f).coerceIn(0.02f, 0.96f)
                    } else {
                        0.05f
                    }
                    _state.value = ProxyJobState.Running(asset.id, asset.displayName, p)
                    mainHandler.postDelayed(this, 180L)
                }
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    mainHandler.post {
                        mainHandler.removeCallbacks(pollRunnable)
                        runCatching { activeTransformer?.cancel() }
                    }
                    runCatching { outputFile.delete() }
                }
            }

            mainHandler.post {
                if (finished.get()) return@post
                try {
                    if (outputFile.exists()) outputFile.delete()
                    outputFile.parentFile?.mkdirs()

                    val presentation = Presentation.createForWidthAndHeight(
                        targetW,
                        targetH,
                        Presentation.LAYOUT_SCALE_TO_FIT,
                    )
                    val videoEffects: List<Effect> = listOf(presentation)
                    val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(asset.playbackUri))
                        .setEffects(Effects(emptyList(), videoEffects))
                        .build()

                    val transformer = Transformer.Builder(context)
                        .setLooper(Looper.getMainLooper())
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    mainHandler.removeCallbacks(pollRunnable)
                                    if (finished.compareAndSet(false, true)) {
                                        continuation.resume(Unit)
                                    }
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    mainHandler.removeCallbacks(pollRunnable)
                                    if (finished.compareAndSet(false, true)) {
                                        continuation.resumeWithException(exportException)
                                    }
                                }
                            },
                        )
                        .build()

                    activeTransformer = transformer
                    transformer.start(editedItem, outputFile.absolutePath)
                    mainHandler.postDelayed(pollRunnable, 180L)
                } catch (t: Throwable) {
                    mainHandler.removeCallbacks(pollRunnable)
                    if (finished.compareAndSet(false, true)) {
                        continuation.resumeWithException(t)
                    }
                }
            }
        }

    companion object {
        fun compute540pDimensions(srcW: Int, srcH: Int): Pair<Int, Int> {
            val w = if (srcW > 0) srcW else 1280
            val h = if (srcH > 0) srcH else 720
            val shortSide = min(w, h)
            val scale = if (shortSide > 540) 540f / shortSide.toFloat() else 1f
            fun even(v: Float): Int = ((v.roundToInt() / 2) * 2).coerceAtLeast(64)
            return even(w * scale) to even(h * scale)
        }
    }
}
