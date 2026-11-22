package com.example.engine.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.EasingPreset
import com.example.engine.project.LayerTransform
import com.example.engine.project.TextAlignMode
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
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
)

/**
 * Renders CutLab TextOverlaySpec + animated LayerTransform onto an ARGB_8888 Bitmap for GPU
 * upload inside the Path B OpenGL ES 2.0 compositor so text layers match live preview
 * typography, pills, strokes, shadows, and blend modes.
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

        val displayText = when (spec.animationPreset) {
            TextAnimationPreset.TYPEWRITER -> {
                if (spec.text.isEmpty()) {
                    ""
                } else {
                    val count = (spec.text.length * (clipRelativeMs.toFloat() / 900f).coerceIn(0.05f, 1f))
                        .roundToInt()
                        .coerceIn(1, spec.text.length)
                    spec.text.take(count)
                }
            }
            else -> spec.text
        }
        if (displayText.isEmpty()) return null

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

        val typeface = when (spec.stylePreset) {
            TextStylePreset.BOLD_TITLE,
            TextStylePreset.NEON_POP,
            TextStylePreset.KINETIC_BADGE,
            -> spaceGroteskBold
            TextStylePreset.CINEMA_SERIF -> serifBold
            TextStylePreset.MONO_TYPEWRITER -> jetBrainsMono
            TextStylePreset.MINIMAL_SUBTITLE -> interMedium
        }

        val minDim = minOf(canvasWidth, canvasHeight).toFloat().coerceAtLeast(360f)
        val textSizePx = (minDim * 0.068f * spec.fontScale.coerceIn(0.5f, 2.5f)).coerceIn(18f, 260f)

        val bgArgb = spec.backgroundColorArgb.toInt()
        val hasBg = Color.alpha(bgArgb) > 6
        val padH = if (hasBg) textSizePx * 0.55f else textSizePx * 0.28f
        val padV = if (hasBg) textSizePx * 0.28f else textSizePx * 0.18f
        val maxContentWidth = (canvasWidth * 0.86f - padH * 2f).roundToInt().coerceAtLeast(120)

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = textSizePx
            this.color = spec.textColorArgb.toInt()
            if (spec.hasShadow) {
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
            .obtain(displayText, 0, displayText.length, fillPaint, maxContentWidth)
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
                .obtain(displayText, 0, displayText.length, fillPaint, tightLayoutW)
                .setAlignment(layoutAlign)
                .setIncludePad(true)
                .build()
        } else {
            layout
        }

        val shadowMargin = if (spec.hasShadow || spec.hasStroke) (textSizePx * 0.22f) else 4f
        val bmpW = ceil(tightLayout.width + padH * 2f + shadowMargin * 2f).toInt().coerceIn(8, canvasWidth)
        val bmpH = ceil(tightLayout.height + padV * 2f + shadowMargin * 2f).toInt().coerceIn(8, canvasHeight)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        if (hasBg) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bgArgb
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

        if (spec.hasStroke) {
            val strokePaint = TextPaint(fillPaint).apply {
                clearShadowLayer()
                style = Paint.Style.STROKE
                strokeWidth = (textSizePx * 0.14f).coerceIn(2.5f, 18f)
                strokeJoin = Paint.Join.ROUND
                color = Color.argb(220, 0, 0, 0)
            }
            val strokeLayout = StaticLayout.Builder
                .obtain(displayText, 0, displayText.length, strokePaint, tightLayout.width)
                .setAlignment(layoutAlign)
                .setIncludePad(true)
                .build()
            strokeLayout.draw(canvas)
        }

        tightLayout.draw(canvas)
        canvas.restore()

        val posX = (spec.offsetX + transform.positionX).coerceIn(-0.95f, 0.95f)
        val posY = (spec.offsetY + transform.positionY).coerceIn(-0.95f, 0.95f)

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
        )
    }
}
