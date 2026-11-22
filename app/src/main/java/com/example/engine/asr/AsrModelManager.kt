package com.example.engine.asr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Manages explicit, user-consented downloads and validation of offline Vosk ASR models
 * inside app-private storage (`filesDir/asr_models`).
 * Never silently downloads large models or uploads user audio off device.
 */
class AsrModelManager(
    context: Context,
) {
    private val modelsRootDir = File(context.filesDir, "asr_models").apply { mkdirs() }

    fun getModelDirectory(model: AsrModelSpec): File = File(modelsRootDir, model.id)

    fun inspectModelStatus(model: AsrModelSpec): AsrModelAvailability {
        val dir = getModelDirectory(model)
        if (!isValidModelDirectory(dir)) {
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
        val amMdl = File(dir, "am/final.mdl")
        val rootMdl = File(dir, "final.mdl")
        val confDir = File(dir, "conf")
        val graphDir = File(dir, "graph")
        val hasModelWeights = (amMdl.exists() && amMdl.length() > 1024L) ||
            (rootMdl.exists() && rootMdl.length() > 1024L)
        val hasConfig = confDir.exists() && confDir.isDirectory
        val hasGraph = graphDir.exists() && graphDir.isDirectory
        return hasModelWeights && (hasConfig || hasGraph)
    }

    suspend fun downloadAndInstallModel(
        model: AsrModelSpec,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetDir = getModelDirectory(model)
        if (isValidModelDirectory(targetDir)) {
            return@withContext Result.success(targetDir)
        }

        val workId = "${model.id}_${System.currentTimeMillis()}"
        val tempZip = File(modelsRootDir, "$workId.zip.part")
        val tempUnpackDir = File(modelsRootDir, "${workId}_unpack")
        var connection: HttpURLConnection? = null

        try {
            val url = URL(model.downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
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
                FileOutputStream(tempZip).use { output ->
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

            val canonicalDest = tempUnpackDir.canonicalPath
            ZipInputStream(BufferedInputStream(tempZip.inputStream())).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    val outFile = File(tempUnpackDir, entry.name)
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
                            val zBuf = ByteArray(16 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val r = zip.read(zBuf)
                                if (r < 0) break
                                fos.write(zBuf, 0, r)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            onProgress(0.96f, downloadedBytes, totalBytes)
            val resolvedRoot = locateUnpackedModelRoot(tempUnpackDir)
                ?: return@withContext Result.failure(
                    IllegalStateException("Extracted archive did not contain a valid Vosk speech model."),
                )

            targetDir.deleteRecursively()
            if (!resolvedRoot.renameTo(targetDir)) {
                resolvedRoot.copyRecursively(targetDir, overwrite = true)
            }

            if (!isValidModelDirectory(targetDir)) {
                targetDir.deleteRecursively()
                return@withContext Result.failure(
                    IllegalStateException("Model validation failed after extraction."),
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
            runCatching { connection?.disconnect() }
            runCatching { tempZip.delete() }
            runCatching { tempUnpackDir.deleteRecursively() }
        }
    }

    fun deleteModel(model: AsrModelSpec): Boolean {
        val dir = getModelDirectory(model)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    private fun locateUnpackedModelRoot(unpackDir: File): File? {
        if (isValidModelDirectory(unpackDir)) return unpackDir
        return unpackDir.listFiles()
            ?.firstOrNull { it.isDirectory && isValidModelDirectory(it) }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }
}
