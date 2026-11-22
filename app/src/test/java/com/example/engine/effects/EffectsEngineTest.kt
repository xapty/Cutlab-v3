package com.example.engine.effects

import com.example.engine.project.ClipAdjustments
import com.example.engine.project.EasingPreset
import com.example.engine.project.FilterPreset
import com.example.engine.project.TextStylePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsEngineTest {

    @Test
    fun `mono preset zeroes saturation across RGB luminance weights`() {
        val effective = EffectsEngine.resolveEffectiveAdjustments(
            ClipAdjustments(filterPreset = FilterPreset.MONO),
        )
        assertEquals(0f, effective.saturation, 0.001f)
        val matrix = EffectsEngine.buildColorMatrixValues(effective)
        assertEquals(20, matrix.size)
        // Red, Green, Blue rows should share identical luminance proportions when saturation == 0
        assertEquals(matrix[0], matrix[5], 0.001f)
        assertEquals(matrix[1], matrix[6], 0.001f)
        assertEquals(matrix[2], matrix[7], 0.001f)
    }

    @Test
    fun `warm temperature shifts red offset positive and blue offset negative`() {
        val warm = ClipAdjustments(temperature = 0.5f)
        val matrix = EffectsEngine.buildColorMatrixValues(warm)
        val redBias = matrix[4]
        val blueBias = matrix[14]
        assertTrue(redBias > 0f)
        assertTrue(blueBias < 0f)
    }

    @Test
    fun `easing curves respect 0 and 1 endpoints and defaultTextSpec builds presets`() {
        EasingPreset.entries.forEach { easing ->
            assertEquals(0f, EffectsEngine.applyEasing(0f, easing), 0.0001f)
            assertEquals(1f, EffectsEngine.applyEasing(1f, easing), 0.0001f)
            val mid = EffectsEngine.applyEasing(0.5f, easing)
            assertTrue(mid in 0f..1f)
        }
        TextStylePreset.entries.forEach { preset ->
            val spec = EffectsEngine.defaultTextSpec(preset)
            assertTrue(spec.text.isNotBlank())
        }
    }
}
