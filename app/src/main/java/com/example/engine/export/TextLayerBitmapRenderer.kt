package com.example.engine.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.ScaleXSpan
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.EasingPreset
import com.example.engine.project.KaraokeHighlightMode
import com.example.engine.project.LayerTransform
import com.example.engine.project.TextAlignMode
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextRenderMode
import com.example.engine.project.TextStylePreset
import com.example.engine.text.UnifiedTextEngine
import com.example.engine.text.WordPlaybackState
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

data class RenderedTextBitmapFrame(
    val bitmap: Bitmap,
    val combinedAlpha: Float,
    val combinedScaleX: Float,
    val combinedScaleY: Float,
    val centerOffsetXNorm: Float,
    val centerOffsetYNorm: Float,
    val rotationDeg: Float,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val isCacheManaged: Boolean = false,
    val cacheKey: String = "",
    val isInvertMask: Boolean = false,
    val invertStrength: Float = 1.0f,
    val invertEdgeSoftness: Float = 0.08f,
)

/**
 * Renders CutLab TextOverlaySpec + animated LayerTransform onto an ARGB_8888 Bitmap for GPU
 * upload inside both live preview ([GlCompositorRenderer] for Invert Lyric) and Path B
 * ([CustomGlVideoExporter]) so typography, word-level karaoke highlighting, pills, strokes,
 * shadows, and Invert Lyric glyph masks stay identical across preview and export.
 */
class TextLayerBitmapRenderer(
    private val context: Context,
) {
    private val spaceGroteskBold by lazy {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.space_grotesk) }.getOrNull()
        if (base != null) Typeface.create(base, Typeface.BOLD) else Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val interMedium by lazy {
        runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull()
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val jetBrainsMono by lazy {
        runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono) }.getOrNull()
            ?: Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val serifBold by lazy { Typeface.create(Typeface.SERIF, Typeface.BOLD) }

    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > 16
        }
    }

    @Synchronized
    fun clearCache() {
        bitmapCache.clear()
    }

    fun render(
        spec: TextOverlaySpec,
        transform: LayerTransform,
        clipRelativeMs: Long,
        canvasWidth: Int,
        canvasHeight: Int,
    ): RenderedTextBitmapFrame? {
        val animWindowMs = 450f
        val rawAnimT = (clipRelativeMs.toFloat() / animWindowMs).coerceIn(0f, 1f)
        val eased = EffectsEngine.applyEasing(rawAnimT, EasingPreset.EASE_OUT)

        val karaokeEval = UnifiedTextEngine.evaluateKaraoke(spec, clipRelativeMs)
        val fullPlainText = if (karaokeEval.hasTimedWords) {
            karaokeEval.words.joinToString(" ") { it.word.text }
        } else {
            spec.text
        }
        val visibleCharCount = when (spec.animationPreset) {
            TextAnimationPreset.TYPEWRITER -> {
                if (fullPlainText.isEmpty()) 0 else {
                    (fullPlainText.length * (clipRelativeMs.toFloat() / 900f).coerceIn(0.05f, 1f))
                        .roundToInt()
                        .coerceIn(1, fullPlainText.length)
                }
            }
            else -> fullPlainText.length
        }
        if (visibleCharCount <= 0 || fullPlainText.isBlank()) return null

        val isInvertMask = spec.renderMode == TextRenderMode.INVERT_VIDEO

        val animAlpha = when (spec.animationPreset) {
            TextAnimationPreset.FADE_IN, TextAnimationPreset.SLIDE_UP -> eased
            TextAnimationPreset.POP_OVERSHOOT -> (eased * 1.4f).coerceIn(0f, 1f)
            else -> 1f
        }
        val animScale = when (spec.animationPreset) {
            TextAnimationPreset.POP_OVERSHOOT -> {
                if (rawAnimT < 0.7f) {
                    0.65f + (rawAnimT / 0.7f) * 0.45f
                } else {
                    1.10f - ((rawAnimT - 0.7f) / 0.3f) * 0.10f
                }
            }
            else -> 1f
        }
        val animTranslateYNdc = when (spec.animationPreset) {
            TextAnimationPreset.SLIDE_UP -> -((1f - eased) * 0.08f)
            else -> 0f
        }
        val glitchOffsetXNdc = if (spec.animationPreset == TextAnimationPreset.GLITCH && rawAnimT < 1f) {
            sin(clipRelativeMs * 0.25f) * 0.02f * (1f - rawAnimT)
        } else 0f

        val minDim = minOf(canvasWidth, canvasHeight).toFloat().coerceAtLeast(360f)
        val textSizePx = (minDim * 0.068f * spec.fontScale.coerceIn(0.5f, 2.5f)).coerceIn(18f, 260f)

        val karaokeSig = if (karaokeEval.hasTimedWords && spec.karaokeMode != KaraokeHighlightMode.NONE) {
            karaokeEval.cacheSignature()
        } else {
            "none"
        }
        val cacheKey = buildString {
            append(if (isInvertMask) "INV|" else "NRM|")
            append(spec.stylePreset.name).append('|')
            append(spec.alignment.name).append('|')
            append(textSizePx.roundToInt()).append('|')
            append(canvasWidth).append('x').append(canvasHeight).append('|')
            append(spec.textColorArgb).append('|')
            append(spec.highlightColorArgb).append('|')
            append(spec.spokenColorArgb).append('|')
            append(spec.backgroundColorArgb).append('|')
            append(spec.hasShadow).append('|')
            append(spec.hasStroke).append('|')
            append(spec.invertIncludeBackground).append('|')
            append((spec.wordSpacingEm * 100).roundToInt()).append('|')
            append(visibleCharCount).append('|')
            append(fullPlainText).append('|')
            append(karaokeSig)
        }

        val cachedBitmap = synchronized(this) { bitmapCache[cacheKey]?.takeIf { !it.isRecycled } }
        val bitmap = cachedBitmap ?: renderBitmapInternal(
            spec = spec,
            isInvertMask = isInvertMask,
            fullPlainText = fullPlainText,
            visibleCharCount = visibleCharCount,
            karaokeEval = karaokeEval,
            textSizePx = textSizePx,
            minDim = minDim,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
        )?.also { generated ->
            synchronized(this) { bitmapCache[cacheKey] = generated }
        } ?: return null

        val posX = UnifiedTextEngine.effectivePositionX(spec, transform)
        val posY = UnifiedTextEngine.effectivePositionY(spec, transform)

        return RenderedTextBitmapFrame(
            bitmap = bitmap,
            combinedAlpha = (animAlpha * transform.opacity).coerceIn(0f, 1f),
            combinedScaleX = animScale * transform.scaleX,
            combinedScaleY = animScale * transform.scaleY,
            centerOffsetXNorm = posX + glitchOffsetXNdc * 0.5f,
            centerOffsetYNorm = posY - animTranslateYNdc * 0.5f,
            rotationDeg = transform.rotationDeg,
            anchorX = transform.anchorX,
            anchorY = transform.anchorY,
            isCacheManaged = true,
            cacheKey = cacheKey,
            isInvertMask = isInvertMask,
            invertStrength = spec.invertStrength.coerceIn(0f, 1f),
            invertEdgeSoftness = spec.invertEdgeSoftness.coerceIn(0f, 1f),
        )
    }

    private fun renderBitmapInternal(
        spec: TextOverlaySpec,
        isInvertMask: Boolean,
        fullPlainText: String,
        visibleCharCount: Int,
        karaokeEval: com.example.engine.text.KaraokeEvaluation,
        textSizePx: Float,
        minDim: Float,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Bitmap? {
        val typeface = when (spec.stylePreset) {
            TextStylePreset.BOLD_TITLE,
            TextStylePreset.NEON_POP,
            TextStylePreset.KINETIC_BADGE,
            -> spaceGroteskBold
            TextStylePreset.CINEMA_SERIF -> serifBold
            TextStylePreset.MONO_TYPEWRITER -> jetBrainsMono
            TextStylePreset.MINIMAL_SUBTITLE -> interMedium
        }

        val styledText: CharSequence = buildStyledSequence(
            spec = spec,
            isInvertMask = isInvertMask,
            fullPlainText = fullPlainText,
            visibleCharCount = visibleCharCount,
            karaokeEval = karaokeEval,
        )
        if (styledText.isEmpty()) return null

        val bgArgb = spec.backgroundColorArgb.toInt()
        val drawBg = if (isInvertMask) {
            spec.invertIncludeBackground && Color.alpha(bgArgb) > 6
        } else {
            Color.alpha(bgArgb) > 6
        }
        val padH = if (drawBg) textSizePx * 0.55f else textSizePx * 0.28f
        val padV = if (drawBg) textSizePx * 0.28f else textSizePx * 0.18f
        val maxContentWidth = (canvasWidth * 0.86f - padH * 2f).roundToInt().coerceAtLeast(120)

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = textSizePx
            this.color = if (isInvertMask) Color.WHITE else spec.textColorArgb.toInt()
            if (!isInvertMask && spec.hasShadow) {
                setShadowLayer(
                    (textSizePx * 0.14f).coerceAtLeast(2f),
                    textSizePx * 0.04f,
                    textSizePx * 0.06f,
                    Color.argb(192, 0, 0, 0),
                )
            }
        }

        val layoutAlign = when (spec.alignment) {
            TextAlignMode.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignMode.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignMode.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }

        val layout = StaticLayout.Builder
            .obtain(styledText, 0, styledText.length, fillPaint, maxContentWidth)
            .setAlignment(layoutAlign)
            .setIncludePad(true)
            .build()

        var maxLineW = 0f
        for (line in 0 until layout.lineCount) {
            val lw = layout.getLineWidth(line)
            if (lw > maxLineW) maxLineW = lw
        }
        val tightLayoutW = ceil(maxLineW).toInt().coerceIn(2, maxContentWidth)
        val tightLayout = if (tightLayoutW < maxContentWidth) {
            StaticLayout.Builder
                .obtain(styledText, 0, styledText.length, fillPaint, tightLayoutW)
                .setAlignment(layoutAlign)
                .setIncludePad(true)
                .build()
        } else {
            layout
        }

        val shadowMargin = if (!isInvertMask && (spec.hasShadow || spec.hasStroke)) {
            textSizePx * 0.22f
        } else {
            4f
        }
        val bmpW = ceil(tightLayout.width + padH * 2f + shadowMargin * 2f).toInt().coerceIn(8, canvasWidth)
        val bmpH = ceil(tightLayout.height + padV * 2f + shadowMargin * 2f).toInt().coerceIn(8, canvasHeight)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (drawBg) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isInvertMask) Color.WHITE else bgArgb
                style = Paint.Style.FILL
            }
            val bgRect = RectF(
                shadowMargin,
                shadowMargin,
                bmpW - shadowMargin,
                bmpH - shadowMargin,
            )
            val corner = (minDim * 0.016f).coerceIn(6f, 28f)
            canvas.drawRoundRect(bgRect, corner, corner, bgPaint)
        }

        canvas.save()
        canvas.translate(shadowMargin + padH, shadowMargin + padV)

        if (!isInvertMask && spec.hasStroke) {
            val strokeText = styledText.toString()
            val strokePaint = TextPaint(fillPaint).apply {
                clearShadowLayer()
                style = Paint.Style.STROKE
                strokeWidth = (textSizePx * 0.14f).coerceIn(2.5f, 18f)
                strokeJoin = Paint.Join.ROUND
                color = Color.argb(220, 0, 0, 0)
            }
            val strokeLayout = StaticLayout.Builder
                .obtain(strokeText, 0, strokeText.length, strokePaint, tightLayout.width)
                .setAlignment(layoutAlign)
                .setIncludePad(true)
                .build()
            strokeLayout.draw(canvas)
        }

        tightLayout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun buildStyledSequence(
        spec: TextOverlaySpec,
        isInvertMask: Boolean,
        fullPlainText: String,
        visibleCharCount: Int,
        karaokeEval: com.example.engine.text.KaraokeEvaluation,
    ): CharSequence {
        val builder = SpannableStringBuilder()
        val spacingScale = (1f + spec.wordSpacingEm.coerceIn(0f, 1.5f) * 2.2f)

        if (!karaokeEval.hasTimedWords || spec.karaokeMode == KaraokeHighlightMode.NONE) {
            val clipped = fullPlainText.take(visibleCharCount)
            builder.append(clipped)
            if (spacingScale > 1.02f) {
                for (i in clipped.indices) {
                    if (clipped[i] == ' ') {
                        builder.setSpan(ScaleXSpan(spacingScale), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            return builder
        }

        var remainingChars = visibleCharCount
        karaokeEval.words.forEachIndexed { idx, evaluated ->
            if (remainingChars <= 0) return@forEachIndexed
            if (idx > 0) {
                val spaceStart = builder.length
                builder.append(' ')
                if (spacingScale > 1.02f) {
                    builder.setSpan(
                        ScaleXSpan(spacingScale),
                        spaceStart,
                        spaceStart + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                remainingChars -= 1
                if (remainingChars <= 0) return@forEachIndexed
            }
            val wordText = evaluated.word.text.take(remainingChars)
            remainingChars -= wordText.length
            val start = builder.length
            builder.append(wordText)
            val end = builder.length

            val colorInt = if (isInvertMask) {
                when (evaluated.state) {
                    WordPlaybackState.ACTIVE -> Color.argb(255, 255, 255, 255)
                    WordPlaybackState.SPOKEN -> Color.argb(215, 255, 255, 255)
                    WordPlaybackState.INACTIVE -> Color.argb(140, 255, 255, 255)
                }
            } else {
                when (evaluated.state) {
                    WordPlaybackState.ACTIVE -> spec.highlightColorArgb.toInt()
                    WordPlaybackState.SPOKEN -> spec.spokenColorArgb.toInt()
                    WordPlaybackState.INACTIVE -> spec.textColorArgb.toInt()
                }
            }
            builder.setSpan(
                ForegroundColorSpan(colorInt),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return builder
    }
}
