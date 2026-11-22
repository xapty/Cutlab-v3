package com.example.engine.effects

import com.example.engine.project.ClipAdjustments
import com.example.engine.project.EasingPreset
import com.example.engine.project.FilterPreset
import com.example.engine.project.TextAlignMode
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
import kotlin.math.pow

object EffectsEngine {

    fun presetBaseline(preset: FilterPreset): ClipAdjustments = when (preset) {
        FilterPreset.NONE -> ClipAdjustments(filterPreset = FilterPreset.NONE)
        FilterPreset.CINEMATIC -> ClipAdjustments(
            filterPreset = FilterPreset.CINEMATIC,
            brightness = -0.04f,
            contrast = 1.18f,
            saturation = 0.92f,
            exposure = 0.05f,
            temperature = -0.10f,
            vignette = 0.35f,
        )
        FilterPreset.VIVID -> ClipAdjustments(
            filterPreset = FilterPreset.VIVID,
            brightness = 0.04f,
            contrast = 1.16f,
            saturation = 1.42f,
            exposure = 0.06f,
            sharpen = 0.25f,
        )
        FilterPreset.WARM_VINTAGE -> ClipAdjustments(
            filterPreset = FilterPreset.WARM_VINTAGE,
            brightness = 0.03f,
            contrast = 0.95f,
            saturation = 0.84f,
            temperature = 0.38f,
            vignette = 0.28f,
        )
        FilterPreset.COOL_SLATE -> ClipAdjustments(
            filterPreset = FilterPreset.COOL_SLATE,
            brightness = -0.02f,
            contrast = 1.10f,
            saturation = 0.78f,
            temperature = -0.34f,
            vignette = 0.18f,
        )
        FilterPreset.MONO -> ClipAdjustments(
            filterPreset = FilterPreset.MONO,
            brightness = 0.02f,
            contrast = 1.30f,
            saturation = 0f,
            vignette = 0.25f,
        )
        FilterPreset.DREAMY -> ClipAdjustments(
            filterPreset = FilterPreset.DREAMY,
            brightness = 0.08f,
            contrast = 0.88f,
            saturation = 1.12f,
            exposure = 0.14f,
            temperature = 0.12f,
            blurRadius = 1.5f,
        )
        FilterPreset.CYBER_NEON -> ClipAdjustments(
            filterPreset = FilterPreset.CYBER_NEON,
            brightness = -0.05f,
            contrast = 1.28f,
            saturation = 1.55f,
            temperature = -0.22f,
            vignette = 0.42f,
        )
    }

    fun resolveEffectiveAdjustments(user: ClipAdjustments): ClipAdjustments {
        if (user.filterPreset == FilterPreset.NONE) return user
        val base = presetBaseline(user.filterPreset)
        return ClipAdjustments(
            filterPreset = user.filterPreset,
            brightness = (base.brightness + user.brightness).coerceIn(-1f, 1f),
            contrast = (base.contrast * user.contrast).coerceIn(0.4f, 2.2f),
            saturation = (base.saturation * user.saturation).coerceIn(0f, 2.2f),
            exposure = (base.exposure + user.exposure).coerceIn(-1f, 1f),
            temperature = (base.temperature + user.temperature).coerceIn(-1f, 1f),
            tint = (base.tint + user.tint).coerceIn(-1f, 1f),
            vignette = (base.vignette + user.vignette).coerceIn(0f, 1f),
            blurRadius = (base.blurRadius + user.blurRadius).coerceIn(0f, 24f),
            sharpen = (base.sharpen + user.sharpen).coerceIn(0f, 1f),
        )
    }

    fun mergeAdjustments(base: ClipAdjustments, adjustmentLayer: ClipAdjustments): ClipAdjustments {
        val b = resolveEffectiveAdjustments(base)
        val a = resolveEffectiveAdjustments(adjustmentLayer)
        return ClipAdjustments(
            filterPreset = if (a.filterPreset != FilterPreset.NONE) a.filterPreset else b.filterPreset,
            brightness = (b.brightness + a.brightness).coerceIn(-1f, 1f),
            contrast = (b.contrast * a.contrast).coerceIn(0.4f, 2.4f),
            saturation = (b.saturation * a.saturation).coerceIn(0f, 2.4f),
            exposure = (b.exposure + a.exposure).coerceIn(-1f, 1f),
            temperature = (b.temperature + a.temperature).coerceIn(-1f, 1f),
            tint = (b.tint + a.tint).coerceIn(-1f, 1f),
            vignette = (b.vignette + a.vignette).coerceIn(0f, 1f),
            blurRadius = (b.blurRadius + a.blurRadius).coerceIn(0f, 24f),
            sharpen = (b.sharpen + a.sharpen).coerceIn(0f, 1f),
        )
    }

    /**
     * Produces a 20-float 4x5 RGBA color matrix combining saturation, contrast,
     * brightness, exposure, temperature, and green-magenta tint channel offsets.
     */
    fun buildColorMatrixValues(effective: ClipAdjustments): FloatArray {
        val sat = effective.saturation.coerceIn(0f, 2.5f)
        val lumR = 0.2126f
        val lumG = 0.7152f
        val lumB = 0.0722f
        val invSat = 1f - sat

        val sr = invSat * lumR
        val sg = invSat * lumG
        val sb = invSat * lumB

        val exposureGain = 2f.pow(effective.exposure.coerceIn(-1f, 1f) * 0.85f)
        val c = (effective.contrast.coerceIn(0.3f, 2.5f)) * exposureGain
        val contrastTranslate = 128f * (1f - effective.contrast.coerceIn(0.3f, 2.5f))
        val brightTranslate = effective.brightness.coerceIn(-1f, 1f) * 140f

        val temp = effective.temperature.coerceIn(-1f, 1f)
        val tint = effective.tint.coerceIn(-1f, 1f)
        val warmRedShift = temp * 28f + tint * 12f
        val tintGreenShift = -tint * 24f
        val coolBlueShift = -temp * 28f + tint * 12f

        val m00 = (sr + sat) * c
        val m01 = sg * c
        val m02 = sb * c

        val m10 = sr * c
        val m11 = (sg + sat) * c
        val m12 = sb * c

        val m20 = sr * c
        val m21 = sg * c
        val m22 = (sb + sat) * c

        return floatArrayOf(
            m00, m01, m02, 0f, contrastTranslate + brightTranslate + warmRedShift,
            m10, m11, m12, 0f, contrastTranslate + brightTranslate + tintGreenShift,
            m20, m21, m22, 0f, contrastTranslate + brightTranslate + coolBlueShift,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun applyEasing(t: Float, easing: EasingPreset): Float {
        val clamped = t.coerceIn(0f, 1f)
        return when (easing) {
            EasingPreset.LINEAR -> clamped
            EasingPreset.EASE_IN -> clamped * clamped
            EasingPreset.EASE_OUT -> 1f - (1f - clamped) * (1f - clamped)
            EasingPreset.EASE_IN_OUT -> if (clamped < 0.5f) {
                2f * clamped * clamped
            } else {
                1f - (-2f * clamped + 2f).pow(2) / 2f
            }
        }
    }

    fun defaultTextSpec(preset: TextStylePreset): TextOverlaySpec = when (preset) {
        TextStylePreset.BOLD_TITLE -> TextOverlaySpec(
            text = "BOLD TITLE",
            stylePreset = TextStylePreset.BOLD_TITLE,
            animationPreset = TextAnimationPreset.POP_OVERSHOOT,
            fontScale = 1.25f,
            textColorArgb = 0xFFFFFFFF,
            backgroundColorArgb = 0x00000000,
            hasShadow = true,
            hasStroke = true,
            alignment = TextAlignMode.CENTER,
            offsetY = 0f,
        )
        TextStylePreset.MINIMAL_SUBTITLE -> TextOverlaySpec(
            text = "Add clean captions here",
            stylePreset = TextStylePreset.MINIMAL_SUBTITLE,
            animationPreset = TextAnimationPreset.FADE_IN,
            fontScale = 0.85f,
            textColorArgb = 0xFFF4F6FA,
            backgroundColorArgb = 0xAA0B0E14,
            hasShadow = false,
            hasStroke = false,
            alignment = TextAlignMode.CENTER,
            offsetY = 0.30f,
        )
        TextStylePreset.NEON_POP -> TextOverlaySpec(
            text = "NEON ENERGY",
            stylePreset = TextStylePreset.NEON_POP,
            animationPreset = TextAnimationPreset.GLITCH,
            fontScale = 1.15f,
            textColorArgb = 0xFF38BDF8,
            backgroundColorArgb = 0x990F172A,
            hasShadow = true,
            hasStroke = true,
            alignment = TextAlignMode.CENTER,
            offsetY = -0.15f,
        )
        TextStylePreset.KINETIC_BADGE -> TextOverlaySpec(
            text = "CUTLAB EDIT",
            stylePreset = TextStylePreset.KINETIC_BADGE,
            animationPreset = TextAnimationPreset.SLIDE_UP,
            fontScale = 0.95f,
            textColorArgb = 0xFF0E1118,
            backgroundColorArgb = 0xFFF59E0B,
            hasShadow = false,
            hasStroke = false,
            alignment = TextAlignMode.CENTER,
            offsetY = 0.25f,
        )
        TextStylePreset.CINEMA_SERIF -> TextOverlaySpec(
            text = "CHAPTER ONE",
            stylePreset = TextStylePreset.CINEMA_SERIF,
            animationPreset = TextAnimationPreset.FADE_IN,
            fontScale = 1.05f,
            textColorArgb = 0xFFFDE68A,
            backgroundColorArgb = 0x00000000,
            hasShadow = true,
            hasStroke = false,
            alignment = TextAlignMode.CENTER,
            offsetY = 0f,
        )
        TextStylePreset.MONO_TYPEWRITER -> TextOverlaySpec(
            text = "REC // 24.0 FPS",
            stylePreset = TextStylePreset.MONO_TYPEWRITER,
            animationPreset = TextAnimationPreset.TYPEWRITER,
            fontScale = 0.90f,
            textColorArgb = 0xFF34D399,
            backgroundColorArgb = 0xB3090D12,
            hasShadow = false,
            hasStroke = false,
            alignment = TextAlignMode.LEFT,
            offsetY = -0.28f,
        )
    }

    fun filterAccentColorArgb(preset: FilterPreset): Long = when (preset) {
        FilterPreset.NONE -> 0xFF64748B
        FilterPreset.CINEMATIC -> 0xFF0EA5E9
        FilterPreset.VIVID -> 0xFFF43F5E
        FilterPreset.WARM_VINTAGE -> 0xFFF59E0B
        FilterPreset.COOL_SLATE -> 0xFF38BDF8
        FilterPreset.MONO -> 0xFFE2E8F0
        FilterPreset.DREAMY -> 0xFFA78BFA
        FilterPreset.CYBER_NEON -> 0xFF22D3EE
    }
}
