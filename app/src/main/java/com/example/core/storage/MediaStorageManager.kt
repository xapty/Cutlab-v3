package com.example.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredMediaFile(
    val file: File,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

class MediaStorageManager(private val context: Context) {
    private val mediaRootDir: File
        get() = File(context.filesDir, "cutlab_media").apply { mkdirs() }

    suspend fun copyUriToManagedStorage(
        uri: Uri,
        projectId: String?,
        assetId: String = UUID.randomUUID().toString(),
    ): StoredMediaFile? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        val mimeType = resolver.getType(uri).orEmpty()
        val displayName = resolveDisplayName(uri) ?: "asset_${assetId.take(8)}"
        val ext = resolveExtension(displayName, mimeType)

        val folderName = projectId?.takeIf { it.isNotBlank() } ?: "vault"
        val targetDir = File(mediaRootDir, folderName).apply { mkdirs() }
        val safeFileName = if (ext.isNotEmpty()) "${assetId}.$ext" else assetId
        val targetFile = File(targetDir, safeFileName)

        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } != null
        }.getOrDefault(false)

        if (!copied || !targetFile.exists() || targetFile.length() == 0L) {
            targetFile.delete()
            return@withContext null
        }

        StoredMediaFile(
            file = targetFile,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = targetFile.length(),
        )
    }

    suspend fun copyFileForDuplicateProject(
        sourcePath: String,
        targetProjectId: String,
        newAssetId: String,
    ): String? = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        if (!source.exists()) return@withContext null
        val ext = source.extension
        val targetDir = File(mediaRootDir, targetProjectId).apply { mkdirs() }
        val targetName = if (ext.isNotEmpty()) "$newAssetId.$ext" else newAssetId
        val dest = File(targetDir, targetName)
        val ok = runCatching {
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            true
        }.getOrDefault(false)
        if (ok && dest.exists()) dest.absolutePath else null
    }

    private val proxyRootDir: File
        get() = File(context.filesDir, "cutlab_proxies").apply { mkdirs() }

    suspend fun deleteFile(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        runCatching { File(path).delete() }
    }

    suspend fun deleteFiles(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { path ->
            if (path.isNotBlank()) runCatching { File(path).delete() }
        }
    }

    suspend fun deleteProjectDirectory(projectId: String) = withContext(Dispatchers.IO) {
        val dir = File(mediaRootDir, projectId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    suspend fun calculateManagedStorageBytes(): Long = withContext(Dispatchers.IO) {
        val mediaBytes = if (mediaRootDir.exists()) {
            mediaRootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L
        val proxyBytes = if (proxyRootDir.exists()) {
            proxyRootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L
        mediaBytes + proxyBytes
    }

    suspend fun pruneOrphanedFiles(
        referencedPaths: Set<String>,
        referencedProxyPaths: Set<String> = emptySet(),
    ): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        if (mediaRootDir.exists()) {
            mediaRootDir.walkTopDown()
                .filter { it.isFile && it.absolutePath !in referencedPaths }
                .forEach { file ->
                    if (file.delete()) deletedCount++
                }
        }
        if (proxyRootDir.exists()) {
            proxyRootDir.walkTopDown()
                .filter { it.isFile && it.absolutePath !in referencedProxyPaths }
                .forEach { file ->
                    if (file.delete()) deletedCount++
                }
        }
        deletedCount
    }

    fun fileExists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = File(path)
        return file.exists() && file.length() > 0L
    }

    private fun resolveDisplayName(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun resolveExtension(displayName: String, mimeType: String): String {
        val fromName = displayName.substringAfterLast('.', "").lowercase()
        if (fromName.isNotBlank() && fromName.length <= 5) return fromName
        if (mimeType.isNotBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { return it }
        }
        return ""
    }
}
