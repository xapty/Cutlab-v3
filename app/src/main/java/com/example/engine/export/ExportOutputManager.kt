package com.example.engine.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Manages exported MP4 output files in app-managed storage, validates MP4 containers with
 * MediaExtractor & MediaMetadataRetriever, saves to Gallery (MediaStore on API 29+ / SAF on API 28),
 * and creates FileProvider share Intents.
 */
class ExportOutputManager(
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val exportRootDir: File
        get() = File(context.filesDir, "cutlab_exports").apply { mkdirs() }

    private val indexFile: File
        get() = File(exportRootDir, "exports_index.json")

    fun createTargetOutputFile(projectId: String, projectName: String): File {
        val projectDir = File(exportRootDir, projectId.ifBlank { "default" }).apply { mkdirs() }
        val safeName = projectName
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .trim('_')
            .ifBlank { "CutLab" }
            .take(24)
        val fileName = "${safeName}_${System.currentTimeMillis()}.mp4"
        return File(projectDir, fileName)
    }

    suspend fun validateExportedMp4(
        filePath: String,
        expectAudio: Boolean,
    ): ExportValidationResult = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || file.length() < 512L) {
            return@withContext ExportValidationResult(
                isValid = false,
                fileSizeBytes = file.length(),
                durationMs = 0L,
                width = 0,
                height = 0,
                hasVideoTrack = false,
                hasAudioTrack = false,
                errorMessage = "Exported file is missing or empty.",
            )
        }

        val extractor = MediaExtractor()
        var hasVideo = false
        var hasAudio = false
        var trackDurationMs = 0L
        var trackWidth = 0
        var trackHeight = 0

        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    val d = fmt.getLong(MediaFormat.KEY_DURATION) / 1_000L
                    if (d > trackDurationMs) trackDurationMs = d
                }
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    if (fmt.containsKey(MediaFormat.KEY_WIDTH)) trackWidth = fmt.getInteger(MediaFormat.KEY_WIDTH)
                    if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) trackHeight = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                }
            }
        } catch (t: Throwable) {
            return@withContext ExportValidationResult(
                isValid = false,
                fileSizeBytes = file.length(),
                durationMs = 0L,
                width = 0,
                height = 0,
                hasVideoTrack = false,
                hasAudioTrack = false,
                errorMessage = "MP4 container validation failed: ${t.message}",
            )
        } finally {
            runCatching { extractor.release() }
        }

        val retriever = MediaMetadataRetriever()
        var retrieverDur = 0L
        var retrieverW = 0
        var retrieverH = 0
        try {
            retriever.setDataSource(file.absolutePath)
            retrieverDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retrieverW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            retrieverH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            // Fall back to MediaExtractor values if retriever metadata lookup fails
        } finally {
            runCatching { retriever.release() }
        }

        val finalDur = maxOf(trackDurationMs, retrieverDur)
        val finalW = if (retrieverW > 0) retrieverW else trackWidth
        val finalH = if (retrieverH > 0) retrieverH else trackHeight

        val valid = hasVideo && finalW > 0 && finalH > 0 && finalDur > 0L && (!expectAudio || hasAudio)
        val err = when {
            !hasVideo -> "Exported MP4 contains no video track."
            finalW <= 0 || finalH <= 0 -> "Exported MP4 has invalid video dimensions."
            finalDur <= 0L -> "Exported MP4 has zero duration."
            expectAudio && !hasAudio -> "Audio track missing from exported MP4."
            else -> null
        }

        ExportValidationResult(
            isValid = valid,
            fileSizeBytes = file.length(),
            durationMs = finalDur,
            width = finalW,
            height = finalH,
            hasVideoTrack = hasVideo,
            hasAudioTrack = hasAudio,
            errorMessage = err,
        )
    }

    suspend fun saveToGallery(artifact: ExportedArtifact): Uri? = withContext(Dispatchers.IO) {
        val source = File(artifact.filePath)
        if (!source.exists() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, artifact.fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CutLab")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        val copied = runCatching {
            resolver.openOutputStream(itemUri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out, bufferSize = 64 * 1024) }
            } != null
        }.getOrDefault(false)

        if (!copied) {
            runCatching { resolver.delete(itemUri, null, null) }
            return@withContext null
        }

        val publish = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(itemUri, publish, null, null)
        markArtifactGalleryUri(artifact.id, itemUri.toString())
        itemUri
    }

    suspend fun writeToDestinationUri(artifact: ExportedArtifact, destUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val source = File(artifact.filePath)
            if (!source.exists()) return@withContext false
            val ok = runCatching {
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    source.inputStream().use { input -> input.copyTo(out, bufferSize = 64 * 1024) }
                } != null
            }.getOrDefault(false)
            if (ok) {
                markArtifactGalleryUri(artifact.id, destUri.toString())
            }
            ok
        }

    fun createShareIntent(artifact: ExportedArtifact): Intent {
        val file = File(artifact.filePath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, artifact.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun recordArtifact(
        projectId: String,
        file: File,
        plan: ExportPlan,
        validation: ExportValidationResult,
    ): ExportedArtifact = withContext(Dispatchers.IO) {
        val artifact = ExportedArtifact(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            fileName = file.name,
            filePath = file.absolutePath,
            renderPathUsed = plan.selectedPath.shortTag,
            width = if (validation.width > 0) validation.width else plan.outputWidth,
            height = if (validation.height > 0) validation.height else plan.outputHeight,
            fps = plan.fps,
            codec = plan.codec.label,
            durationMs = if (validation.durationMs > 0L) validation.durationMs else plan.totalDurationMs,
            fileSizeBytes = file.length(),
            createdAtMs = System.currentTimeMillis(),
        )
        val all = readAllArtifacts().filter { File(it.filePath).exists() }.toMutableList()
        all.add(0, artifact)
        writeAllArtifacts(all.take(40))
        artifact
    }

    suspend fun listProjectExports(projectId: String): List<ExportedArtifact> = withContext(Dispatchers.IO) {
        readAllArtifacts()
            .filter { it.projectId == projectId && File(it.filePath).exists() }
            .sortedByDescending { it.createdAtMs }
    }

    suspend fun deleteArtifact(artifactId: String) = withContext(Dispatchers.IO) {
        val all = readAllArtifacts()
        val target = all.firstOrNull { it.id == artifactId }
        if (target != null) {
            runCatching { File(target.filePath).delete() }
        }
        writeAllArtifacts(all.filterNot { it.id == artifactId })
    }

    private fun markArtifactGalleryUri(artifactId: String, uriStr: String) {
        val updated = readAllArtifacts().map {
            if (it.id == artifactId) it.copy(savedGalleryUri = uriStr) else it
        }
        writeAllArtifacts(updated)
    }

    suspend fun cleanStaleExports(): Int = withContext(Dispatchers.IO) {
        val indexedPaths = readAllArtifacts().map { it.filePath }.toSet()
        var removed = 0
        if (exportRootDir.exists()) {
            exportRootDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
                .forEach { file ->
                    val isIncomplete = file.length() < 512L
                    val unindexed = file.absolutePath !in indexedPaths
                    if (isIncomplete && unindexed && file.delete()) {
                        removed++
                    }
                }
        }
        removed
    }

    private fun readAllArtifacts(): List<ExportedArtifact> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ExportedArtifact>>(indexFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun writeAllArtifacts(items: List<ExportedArtifact>) {
        runCatching {
            indexFile.writeText(json.encodeToString(items))
        }
    }
}
