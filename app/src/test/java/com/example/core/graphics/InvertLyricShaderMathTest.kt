package com.example.core.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvertLyricShaderMathTest {

    @Test
    fun `strength 0 percent returns original video unchanged even inside glyph`() {
        val result = GlCompositorMath.evaluateInvertLyricPixel(
            baseR = 0.25f,
            baseG = 0.60f,
            baseB = 0.90f,
            baseA = 1.0f,
            maskAlpha = 1.0f,
            invertStrength = 0.0f,
            opacity = 1.0f,
        )
        assertEquals(0.25f, result.r, 0.0001f)
        assertEquals(0.60f, result.g, 0.0001f)
        assertEquals(0.90f, result.b, 0.0001f)
        assertEquals(1.0f, result.a, 0.0001f)
    }

    @Test
    fun `strength 100 percent returns inverted RGB inside glyphs while preserving alpha`() {
        val result = GlCompositorMath.evaluateInvertLyricPixel(
            baseR = 0.20f,
            baseG = 0.75f,
            baseB = 0.10f,
            baseA = 0.85f,
            maskAlpha = 1.0f,
            invertStrength = 1.0f,
            opacity = 1.0f,
        )
        assertEquals(0.80f, result.r, 0.0001f)
        assertEquals(0.25f, result.g, 0.0001f)
        assertEquals(0.90f, result.b, 0.0001f)
        assertEquals(0.85f, result.a, 0.0001f)
    }

    @Test
    fun `outside glyphs maskAlpha 0 returns original video unchanged`() {
        val result = GlCompositorMath.evaluateInvertLyricPixel(
            baseR = 0.35f,
            baseG = 0.45f,
            baseB = 0.80f,
            baseA = 1.0f,
            maskAlpha = 0.0f,
            invertStrength = 1.0f,
            opacity = 1.0f,
        )
        assertEquals(0.35f, result.r, 0.0001f)
        assertEquals(0.45f, result.g, 0.0001f)
        assertEquals(0.80f, result.b, 0.0001f)
        assertEquals(1.0f, result.a, 0.0001f)
    }

    @Test
    fun `partial glyph coverage blends original and inverted pixels proportionally`() {
        // Original = (0.2, 0.4, 0.8), Inverted = (0.8, 0.6, 0.2), midpoint at maskAlpha = 0.5 -> (0.5, 0.5, 0.5)
        val midpoint = GlCompositorMath.evaluateInvertLyricPixel(
            baseR = 0.2f,
            baseG = 0.4f,
            baseB = 0.8f,
            baseA = 1.0f,
            maskAlpha = 0.5f,
            invertStrength = 1.0f,
            opacity = 1.0f,
        )
        assertEquals(0.5f, midpoint.r, 0.0001f)
        assertEquals(0.5f, midpoint.g, 0.0001f)
        assertEquals(0.5f, midpoint.b, 0.0001f)

        // With 50% opacity on top of 50% mask coverage -> 25% mix toward inverted
        val quarter = GlCompositorMath.evaluateInvertLyricPixel(
            baseR = 0.2f,
            baseG = 0.4f,
            baseB = 0.8f,
            baseA = 0.9f,
            maskAlpha = 0.5f,
            invertStrength = 1.0f,
            opacity = 0.5f,
        )
        assertEquals(0.35f, quarter.r, 0.0001f)
        assertEquals(0.45f, quarter.g, 0.0001f)
        assertEquals(0.65f, quarter.b, 0.0001f)
        assertEquals(0.9f, quarter.a, 0.0001f)
    }

    @Test
    fun `glsl invert shader declares real framebuffer sampling and RGB inversion`() {
        val shader = CompositorShaders.INVERT_TEXT_FRAGMENT_SHADER
        assertTrue(shader.contains("uniform sampler2D uBaseTex;"))
        assertTrue(shader.contains("uniform sampler2D uMaskTex;"))
        assertTrue(shader.contains("uniform float uInvertStrength;"))
        assertTrue(shader.contains("uniform float uEdgeSoftness;"))
        assertTrue(shader.contains("uniform float uOpacity;"))
        assertTrue(shader.contains("vec3 inverted = vec3(1.0) - base.rgb;"))
        assertTrue(shader.contains("mix(base.rgb, inverted, amount)"))
    }
}
