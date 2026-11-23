package com.example.engine.asr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class ResolvedWhisperModelFiles(
    val encoderFile: File,
    val decoderFile: File,
    val tokensFile: File,
)

/**
 * Manages explicit, user-consented downloads, safe extraction (`.tar.bz2` / `.zip`),
 * and verification of offline sherpa-onnx Whisper ASR models inside app-private storage
 * (`filesDir/asr_models`).
 *
 * Never silently downloads large models or uploads user audio off device.
 */
class AsrModelManager(
    context: Context,
) {
    private val modelsRootDir = File(context.filesDir, "asr_models").apply { mkdirs() }
    private val inFlightDownloads = ConcurrentHashMap<String, Boolean>()

    fun getModelDirectory(model: AsrModelSpec): File = File(modelsRootDir, model.id)

    fun inspectModelStatus(model: AsrModelSpec): AsrModelAvailability {
        val dir = getModelDirectory(model)
        if (!isValidModelDirectory(dir, model)) {
            return AsrModelAvailability.NotInstalled
        }
        val sizeBytes = calculateDirectorySize(dir)
        return AsrModelAvailability.Ready(
            modelDirPath = dir.absolutePath,
            diskBytes = sizeBytes,
        )
    }

    fun isValidModelDirectory(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return AsrModelCatalog.MODELS.any { spec -> resolveWhisperFiles(dir, spec) != null }
    }

    fun isValidModelDirectory(dir: File, model: AsrModelSpec): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return resolveWhisperFiles(dir, model) != null
    }

    fun resolveWhisperFiles(dir: File, model: AsrModelSpec): ResolvedWhisperModelFiles? {
        if (!dir.exists() || !dir.isDirectory) return null
        val encoderName = model.encoderFileName.ifBlank { "encoder.int8.onnx" }
        val decoderName = model.decoderFileName.ifBlank { "decoder.int8.onnx" }
        val tokensName = model.tokensFileName.ifBlank { "tokens.txt" }

        val candidateRoots = buildList {
            add(dir)
            dir.listFiles()?.filter { it.isDirectory }?.forEach { add(it) }
        }
        for (root in candidateRoots) {
            val encoder = File(root, encoderName)
            val decoder = File(root, decoderName)
            val tokens = File(root, tokensName)
            if (encoder.isFile && encoder.length() > MIN_ONNX_WEIGHT_BYTES &&
                decoder.isFile && decoder.length() > MIN_ONNX_WEIGHT_BYTES &&
                tokens.isFile && tokens.length() > MIN_TOKENS_BYTES
            ) {
                return ResolvedWhisperModelFiles(
                    encoderFile = encoder,
                    decoderFile = decoder,
                    tokensFile = tokens,
                )
            }
        }
        return null
    }

    suspend fun downloadAndInstallModel(
        model: AsrModelSpec,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = getModelDirectory(model)
        if (isValidModelDirectory(targetDir, model)) {
            return@withContext Result.success(targetDir)
        }
        if (inFlightDownloads.putIfAbsent(model.id, true) != null) {
            return@withContext Result.failure(
                IllegalStateException("Download already in progress for ${model.displayName}."),
            )
        }

        val workId = "${model.id}_${System.currentTimeMillis()}"
        val ext = if (model.archiveType.equals("zip", ignoreCase = true)) "zip" else "tar.bz2"
        val tempArchive = File(modelsRootDir, "$workId.$ext.part")
        val tempUnpackDir = File(modelsRootDir, "${workId}_unpack")
        var connection: HttpURLConnection? = null

        try {
            val url = URL(model.downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("Model server returned HTTP $responseCode for ${model.displayName}."),
                )
            }

            val totalBytes = connection.contentLengthLong
                .takeIf { it > 0L }
                ?: (model.approximateSizeMb.toLong() * 1024L * 1024L)
            var downloadedBytes = 0L
            val buffer = ByteArray(16 * 1024)

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(tempArchive).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val dlFraction = (downloadedBytes.toFloat() / totalBytes.coerceAtLeast(1L).toFloat())
                            .coerceIn(0f, 1f)
                        onProgress(dlFraction * 0.85f, downloadedBytes, totalBytes)
                    }
                }
            }

            if (downloadedBytes < 1024L) {
                return@withContext Result.failure(IllegalStateException("Downloaded model archive was incomplete."))
            }

            onProgress(0.88f, downloadedBytes, totalBytes)
            tempUnpackDir.deleteRecursively()
            tempUnpackDir.mkdirs()

            if (model.archiveType.equals("zip", ignoreCase = true) ||
                model.downloadUrl.endsWith(".zip", ignoreCase = true)
            ) {
                extractZipSafely(tempArchive, tempUnpackDir)
            } else {
                extractTarBz2Safely(tempArchive, tempUnpackDir)
            }

            onProgress(0.96f, downloadedBytes, totalBytes)
            val resolvedRoot = locateUnpackedModelRoot(tempUnpackDir, model)
                ?: return@withContext Result.failure(
                    IllegalStateException("Extracted archive did not contain required Whisper ONNX files (${model.encoderFileName}, ${model.decoderFileName}, ${model.tokensFileName})."),
                )

            val tempStagedDir = File(modelsRootDir, "${workId}_staged")
            tempStagedDir.deleteRecursively()
            if (!resolvedRoot.renameTo(tempStagedDir)) {
                resolvedRoot.copyRecursively(tempStagedDir, overwrite = true)
            }

            targetDir.deleteRecursively()
            if (!tempStagedDir.renameTo(targetDir)) {
                tempStagedDir.copyRecursively(targetDir, overwrite = true)
                tempStagedDir.deleteRecursively()
            }

            if (!isValidModelDirectory(targetDir, model)) {
                targetDir.deleteRecursively()
                return@withContext Result.failure(
                    IllegalStateException("Whisper model validation failed after extraction."),
                )
            }

            onProgress(1.0f, downloadedBytes, totalBytes)
            Result.success(targetDir)
        } catch (ce: CancellationException) {
            targetDir.deleteRecursively()
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            inFlightDownloads.remove(model.id)
            runCatching { connection?.disconnect() }
            runCatching { tempArchive.delete() }
            runCatching { tempUnpackDir.deleteRecursively() }
        }
    }

    internal suspend fun extractTarBz2Safely(archiveFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        val buffer = ByteArray(16 * 1024)
        BufferedInputStream(archiveFile.inputStream()).use { rawIn ->
            BZip2CompressorInputStream(rawIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = tarIn.nextEntry ?: break
                        if (entry.isSymbolicLink || entry.isLink) continue
                        val outFile = File(destDir, entry.name)
                        if (!outFile.canonicalPath.startsWith(canonicalDest + File.separator) &&
                            outFile.canonicalPath != canonicalDest
                        ) {
                            throw SecurityException("Invalid tar entry path: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val r = tarIn.read(buffer)
                                    if (r < 0) break
                                    fos.write(buffer, 0, r)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    internal suspend fun extractZipSafely(archiveFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath
        val buffer = ByteArray(16 * 1024)
        ZipInputStream(BufferedInputStream(archiveFile.inputStream())).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(canonicalDest + File.separator) &&
                    outFile.canonicalPath != canonicalDest
                ) {
                    throw SecurityException("Invalid zip entry path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val r = zip.read(buffer)
                            if (r < 0) break
                            fos.write(buffer, 0, r)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    fun deleteModel(model: AsrModelSpec): Boolean {
        val dir = getModelDirectory(model)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    private fun locateUnpackedModelRoot(unpackDir: File, model: AsrModelSpec): File? {
        if (resolveWhisperFiles(unpackDir, model) != null) {
            val directEncoder = File(unpackDir, model.encoderFileName)
            if (directEncoder.isFile) return unpackDir
            return unpackDir.listFiles()?.firstOrNull { child ->
                child.isDirectory && File(child, model.encoderFileName).isFile
            }
        }
        return null
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    companion object {
        const val MIN_ONNX_WEIGHT_BYTES = 4096L
        const val MIN_TOKENS_BYTES = 128L
    }
}
