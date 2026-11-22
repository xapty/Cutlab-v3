package com.example

import android.content.ComponentCallbacks2
import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.example.core.database.CutLabDatabase
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.media.MediaMetadataExtractor
import com.example.core.media.ProxyMediaManager
import com.example.core.performance.DevicePerformanceMonitor
import com.example.core.storage.MediaStorageManager
import com.example.engine.audio.MixedAudioPreviewPlayer
import com.example.engine.audio.MultiTrackAudioMixer
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.export.CustomGlVideoExporter
import com.example.engine.export.CutLabExportCoordinator
import com.example.engine.export.ExportOutputManager
import com.example.engine.export.ExportPlanner
import com.example.engine.export.Media3TransformerExporter
import com.example.engine.project.ProjectEngine
import com.example.engine.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val applicationContext: Context = context.applicationContext
    private val appContext = applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: CutLabDatabase by lazy { CutLabDatabase.getInstance(appContext) }
    val preferencesRepository: CutLabPreferencesRepository by lazy {
        CutLabPreferencesRepository(appContext)
    }
    val performanceMonitor: DevicePerformanceMonitor by lazy {
        DevicePerformanceMonitor(appContext).also { monitor ->
            monitor.registerTrimListener { level ->
                if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                ) {
                    pcmAudioExtractor.trimMemoryCache()
                    runCatching { imageLoader.memoryCache?.clear() }
                }
            }
        }
    }
    val storageManager: MediaStorageManager by lazy { MediaStorageManager(appContext) }
    val metadataExtractor: MediaMetadataExtractor by lazy {
        MediaMetadataExtractor(storageManager)
    }
    val pcmAudioExtractor: PcmAudioExtractor by lazy { PcmAudioExtractor(appContext) }
    val mixedAudioPreviewPlayer: MixedAudioPreviewPlayer by lazy { MixedAudioPreviewPlayer() }
    val projectEngine: ProjectEngine by lazy { ProjectEngine() }
    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(
            projectDao = database.projectDao(),
            mediaAssetDao = database.mediaAssetDao(),
            projectEngine = projectEngine,
            storageManager = storageManager,
            metadataExtractor = metadataExtractor,
        )
    }
    val proxyMediaManager: ProxyMediaManager by lazy {
        ProxyMediaManager(
            context = appContext,
            projectRepository = projectRepository,
        )
    }
    val exportPlanner: ExportPlanner by lazy { ExportPlanner() }
    val exportOutputManager: ExportOutputManager by lazy { ExportOutputManager(appContext) }
    val exportCoordinator: CutLabExportCoordinator by lazy {
        CutLabExportCoordinator(
            exportPlanner = exportPlanner,
            outputManager = exportOutputManager,
            media3Exporter = Media3TransformerExporter(appContext),
            customGlExporter = CustomGlVideoExporter(
                context = appContext,
                pcmAudioExtractor = pcmAudioExtractor,
                audioMixer = MultiTrackAudioMixer(),
            ),
            performanceMonitor = performanceMonitor,
        )
    }
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    fun initializeRuntimeServices() {
        performanceMonitor.installCrashRecorder()
        appScope.launch {
            runCatching {
                preferencesRepository.preferencesFlow.collect { prefs ->
                    performanceMonitor.setPreviewQualityMode(prefs.previewQualityMode)
                }
            }
        }
        appScope.launch {
            runCatching { exportOutputManager.cleanStaleExports() }
        }
    }

    fun onTrimMemory(level: Int) {
        performanceMonitor.onTrimMemory(level)
    }
}
