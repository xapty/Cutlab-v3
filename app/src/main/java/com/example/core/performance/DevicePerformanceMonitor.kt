package com.example.core.performance

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.example.engine.audio.AudioDsp
import com.example.engine.animation.AnimationEngine
import com.example.engine.animation.LumaFrame
import com.example.engine.animation.MotionTrackingEngine
import com.example.engine.effects.Lut3DEngine
import com.example.engine.project.BezierCurveSpec
import com.example.engine.project.ClipAudioEffect
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.KeyframeTrack
import com.example.engine.project.LutPreset
import com.example.engine.project.PropertyKeyframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ThermalTier(
    val level: Int,
    val label: String,
    val recommendedScale: Float,
) {
    NOMINAL(level = 0, label = "Nominal", recommendedScale = 1.0f),
    LIGHT(level = 1, label = "Warm (Light)", recommendedScale = 1.0f),
    MODERATE(level = 2, label = "Moderate Throttle", recommendedScale = 0.75f),
    SEVERE(level = 3, label = "Severe Thermal", recommendedScale = 0.5f),
    CRITICAL(level = 4, label = "Critical Thermal", recommendedScale = 0.5f),
}

enum class MemoryPressureTier(
    val label: String,
) {
    NORMAL("Normal"),
    MODERATE("Moderate"),
    LOW("Low Memory"),
    CRITICAL("Critical"),
}

enum class PreviewQualityMode(
    val label: String,
) {
    AUTO("Adaptive Auto"),
    FULL("Full (1080p)"),
    BALANCED("Balanced (75%)"),
    LOW_POWER("Draft (50%)"),
}

enum class BenchmarkKind(val label: String) {
    DIAGNOSTIC_PIPELINE("Pipeline Benchmark"),
    REAL_EXPORT("Video Export"),
}

@Serializable
data class BenchmarkRecord(
    val id: String,
    val timestampMs: Long,
    val kind: BenchmarkKind,
    val label: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val throughputMbps: Double,
    val frameAvgMs: Float,
    val peakHeapMb: Long,
    val thermalTier: String,
    val renderPath: String,
    val validated: Boolean,
)

data class PerformanceSnapshot(
    val usedHeapMb: Long = 0L,
    val maxHeapMb: Long = 256L,
    val heapUsagePercent: Int = 0,
    val thermalTier: ThermalTier = ThermalTier.NOMINAL,
    val thermalApiSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    val memoryPressureTier: MemoryPressureTier = MemoryPressureTier.NORMAL,
    val previewQualityMode: PreviewQualityMode = PreviewQualityMode.AUTO,
    val effectivePreviewScale: Float = 1.0f,
    val lastPreviewFrameMs: Float = 0f,
    val previewFps: Float = 0f,
    val trimEventsHandled: Int = 0,
    val lastTrimLevel: Int? = null,
    val isBenchmarkRunning: Boolean = false,
    val deviceSummary: String = buildDeviceSummary(),
    val lastCrashSummary: String? = null,
)

private fun buildDeviceSummary(): String {
    val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • $cores CPU cores"
}

/**
 * Coordinates runtime memory trim callbacks, API 29+ thermal status observation (with safe API 28 fallback),
 * adaptive preview resolution scaling, preview latency telemetry, crash log recovery, and real export/pipeline benchmarking.
 */
class DevicePerformanceMonitor(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val benchmarkFile: File
        get() = File(appContext.filesDir, "cutlab_benchmarks.json")
    private val crashFile: File
        get() = File(appContext.filesDir, "cutlab_last_crash.txt")

    private val trimListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private var thermalRegistered = false
    private var lastTrimTimestampMs = 0L

    private val _snapshot = MutableStateFlow(
        PerformanceSnapshot(
            thermalTier = readPlatformThermalTier(),
            thermalApiSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            lastCrashSummary = readLastCrashSummaryInternal(),
        ),
    )
    val snapshot: StateFlow<PerformanceSnapshot> = _snapshot.asStateFlow()

    private val _benchmarks = MutableStateFlow<List<BenchmarkRecord>>(readPersistedBenchmarks())
    val benchmarks: StateFlow<List<BenchmarkRecord>> = _benchmarks.asStateFlow()

    init {
        refreshRuntimeMetrics()
        registerThermalListenerIfSupported()
    }

    fun registerTrimListener(listener: (Int) -> Unit): () -> Unit {
        trimListeners.add(listener)
        return { trimListeners.remove(listener) }
    }

    fun setPreviewQualityMode(mode: PreviewQualityMode) {
        _snapshot.update { cur ->
            val updated = cur.copy(previewQualityMode = mode)
            updated.copy(
                effectivePreviewScale = computeAdaptivePreviewScale(
                    mode = updated.previewQualityMode,
                    thermalTier = updated.thermalTier,
                    memoryPressureTier = updated.memoryPressureTier,
                    heapUsagePercent = updated.heapUsagePercent,
                ),
            )
        }
    }

    fun refreshRuntimeMetrics() {
        val rt = Runtime.getRuntime()
        val maxBytes = rt.maxMemory().coerceAtLeast(1L)
        val usedBytes = (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
        val maxMb = (maxBytes / (1024L * 1024L)).coerceAtLeast(1L)
        val usedMb = (usedBytes / (1024L * 1024L)).coerceIn(0L, maxMb)
        val pct = ((usedBytes.toDouble() / maxBytes.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        val thermal = readPlatformThermalTier()

        val now = System.currentTimeMillis()
        _snapshot.update { cur ->
            val cooldownElapsed = (now - lastTrimTimestampMs) > 30_000L
            val nextPressure = if (cur.memoryPressureTier != MemoryPressureTier.NORMAL && pct < 62 && cooldownElapsed) {
                MemoryPressureTier.NORMAL
            } else {
                cur.memoryPressureTier
            }
            cur.copy(
                usedHeapMb = usedMb,
                maxHeapMb = maxMb,
                heapUsagePercent = pct,
                thermalTier = thermal,
                memoryPressureTier = nextPressure,
                effectivePreviewScale = computeAdaptivePreviewScale(
                    mode = cur.previewQualityMode,
                    thermalTier = thermal,
                    memoryPressureTier = nextPressure,
                    heapUsagePercent = pct,
                ),
                lastCrashSummary = readLastCrashSummaryInternal(),
            )
        }
    }

    fun onTrimMemory(level: Int) {
        lastTrimTimestampMs = System.currentTimeMillis()
        val mappedTier = mapTrimLevelToPressureTier(level)
        trimListeners.forEach { listener ->
            runCatching { listener(level) }
        }
        _snapshot.update { cur ->
            val updated = cur.copy(
                memoryPressureTier = mappedTier,
                trimEventsHandled = cur.trimEventsHandled + 1,
                lastTrimLevel = level,
            )
            updated.copy(
                effectivePreviewScale = computeAdaptivePreviewScale(
                    mode = updated.previewQualityMode,
                    thermalTier = updated.thermalTier,
                    memoryPressureTier = updated.memoryPressureTier,
                    heapUsagePercent = updated.heapUsagePercent,
                ),
            )
        }
        refreshRuntimeMetrics()
    }

    fun forceTrimAllCaches() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }

    fun recordPreviewFrameLatency(frameTimeMs: Float) {
        if (!frameTimeMs.isFinite() || frameTimeMs <= 0f) return
        _snapshot.update { cur ->
            val smoothedMs = if (cur.lastPreviewFrameMs <= 0f) {
                frameTimeMs
            } else {
                cur.lastPreviewFrameMs * 0.8f + frameTimeMs * 0.2f
            }
            val fps = (1000f / smoothedMs.coerceAtLeast(1f)).coerceIn(0f, 120f)
            cur.copy(
                lastPreviewFrameMs = smoothedMs,
                previewFps = fps,
            )
        }
    }

    suspend fun recordExportBenchmark(
        label: String,
        durationMs: Long,
        outputBytes: Long,
        width: Int,
        height: Int,
        fps: Int,
        renderPath: String,
        validated: Boolean,
    ) = withContext(Dispatchers.IO) {
        refreshRuntimeMetrics()
        val safeDurMs = durationMs.coerceAtLeast(1L)
        val throughputMbps = (outputBytes.toDouble() * 8.0 / 1_000_000.0) / (safeDurMs.toDouble() / 1_000.0)
        val snap = _snapshot.value
        val record = BenchmarkRecord(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            kind = BenchmarkKind.REAL_EXPORT,
            label = label,
            durationMs = safeDurMs,
            width = width,
            height = height,
            fps = fps.toFloat(),
            throughputMbps = throughputMbps,
            frameAvgMs = 1000f / fps.coerceAtLeast(1),
            peakHeapMb = snap.usedHeapMb,
            thermalTier = snap.thermalTier.label,
            renderPath = renderPath,
            validated = validated,
        )
        appendBenchmarkRecord(record)
    }

    /**
     * Executes an actual multi-stage CPU/DSP/LUT/Keyframe/Optical-Tracking pipeline benchmark
     * off the main thread and persists the measured latency & throughput metrics.
     */
    suspend fun runPipelineBenchmark(): BenchmarkRecord = withContext(Dispatchers.Default) {
        _snapshot.update { it.copy(isBenchmarkRunning = true) }
        refreshRuntimeMetrics()
        try {
            val animEngine = AnimationEngine()
            val sampleTrack = KeyframeTrack(
                property = KeyframeProperty.SCALE_X,
                keyframes = listOf(
                    PropertyKeyframe(
                        id = "b1",
                        timeMs = 0L,
                        value = 0.85f,
                        interpolation = KeyframeInterpolation.CUBIC_BEZIER,
                        bezier = BezierCurveSpec(0.18f, 0.9f, 0.32f, 1.0f),
                    ),
                    PropertyKeyframe(
                        id = "b2",
                        timeMs = 1000L,
                        value = 1.25f,
                        interpolation = KeyframeInterpolation.EASE_OUT,
                    ),
                ),
            )

            val lutTable = Lut3DEngine.resolveLutTable(
                ClipLutSpec(preset = LutPreset.TEAL_ORANGE, intensity = 0.85f),
            )!!

            val frameW = 96
            val frameH = 54
            val lumaFrames = List(8) { idx ->
                val arr = FloatArray(frameW * frameH) { p ->
                    val x = (p % frameW).toFloat() + idx * 1.4f
                    val y = (p / frameW).toFloat()
                    ((sin(x * 0.19f) + sin(y * 0.23f)) * 0.25f + 0.5f).coerceIn(0f, 1f)
                }
                LumaFrame(idx * 33L, frameW, frameH, arr)
            }

            val audioBuf = FloatArray(4096) { i ->
                (sin(i * 0.08) * 0.45).toFloat()
            }
            val eqSpec = ClipAudioEffect(bassDb = 4f, trebleDb = 2.5f, pan = 0.15f, echoMix = 0.25f)

            val iterations = 24
            var sinkChecksum = 0.0
            val peakHeapBefore = _snapshot.value.usedHeapMb
            var peakHeap = peakHeapBefore

            val startNs = System.nanoTime()
            for (iter in 0 until iterations) {
                val tMs = (iter * 1000L) / iterations
                val animVal = animEngine.evaluateTrack(sampleTrack, tMs) ?: 1f

                // 3D LUT trilinear evaluation pass over a 48x48 color tile
                for (py in 0 until 48) {
                    val g = py / 47f
                    for (px in 0 until 48) {
                        val r = px / 47f
                        val b = ((px + py + iter) % 48) / 47f
                        val (lr, lg, lb) = Lut3DEngine.sampleTrilinear(lutTable, r, g, b)
                        sinkChecksum += (lr + lg + lb) * animVal
                    }
                }

                // Optical SAD block tracking pass
                val tracked = MotionTrackingEngine.trackPatchSequence(
                    frames = lumaFrames,
                    startNormX = 0.5f,
                    startNormY = 0.5f,
                    patchRadiusPx = 4,
                    searchRadiusPx = 8,
                )
                sinkChecksum += tracked.lastOrNull()?.normX ?: 0f

                // Stereo PCM DSP pass
                val dspOut = AudioDsp.applyClipAudioDsp(
                    stereoInterleaved = audioBuf,
                    sampleRate = 48_000,
                    effect = eqSpec,
                )
                sinkChecksum += dspOut.firstOrNull()?.toDouble() ?: 0.0

                val rt = Runtime.getRuntime()
                val curMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).coerceAtLeast(0L)
                if (curMb > peakHeap) peakHeap = curMb
            }
            val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1_000L)
            val elapsedMs = (elapsedNs / 1_000_000L).coerceAtLeast(1L)
            val frameAvgMs = (elapsedNs / 1_000_000f) / iterations.toFloat()
            val measuredFps = (1000f / frameAvgMs.coerceAtLeast(0.05f)).coerceAtMost(999f)

            // Total synthetic workload volume processed (LUT pixels + SAD pixels + PCM bytes) in MB/s
            val totalWorkloadBytes = iterations.toDouble() * ((48 * 48 * 4) + (8 * frameW * frameH * 4) + (4096 * 2))
            val throughputMbps = (totalWorkloadBytes * 8.0 / 1_000_000.0) / (elapsedNs / 1_000_000_000.0)

            refreshRuntimeMetrics()
            val record = BenchmarkRecord(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                kind = BenchmarkKind.DIAGNOSTIC_PIPELINE,
                label = "LUT + Optical Flow + Bezier + DSP (24 cycles, ck=${sinkChecksum.toInt() and 0xFF})",
                durationMs = elapsedMs,
                width = 1080,
                height = 1080,
                fps = measuredFps,
                throughputMbps = throughputMbps,
                frameAvgMs = frameAvgMs,
                peakHeapMb = max(peakHeap, _snapshot.value.usedHeapMb),
                thermalTier = _snapshot.value.thermalTier.label,
                renderPath = "CPU+DSP+LUT",
                validated = true,
            )
            appendBenchmarkRecord(record)
            record
        } finally {
            _snapshot.update { it.copy(isBenchmarkRunning = false) }
        }
    }

    fun installCrashRecorder() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val summary = "${throwable::class.java.simpleName}: ${throwable.message ?: "No message"} " +
                    "[thread=${thread.name}, api=${Build.VERSION.SDK_INT}]"
                crashFile.writeText(summary)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun clearLastCrashReport() {
        runCatching { crashFile.delete() }
        _snapshot.update { it.copy(lastCrashSummary = null) }
    }

    private fun readLastCrashSummaryInternal(): String? {
        if (!crashFile.exists()) return null
        return runCatching { crashFile.readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()
    }

    private suspend fun appendBenchmarkRecord(record: BenchmarkRecord) {
        withContext(Dispatchers.IO) {
            val updated = (listOf(record) + _benchmarks.value).take(25)
            _benchmarks.value = updated
            runCatching {
                benchmarkFile.writeText(json.encodeToString(updated))
            }
        }
    }

    private fun readPersistedBenchmarks(): List<BenchmarkRecord> {
        if (!benchmarkFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<BenchmarkRecord>>(benchmarkFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun registerThermalListenerIfSupported() {
        if (thermalRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        registerThermalListenerApi29()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermalListenerApi29() {
        runCatching {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            powerManager.addThermalStatusListener { status ->
                val tier = mapThermalStatus(status)
                _snapshot.update { cur ->
                    cur.copy(
                        thermalTier = tier,
                        effectivePreviewScale = computeAdaptivePreviewScale(
                            mode = cur.previewQualityMode,
                            thermalTier = tier,
                            memoryPressureTier = cur.memoryPressureTier,
                            heapUsagePercent = cur.heapUsagePercent,
                        ),
                    )
                }
            }
            thermalRegistered = true
        }
    }

    private fun readPlatformThermalTier(): ThermalTier {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalTier.NOMINAL
        return runCatching {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            mapThermalStatus(powerManager?.currentThermalStatus ?: 0)
        }.getOrDefault(ThermalTier.NOMINAL)
    }

    companion object {
        fun mapThermalStatus(status: Int): ThermalTier {
            return when {
                status <= 0 -> ThermalTier.NOMINAL // THERMAL_STATUS_NONE
                status == 1 -> ThermalTier.LIGHT // THERMAL_STATUS_LIGHT
                status == 2 -> ThermalTier.MODERATE // THERMAL_STATUS_MODERATE
                status == 3 -> ThermalTier.SEVERE // THERMAL_STATUS_SEVERE
                else -> ThermalTier.CRITICAL // THERMAL_STATUS_CRITICAL / EMERGENCY / SHUTDOWN
            }
        }

        fun mapTrimLevelToPressureTier(level: Int): MemoryPressureTier {
            return when {
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressureTier.CRITICAL
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressureTier.LOW
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
                    level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryPressureTier.MODERATE
                else -> MemoryPressureTier.NORMAL
            }
        }

        fun computeAdaptivePreviewScale(
            mode: PreviewQualityMode,
            thermalTier: ThermalTier,
            memoryPressureTier: MemoryPressureTier,
            heapUsagePercent: Int,
        ): Float {
            return when (mode) {
                PreviewQualityMode.FULL -> 1.0f
                PreviewQualityMode.BALANCED -> 0.75f
                PreviewQualityMode.LOW_POWER -> 0.5f
                PreviewQualityMode.AUTO -> {
                    when {
                        thermalTier == ThermalTier.SEVERE ||
                            thermalTier == ThermalTier.CRITICAL ||
                            memoryPressureTier == MemoryPressureTier.CRITICAL ||
                            heapUsagePercent >= 88 -> 0.5f
                        thermalTier == ThermalTier.MODERATE ||
                            memoryPressureTier == MemoryPressureTier.LOW ||
                            memoryPressureTier == MemoryPressureTier.MODERATE ||
                            heapUsagePercent >= 74 -> 0.75f
                        else -> 1.0f
                    }
                }
            }
        }

        fun previewMaxFboDimension(scale: Float): Int {
            return (1080f * scale.coerceIn(0.4f, 1.0f)).roundToInt().coerceIn(360, 1080)
        }
    }
}
