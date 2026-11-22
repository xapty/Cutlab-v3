package com.example.engine.effects

import android.graphics.Color
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.LutPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class Lut3DEngineTest {

    @Test
    fun `parseCubeLut parses valid 3D cube with title comments and domain range`() {
        val sb = StringBuilder()
        sb.appendLine("# Comment header")
        sb.appendLine("TITLE \"Warm Sunset\"")
        sb.appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        sb.appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        sb.appendLine("LUT_3D_SIZE 2")
        // b=0..1, g=0..1, r=0..1 (red varies fastest)
        for (b in 0..1) {
            for (g in 0..1) {
                for (r in 0..1) {
                    val outR = (r * 0.9f + 0.1f).coerceIn(0f, 1f)
                    val outG = (g * 0.85f).coerceIn(0f, 1f)
                    val outB = (b * 0.7f).coerceIn(0f, 1f)
                    sb.appendLine("$outR $outG $outB")
                }
            }
        }

        val parsed = Lut3DEngine.parseCubeLut(sb.toString())
        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)
        assertEquals("Warm Sunset", parsed.title)
        assertEquals(2 * 2 * 2 * 3, parsed.rgbData.size)

        // Black corner (0,0,0) -> (0.1, 0.0, 0.0)
        val (r0, g0, b0) = Lut3DEngine.sampleTrilinear(parsed, 0f, 0f, 0f)
        assertEquals(0.1f, r0, 0.01f)
        assertEquals(0.0f, g0, 0.01f)
        assertEquals(0.0f, b0, 0.01f)

        // Midpoint (0.5, 0.5, 0.5) trilinear sample
        val (rm, gm, bm) = Lut3DEngine.sampleTrilinear(parsed, 0.5f, 0.5f, 0.5f)
        assertEquals(0.55f, rm, 0.02f)
        assertEquals(0.425f, gm, 0.02f)
        assertEquals(0.35f, bm, 0.02f)
    }

    @Test
    fun `parseCubeLut rejects 1D LUTs or truncated payloads`() {
        val lut1d = """
            LUT_1D_SIZE 4
            0.0 0.0 0.0
            0.3 0.3 0.3
            0.6 0.6 0.6
            1.0 1.0 1.0
        """.trimIndent()
        assertNull(Lut3DEngine.parseCubeLut(lut1d))

        val truncated = """
            LUT_3D_SIZE 4
            0.0 0.0 0.0
            1.0 1.0 1.0
        """.trimIndent()
        assertNull(Lut3DEngine.parseCubeLut(truncated))
    }

    @Test
    fun `cinema presets generate distinct LUTs and pack cleanly to 256x16 GL strip bitmap`() {
        val tealOrange = Lut3DEngine.resolveLutTable(ClipLutSpec(LutPreset.TEAL_ORANGE, 1f))
        val noirPunch = Lut3DEngine.resolveLutTable(ClipLutSpec(LutPreset.NOIR_PUNCH, 1f))
        assertNotNull(tealOrange)
        assertNotNull(noirPunch)
        assertEquals(Lut3DEngine.GL_LUT_SIZE, tealOrange!!.size)

        val (tr, tg, tb) = Lut3DEngine.sampleTrilinear(tealOrange, 0.8f, 0.45f, 0.25f)
        val (nr, ng, nb) = Lut3DEngine.sampleTrilinear(noirPunch!!, 0.8f, 0.45f, 0.25f)
        assertTrue("Teal & Orange and Noir Punch should diverge significantly", kotlin.math.abs(tr - tb) > kotlin.math.abs(nr - nb))
        assertTrue(nr in 0f..1f && ng in 0f..1f && nb in 0f..1f && tg in 0f..1f)

        val strip = Lut3DEngine.packTableToGlStripBitmap(tealOrange)
        assertEquals(256, strip.width)
        assertEquals(16, strip.height)
        // Top-right corner b=15, r=15 -> x = 15*16 + 15 = 255, g=15 -> y=15 should be bright highlight
        val cornerPx = strip.getPixel(255, 15)
        assertTrue(Color.red(cornerPx) > 180)
        assertTrue(Color.green(cornerPx) > 180)
        strip.recycle()
    }
}
