package com.example.core.media

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.core.storage.MediaStorageManager
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface MediaImportResult {
    data class Success(val asset: MediaAsset) : MediaImportResult
    data class Failure(val displayName: String, val reason: String) : MediaImportResult
}

class MediaMetadataExtractor(
    private val storageManager: MediaStorageManager,
) {
    suspend fun importAndInspect(
        uri: Uri,
        projectId: String?,
        hintType: MediaType? = null,
    ): MediaImportResult = withContext(Dispatchers.IO) {
        val assetId = UUID.randomUUID().toString()
        val stored = storageManager.copyUriToManagedStorage(
            uri = uri,
            projectId = projectId,
            assetId = assetId,
        ) ?: return@withContext MediaImportResult.Failure(
            displayName = uri.lastPathSegment ?: "Selected file",
            reason = "Could not read selected file stream.",
        )

        val inferredType = hintType ?: inferMediaType(stored.mimeType, stored.file.extension)
        if (inferredType == null) {
            storageManager.deleteFile(stored.file.absolutePath)
            return@withContext MediaImportResult.Failure(
                displayName = stored.displayName,
                reason = "Unsupported media format (${stored.mimeType.ifBlank { stored.file.extension }}).",
            )
        }

        val inspected = when (inferredType) {
            MediaType.VIDEO -> inspectVideo(
                assetId = assetId,
                projectId = projectId,
                sourceUri = uri.toString(),
                file = stored.file,
                displayName = stored.displayName,
                mimeType = stored.mimeType.ifBlank { "video/${stored.file.extension.ifBlank { "mp4" }}" },
                sizeBytes = stored.sizeBytes,
            )
            MediaType.IMAGE -> inspectImage(
                assetId = assetId,
                projectId = projectId,
                sourceUri = uri.toString(),
                file = stored.file,
                displayName = stored.displayName,
                mimeType = stored.mimeType.ifBlank { "image/${stored.file.extension.ifBlank { "jpeg" }}" },
                sizeBytes = stored.sizeBytes,
            )
            MediaType.AUDIO -> inspectAudio(
                assetId = assetId,
                projectId = projectId,
                sourceUri = uri.toString(),
                file = stored.file,
                displayName = stored.displayName,
                mimeType = stored.mimeType.ifBlank { "audio/${stored.file.extension.ifBlank { "mp3" }}" },
                sizeBytes = stored.sizeBytes,
            )
        }

        if (inspected == null) {
            storageManager.deleteFile(stored.file.absolutePath)
            return@withContext MediaImportResult.Failure(
                displayName = stored.displayName,
                reason = "Corrupted or unsupported ${inferredType.label.lowercase()} container.",
            )
        }

        MediaImportResult.Success(inspected)
    }

    private fun inferMediaType(mimeType: String, extension: String): MediaType? {
        val lowerMime = mimeType.lowercase()
        val lowerExt = extension.lowercase()
        return when {
            lowerMime.startsWith("video/") -> MediaType.VIDEO
            lowerMime.startsWith("image/") -> MediaType.IMAGE
            lowerMime.startsWith("audio/") -> MediaType.AUDIO
            lowerExt in setOf("mp4", "mov", "mkv", "webm", "m4v", "3gp") -> MediaType.VIDEO
            lowerExt in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp") -> MediaType.IMAGE
            lowerExt in setOf("mp3", "wav", "aac", "m4a", "ogg", "flac", "opus") -> MediaType.AUDIO
            else -> null
        }
    }

    private fun inspectVideo(
        assetId: String,
        projectId: String?,
        sourceUri: String,
        file: File,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ): MediaAsset? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (hasVideo != "yes") {
                // Verify dimensions as secondary fallback
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                if (w <= 0) return null
            }
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (width, height) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val frameCount = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toFloatOrNull()
            val fps = if (frameCount != null && durationMs > 0L) {
                (frameCount * 1000f / durationMs).coerceIn(1f, 240f)
            } else {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull() ?: 30f
            }

            MediaAsset(
                id = assetId,
                projectId = projectId,
                type = MediaType.VIDEO,
                displayName = displayName,
                sourceUri = sourceUri,
                managedFilePath = file.absolutePath,
                mimeType = mimeType,
                durationMs = durationMs.coerceAtLeast(0L),
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate,
                fileSizeBytes = sizeBytes,
                status = MediaStatus.READY,
                importedAt = System.currentTimeMillis(),
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun inspectImage(
        assetId: String,
        projectId: String?,
        sourceUri: String,
        file: File,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ): MediaAsset? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            MediaAsset(
                id = assetId,
                projectId = projectId,
                type = MediaType.IMAGE,
                displayName = displayName,
                sourceUri = sourceUri,
                managedFilePath = file.absolutePath,
                mimeType = options.outMimeType ?: mimeType,
                durationMs = 3_000L,
                width = options.outWidth,
                height = options.outHeight,
                fps = 0f,
                bitrate = 0L,
                fileSizeBytes = sizeBytes,
                status = MediaStatus.READY,
                importedAt = System.currentTimeMillis(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun inspectAudio(
        assetId: String,
        projectId: String?,
        sourceUri: String,
        file: File,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
    ): MediaAsset? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (hasAudio != "yes" && durationMs <= 0L) return null
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: displayName

            MediaAsset(
                id = assetId,
                projectId = projectId,
                type = MediaType.AUDIO,
                displayName = title,
                sourceUri = sourceUri,
                managedFilePath = file.absolutePath,
                mimeType = mimeType,
                durationMs = durationMs.coerceAtLeast(0L),
                width = 0,
                height = 0,
                fps = 0f,
                bitrate = bitrate,
                fileSizeBytes = sizeBytes,
                status = MediaStatus.READY,
                importedAt = System.currentTimeMillis(),
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
