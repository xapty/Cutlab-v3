package com.example.core.performance

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.engine.export.ExportOutputManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class DevicePerformanceMonitorTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "cutlab_benchmarks.json").delete()
        File(context.filesDir, "cutlab_last_crash.txt").delete()
    }

    @After
    fun teardown() {
        File(context.filesDir, "cutlab_benchmarks.json").delete()
        File(context.filesDir, "cutlab_last_crash.txt").delete()
    }

    @Test
    fun adaptiveScale_respondsToThermalMemoryAndManualOverrides() {
        assertEquals(
            1.0f,
            DevicePerformanceMonitor.computeAdaptivePreviewScale(
                mode = PreviewQualityMode.AUTO,
                thermalTier = ThermalTier.NOMINAL,
                memoryPressureTier = MemoryPressureTier.NORMAL,
                heapUsagePercent = 40,
            ),
            0.001f,
        )
        assertEquals(
            0.75f,
            DevicePerformanceMonitor.computeAdaptivePreviewScale(
                mode = PreviewQualityMode.AUTO,
                thermalTier = ThermalTier.MODERATE,
                memoryPressureTier = MemoryPressureTier.NORMAL,
                heapUsagePercent = 40,
            ),
            0.001f,
        )
        assertEquals(
            0.5f,
            DevicePerformanceMonitor.computeAdaptivePreviewScale(
                mode = PreviewQualityMode.AUTO,
                thermalTier = ThermalTier.SEVERE,
                memoryPressureTier = MemoryPressureTier.NORMAL,
                heapUsagePercent = 40,
            ),
            0.001f,
        )
        assertEquals(
            0.5f,
            DevicePerformanceMonitor.computeAdaptivePreviewScale(
                mode = PreviewQualityMode.AUTO,
                thermalTier = ThermalTier.NOMINAL,
                memoryPressureTier = MemoryPressureTier.NORMAL,
                heapUsagePercent = 90,
            ),
            0.001f,
        )
        assertEquals(
            1.0f,
            DevicePerformanceMonitor.computeAdaptivePreviewScale(
                mode = PreviewQualityMode.FULL,
                thermalTier = ThermalTier.CRITICAL,
                memoryPressureTier = MemoryPressureTier.CRITICAL,
                heapUsagePercent = 95,
            ),
            0.001f,
        )
        assertEquals(540, DevicePerformanceMonitor.previewMaxFboDimension(0.5f))
        assertEquals(810, DevicePerformanceMonitor.previewMaxFboDimension(0.75f))
        assertEquals(1080, DevicePerformanceMonitor.previewMaxFboDimension(1.0f))
    }

    @Test
    fun thermalApiCompatibility_matchesAndroidSdkLevel() {
        val monitor = DevicePerformanceMonitor(context)
        val snap = monitor.snapshot.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(snap.thermalApiSupported)
        } else {
            assertFalse(snap.thermalApiSupported)
            assertEquals(ThermalTier.NOMINAL, snap.thermalTier)
        }
    }

    @Test
    fun onTrimMemory_notifiesListenersAndReducesPreviewScaleUnderPressure() {
        val monitor = DevicePerformanceMonitor(context)
        var capturedLevel = -1
        monitor.registerTrimListener { level -> capturedLevel = level }

        monitor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, capturedLevel)
        val snap = monitor.snapshot.value
        assertEquals(MemoryPressureTier.CRITICAL, snap.memoryPressureTier)
        assertEquals(1, snap.trimEventsHandled)
        assertEquals(0.5f, snap.effectivePreviewScale, 0.001f)
    }

    @Test
    fun previewFrameLatency_computesSmoothedFps() {
        val monitor = DevicePerformanceMonitor(context)
        monitor.recordPreviewFrameLatency(16.0f)
        monitor.recordPreviewFrameLatency(16.0f)
        val snap = monitor.snapshot.value
        assertEquals(16.0f, snap.lastPreviewFrameMs, 0.5f)
        assertEquals(62.5f, snap.previewFps, 2.0f)
    }

    @Test
    fun runPipelineBenchmark_measuresAndPersistsRealMetrics() = runBlocking {
        val monitor = DevicePerformanceMonitor(context)
        val record = monitor.runPipelineBenchmark()

        assertTrue(record.durationMs >= 1L)
        assertTrue(record.fps > 0f)
        assertTrue(record.throughputMbps > 0.0)
        assertTrue(record.validated)
        assertEquals(BenchmarkKind.DIAGNOSTIC_PIPELINE, record.kind)

        // Verify persistence across a fresh monitor instance
        val reloaded = DevicePerformanceMonitor(context)
        assertEquals(1, reloaded.benchmarks.value.size)
        assertEquals(record.id, reloaded.benchmarks.value.first().id)
    }

    @Test
    fun recordExportBenchmark_recordsThroughputAndPath() = runBlocking {
        val monitor = DevicePerformanceMonitor(context)
        monitor.recordExportBenchmark(
            label = "Promo Reel (1080x1920)",
            durationMs = 2_000L,
            outputBytes = 4_000_000L,
            width = 1080,
            height = 1920,
            fps = 30,
            renderPath = "Path B (GL)",
            validated = true,
        )

        val latest = monitor.benchmarks.value.first()
        assertEquals(BenchmarkKind.REAL_EXPORT, latest.kind)
        assertEquals(16.0, latest.throughputMbps, 0.2)
        assertEquals("Path B (GL)", latest.renderPath)
        assertTrue(latest.validated)
    }

    @Test
    fun cleanStaleExports_removesTinyUnindexedFilesOnly() = runBlocking {
        val exportRoot = File(context.filesDir, "cutlab_exports/proj1").apply { mkdirs() }
        val tinyLeftover = File(exportRoot, "aborted_0.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val validUnindexed = File(exportRoot, "keep_large.mp4").apply { writeBytes(ByteArray(2048) { 7 }) }

        val manager = ExportOutputManager(context)
        val removed = manager.cleanStaleExports()

        assertEquals(1, removed)
        assertFalse(tinyLeftover.exists())
        assertTrue(validUnindexed.exists())
    }
}
