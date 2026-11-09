package io.github.openrgbremote.core.domain

import io.github.openrgbremote.core.domain.model.HsvColor
import kotlin.test.Test
import kotlin.test.assertEquals

class HsvColorTest {
    @Test
    fun `primary and secondary colors`() {
        assertEquals(0xFF0000, HsvColor(0f, 1f, 1f).toRgb())
        assertEquals(0xFFFF00, HsvColor(60f, 1f, 1f).toRgb())
        assertEquals(0x00FF00, HsvColor(120f, 1f, 1f).toRgb())
        assertEquals(0x00FFFF, HsvColor(180f, 1f, 1f).toRgb())
        assertEquals(0x0000FF, HsvColor(240f, 1f, 1f).toRgb())
        assertEquals(0xFF00FF, HsvColor(300f, 1f, 1f).toRgb())
        assertEquals(0xFF0000, HsvColor(360f, 1f, 1f).toRgb(), "hue wraps")
        assertEquals(0xFFFFFF, HsvColor.WHITE.toRgb())
        assertEquals(0x000000, HsvColor(200f, 1f, 0f).toRgb())
        assertEquals(0x808080, HsvColor(0f, 0f, 0.5f).toRgb())
        assertEquals("#FF8000", HsvColor(30f, 1f, 1f).toHex())
    }

    @Test
    fun `every rgb value survives the round trip through hsv`() {
        // All 16.7M colors would take a while; a step of 5 per channel still covers every sextant.
        for (r in 0..255 step 5) for (g in 0..255 step 5) for (b in 0..255 step 5) {
            val rgb = (r shl 16) or (g shl 8) or b
            assertEquals(rgb, HsvColor.fromRgb(rgb).toRgb())
        }
    }

    @Test
    fun `brightness scales the color without changing its hue`() {
        val orange = HsvColor.fromRgb(0xFF8000)
        assertEquals(0x804000, orange.copy(value = 0.5f).toRgb())
        assertEquals(30f, orange.hue, 0.2f)
    }
}
