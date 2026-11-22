package com.example.engine.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.LutPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Cube-ordered 3D RGB Lookup Table.
 * Entry index for normalized grid indices (r, g, b) in [0, size-1] is:
 * `(b * size + g) * size + r` (Red varies fastest, then Green, then Blue — Adobe .cube convention).
 */
data class Lut3DTable(
    val size: Int,
    val title: String,
    val rgbData: FloatArray, // size * size * size * 3 values in [0f, 1f]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Lut3DTable) return false
        return size == other.size && title == other.title && rgbData.contentEquals(other.rgbData)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + title.hashCode()
        result = 31 * result + rgbData.contentHashCode()
        return result
    }
}

/**
 * Pure-Kotlin Adobe `.cube` parser, procedural cinema LUT generator, trilinear resampler,
 * and GLES 2.0 2D-strip (`256 x 16` RGBA) bitmap packer.
 */
object Lut3DEngine {
    const val GL_LUT_SIZE = 16
    const val GL_STRIP_WIDTH = GL_LUT_SIZE * GL_LUT_SIZE // 256
    const val GL_STRIP_HEIGHT = GL_LUT_SIZE // 16

    private val parsedFileCache = ConcurrentHashMap<String, Lut3DTable>()
    private val presetCache = ConcurrentHashMap<LutPreset, Lut3DTable>()

    fun cacheKey(spec: ClipLutSpec): String = when {
        !spec.isActive -> "none"
        spec.preset == LutPreset.CUSTOM_CUBE -> "cube:${spec.customCubePath.orEmpty()}"
        else -> "preset:${spec.preset.name}"
    }

    fun resolveLutTable(spec: ClipLutSpec): Lut3DTable? {
        if (!spec.isActive) return null
        return when (spec.preset) {
            LutPreset.NONE -> null
            LutPreset.CUSTOM_CUBE -> {
                val path = spec.customCubePath ?: return null
                parsedFileCache.getOrPut(path) {
                    val file = File(path)
                    if (!file.exists()) return null
                    val parsed = runCatching { parseCubeLut(file.readText()) }.getOrNull() ?: return null
                    resampleTable(parsed, GL_LUT_SIZE)
                }
            }
            else -> presetCache.getOrPut(spec.preset) {
                generatePresetLut(spec.preset, GL_LUT_SIZE)
            }
        }
    }

    /**
     * Evaluates a 3D LUT with trilinear interpolation for [r], [g], [b] in `[0f, 1f]`.
     */
    fun sampleTrilinear(table: Lut3DTable, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val maxIdx = (table.size - 1).coerceAtLeast(1)
        val rf = r.coerceIn(0f, 1f) * maxIdx
        val gf = g.coerceIn(0f, 1f) * maxIdx
        val bf = b.coerceIn(0f, 1f) * maxIdx

        val r0 = floor(rf).toInt().coerceIn(0, maxIdx)
        val g0 = floor(gf).toInt().coerceIn(0, maxIdx)
        val b0 = floor(bf).toInt().coerceIn(0, maxIdx)
        val r1 = ceil(rf).toInt().coerceIn(0, maxIdx)
        val g1 = ceil(gf).toInt().coerceIn(0, maxIdx)
        val b1 = ceil(bf).toInt().coerceIn(0, maxIdx)

        val dr = rf - r0
        val dg = gf - g0
        val db = bf - b0

        fun fetch(ri: Int, gi: Int, bi: Int, ch: Int): Float {
            val idx = ((bi * table.size + gi) * table.size + ri) * 3 + ch
            return table.rgbData[idx]
        }

        fun interpChannel(ch: Int): Float {
            val c000 = fetch(r0, g0, b0, ch)
            val c100 = fetch(r1, g0, b0, ch)
            val c010 = fetch(r0, g1, b0, ch)
            val c110 = fetch(r1, g1, b0, ch)
            val c001 = fetch(r0, g0, b1, ch)
            val c101 = fetch(r1, g0, b1, ch)
            val c011 = fetch(r0, g1, b1, ch)
            val c111 = fetch(r1, g1, b1, ch)

            val c00 = c000 + (c100 - c000) * dr
            val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr
            val c11 = c011 + (c111 - c011) * dr

            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg
            return (c0 + (c1 - c0) * db).coerceIn(0f, 1f)
        }

        return Triple(interpChannel(0), interpChannel(1), interpChannel(2))
    }

    /**
     * Parses an Adobe `.cube` 3D LUT text payload.
     * Supports comments (`#`), `TITLE`, `LUT_3D_SIZE`, `DOMAIN_MIN`, `DOMAIN_MAX`.
     */
    fun parseCubeLut(cubeText: String): Lut3DTable? {
        var lutSize = -1
        var title = "Custom LUT"
        val domainMin = floatArrayOf(0f, 0f, 0f)
        val domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>(17 * 17 * 17 * 3)

        cubeText.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("TITLE", ignoreCase = true) -> {
                    title = line.substringAfter("TITLE", "").trim().trim('"').ifBlank { title }
                }
                line.startsWith("LUT_1D_SIZE", ignoreCase = true) -> {
                    // Only 3D LUTs are supported here
                    return null
                }
                line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                    lutSize = line.substringAfter("LUT_3D_SIZE").trim().toIntOrNull() ?: -1
                }
                line.startsWith("DOMAIN_MIN", ignoreCase = true) -> {
                    val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toFloatOrNull() }
                    if (parts.size >= 3) {
                        domainMin[0] = parts[0]; domainMin[1] = parts[1]; domainMin[2] = parts[2]
                    }
                }
                line.startsWith("DOMAIN_MAX", ignoreCase = true) -> {
                    val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toFloatOrNull() }
                    if (parts.size >= 3) {
                        domainMax[0] = parts[0]; domainMax[1] = parts[1]; domainMax[2] = parts[2]
                    }
                }
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            val nr = ((r - domainMin[0]) / (domainMax[0] - domainMin[0]).takeIf { it > 1e-4f }.let { it ?: 1f }).coerceIn(0f, 1f)
                            val ng = ((g - domainMin[1]) / (domainMax[1] - domainMin[1]).takeIf { it > 1e-4f }.let { it ?: 1f }).coerceIn(0f, 1f)
                            val nb = ((b - domainMin[2]) / (domainMax[2] - domainMin[2]).takeIf { it > 1e-4f }.let { it ?: 1f }).coerceIn(0f, 1f)
                            values.add(nr)
                            values.add(ng)
                            values.add(nb)
                        }
                    }
                }
            }
        }

        if (lutSize !in 2..65) return null
        val expectedFloats = lutSize * lutSize * lutSize * 3
        if (values.size < expectedFloats) return null

        return Lut3DTable(
            size = lutSize,
            title = title,
            rgbData = values.subList(0, expectedFloats).toFloatArray(),
        )
    }

    fun resampleTable(source: Lut3DTable, targetSize: Int = GL_LUT_SIZE): Lut3DTable {
        if (source.size == targetSize) return source
        val out = FloatArray(targetSize * targetSize * targetSize * 3)
        val denom = (targetSize - 1).coerceAtLeast(1).toFloat()
        for (b in 0 until targetSize) {
            for (g in 0 until targetSize) {
                for (r in 0 until targetSize) {
                    val (sr, sg, sb) = sampleTrilinear(source, r / denom, g / denom, b / denom)
                    val idx = ((b * targetSize + g) * targetSize + r) * 3
                    out[idx] = sr
                    out[idx + 1] = sg
                    out[idx + 2] = sb
                }
            }
        }
        return Lut3DTable(size = targetSize, title = source.title, rgbData = out)
    }

    fun generatePresetLut(preset: LutPreset, size: Int = GL_LUT_SIZE): Lut3DTable {
        val data = FloatArray(size * size * size * 3)
        val denom = (size - 1).coerceAtLeast(1).toFloat()
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    val r = ri / denom
                    val g = gi / denom
                    val b = bi / denom
                    val (or, og, ob) = transformPresetColor(preset, r, g, b)
                    val idx = ((bi * size + gi) * size + ri) * 3
                    data[idx] = or.coerceIn(0f, 1f)
                    data[idx + 1] = og.coerceIn(0f, 1f)
                    data[idx + 2] = ob.coerceIn(0f, 1f)
                }
            }
        }
        return Lut3DTable(size = size, title = preset.label, rgbData = data)
    }

    private fun transformPresetColor(
        preset: LutPreset,
        r: Float,
        g: Float,
        b: Float,
    ): Triple<Float, Float, Float> {
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val shadowWeight = (1f - luma).pow(1.6f)
        val highlightWeight = luma.pow(1.6f)
        return when (preset) {
            LutPreset.NONE, LutPreset.CUSTOM_CUBE -> Triple(r, g, b)
            LutPreset.TEAL_ORANGE -> {
                // Classic Hollywood teal shadows + warm skin/orange highlights with S-curve
                val cr = sCurve(r, 1.18f)
                val cg = sCurve(g, 1.12f)
                val cb = sCurve(b, 1.14f)
                Triple(
                    cr - 0.14f * shadowWeight + 0.16f * highlightWeight,
                    cg + 0.04f * shadowWeight + 0.03f * highlightWeight,
                    cb + 0.18f * shadowWeight - 0.17f * highlightWeight,
                )
            }
            LutPreset.KODAK_2383 -> {
                // Print film S-curve contrast, subtle warm midtones, deep blacks
                val cr = sCurve(r, 1.32f)
                val cg = sCurve(g, 1.28f)
                val cb = sCurve(b, 1.24f)
                Triple(
                    cr * 1.04f + 0.02f * highlightWeight,
                    cg * 0.99f + 0.01f * shadowWeight,
                    cb * 0.91f + 0.04f * shadowWeight,
                )
            }
            LutPreset.FUJI_PRO -> {
                // Crisp greens, vibrant saturation, cool shadows
                val sat = 1.24f
                val nr = luma + (r - luma) * sat
                val ng = luma + (g - luma) * sat
                val nb = luma + (b - luma) * sat
                Triple(
                    sCurve(nr, 1.12f) - 0.04f * shadowWeight,
                    sCurve(ng, 1.16f) + 0.04f * (1f - shadowWeight),
                    sCurve(nb, 1.10f) + 0.06f * shadowWeight,
                )
            }
            LutPreset.BLEACH_BYPASS -> {
                // Desaturated silver retention + steep punchy contrast
                val desat = 0.36f
                val mr = sCurve(luma + (r - luma) * desat, 1.45f)
                val mg = sCurve(luma + (g - luma) * desat, 1.45f)
                val mb = sCurve(luma + (b - luma) * desat, 1.48f)
                Triple(mr * 0.98f, mg * 0.99f, mb * 1.03f)
            }
            LutPreset.NOIR_PUNCH -> {
                // Monochrome with gentle selenium tint & crushed blacks
                val c = sCurve(luma, 1.42f)
                Triple(c * 1.02f, c * 0.99f, c * 0.94f)
            }
            LutPreset.GOLDEN_HOUR -> {
                // Sunset warmth, lifted peach highlights, soft contrast
                val nr = sCurve(r, 1.08f) + 0.14f * (0.4f + 0.6f * highlightWeight)
                val ng = sCurve(g, 1.06f) + 0.06f * highlightWeight
                val nb = sCurve(b, 1.04f) * 0.84f + 0.03f * shadowWeight
                Triple(nr, ng, nb)
            }
            LutPreset.CYBER_MINT -> {
                // Cyberpunk cyan/mint highlights + magenta-purple shadows
                val cr = sCurve(r, 1.22f) + 0.14f * shadowWeight - 0.10f * highlightWeight
                val cg = sCurve(g, 1.24f) + 0.10f * highlightWeight
                val cb = sCurve(b, 1.25f) + 0.18f * shadowWeight + 0.10f * highlightWeight
                Triple(cr, cg, cb)
            }
        }
    }

    private fun sCurve(x: Float, slope: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val shifted = (c - 0.5f) * slope
        val sig = 1f / (1f + kotlin.math.exp((-shifted * 4.0).toDouble()).toFloat())
        val minSig = 1f / (1f + kotlin.math.exp((2.0 * slope).toDouble()).toFloat())
        val maxSig = 1f / (1f + kotlin.math.exp((-2.0 * slope).toDouble()).toFloat())
        return ((sig - minSig) / (maxSig - minSig).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
    }

    /**
     * Packs a [Lut3DTable] into a 2D GLES 2.0 texture strip (`256 x 16` RGBA):
     * pixel `(b * 16 + r, g)` holds the mapped RGB color.
     */
    fun packTableToGlStripBitmap(table: Lut3DTable): Bitmap {
        val resampled = if (table.size == GL_LUT_SIZE) table else resampleTable(table, GL_LUT_SIZE)
        val bmp = Bitmap.createBitmap(GL_STRIP_WIDTH, GL_STRIP_HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(GL_STRIP_WIDTH * GL_STRIP_HEIGHT)
        for (b in 0 until GL_LUT_SIZE) {
            for (g in 0 until GL_LUT_SIZE) {
                for (r in 0 until GL_LUT_SIZE) {
                    val srcIdx = ((b * GL_LUT_SIZE + g) * GL_LUT_SIZE + r) * 3
                    val pr = (resampled.rgbData[srcIdx] * 255f).roundToInt().coerceIn(0, 255)
                    val pg = (resampled.rgbData[srcIdx + 1] * 255f).roundToInt().coerceIn(0, 255)
                    val pb = (resampled.rgbData[srcIdx + 2] * 255f).roundToInt().coerceIn(0, 255)
                    val x = b * GL_LUT_SIZE + r
                    val y = g
                    pixels[y * GL_STRIP_WIDTH + x] = Color.argb(255, pr, pg, pb)
                }
            }
        }
        bmp.setPixels(pixels, 0, GL_STRIP_WIDTH, 0, 0, GL_STRIP_WIDTH, GL_STRIP_HEIGHT)
        return bmp
    }

    /**
     * Copies a user-picked `.cube` file into private app storage after validating it.
     * Returns `Pair(managedPath, displayName)` on success.
     */
    suspend fun importCubeFile(context: Context, uri: Uri): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to open LUT file.")
                val parsed = parseCubeLut(text)
                    ?: error("Unsupported or malformed .cube file (expected LUT_3D_SIZE 2..65).")

                var displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: "${parsed.title}.cube"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                    }
                }

                val dir = File(context.filesDir, "cutlab_luts").apply { mkdirs() }
                val dest = File(dir, "${UUID.randomUUID()}.cube")
                dest.writeText(text)
                parsedFileCache[dest.absolutePath] = resampleTable(parsed, GL_LUT_SIZE)
                dest.absolutePath to displayName
            }
        }
}
