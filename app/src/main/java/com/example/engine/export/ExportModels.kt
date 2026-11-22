package com.example.engine.export

import com.example.engine.project.ExportSettingsSpec
import com.example.engine.project.MediaAsset
import com.example.engine.project.ProjectDocument
import kotlinx.serialization.Serializable

enum class ExportRenderPath(val label: String, val shortTag: String) {
    AUTO("Auto (Smart Hybrid)", "Auto"),
    MEDIA3_TRANSFORMER("Media3 Transformer (Path A)", "Path A: Media3"),
    CUSTOM_GL_CODEC("Custom GL + Codec (Path B)", "Path B: Custom GL"),
}

enum class ExportResolutionPreset(
    val shortSidePx: Int,
    val label: String,
    val videoBitrateBps: Int,
) {
    HD_720P(720, "720p", 5_500_000),
    FHD_1080P(1080, "1080p", 11_000_000),
    QHD_1440P(1440, "1440p", 18_500_000),
    UHD_4K(2160, "4K", 34_000_000);

    companion object {
        fun fromHeight(height: Int): ExportResolutionPreset {
            return entries.firstOrNull { it.shortSidePx == height } ?: FHD_1080P
        }
    }
}

enum class ExportVideoCodec(
    val label: String,
    val mimeType: String,
    val description: String,
) {
    H264("H.264", "video/avc", "Universal MP4 compatibility"),
    H265("H.265", "video/hevc", "HEVC high efficiency");

    companion object {
        fun fromLabel(codec: String): ExportVideoCodec {
            return if (codec.equals("H.265", ignoreCase = true) ||
                codec.equals("HEVC", ignoreCase = true)
            ) H265 else H264
        }
    }
}

enum class ExportAudioBitrateOption(val kbps: Int, val label: String) {
    KBPS_128(128, "128 kbps"),
    KBPS_192(192, "192 kbps"),
    KBPS_256(256, "256 kbps"),
    KBPS_320(320, "320 kbps");

    companion object {
        fun fromKbps(kbps: Int): ExportAudioBitrateOption {
            return entries.firstOrNull { it.kbps == kbps } ?: KBPS_192
        }
    }
}

data class HardwareExportCapabilities(
    val supportsAvc: Boolean = true,
    val supportsHevc: Boolean = false,
    val maxSupportedShortSidePx: Int = 2160,
    val avcEncoderName: String? = null,
    val hevcEncoderName: String? = null,
) {
    fun supportsResolution(shortSidePx: Int): Boolean = shortSidePx <= maxSupportedShortSidePx
}

data class ExportRequest(
    val project: ProjectDocument,
    val assetsById: Map<String, MediaAsset>,
    val settings: ExportSettingsSpec,
    val preferredPath: ExportRenderPath = ExportRenderPath.AUTO,
)

data class ExportPlan(
    val selectedPath: ExportRenderPath,
    val reason: String,
    val compatibilityNotes: List<String>,
    val outputWidth: Int,
    val outputHeight: Int,
    val fps: Int,
    val codec: ExportVideoCodec,
    val videoBitrateBps: Int,
    val audioBitrateBps: Int,
    val hasAudio: Boolean,
    val totalDurationMs: Long,
    val estimatedFileSizeMb: Float,
    val isExportable: Boolean,
    val blockingError: String? = null,
)

data class ExportValidationResult(
    val isValid: Boolean,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val errorMessage: String? = null,
)

@Serializable
data class ExportedArtifact(
    val id: String,
    val projectId: String,
    val fileName: String,
    val filePath: String,
    val renderPathUsed: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val createdAtMs: Long,
    val savedGalleryUri: String? = null,
)

sealed class ExportJobState {
    data object Idle : ExportJobState()
    data class Preparing(val plan: ExportPlan) : ExportJobState()
    data class Exporting(
        val plan: ExportPlan,
        val progress: Float,
        val statusMessage: String,
        val elapsedMs: Long,
    ) : ExportJobState()
    data class Validating(val plan: ExportPlan) : ExportJobState()
    data class Succeeded(
        val artifact: ExportedArtifact,
        val validation: ExportValidationResult,
        val plan: ExportPlan,
    ) : ExportJobState()
    data class Cancelled(val message: String) : ExportJobState()
    data class Failed(val message: String) : ExportJobState()
}
