package dev.eno2.openrgbremote.core.effects

import dev.eno2.openrgbremote.sdk.testing.RealDevices
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EffectRendererTest {

    private val strip = LedLayout.linear(30)

    private fun frame(effect: EffectId, time: Double, params: EffectParams = EffectParams(), layout: LedLayout = strip, audio: AudioLevels = AudioLevels.SILENCE) =
        IntArray(layout.size).also { EffectRenderer.render(effect, time, layout, params, audio, it) }

    private fun red(rgb: Int) = (rgb shr 16) and 0xFF
    private fun luminance(rgb: Int) = red(rgb) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)

    @ParameterizedTest
    @EnumSource(EffectId::class)
    fun `every effect is deterministic, valid and respects brightness`(effect: EffectId) {
        val audio = AudioLevels(FloatArray(AudioLevels.BANDS) { 0.6f }, 0.6f)
        val keyboard = LedLayout.of(RealDevices.all()[0])
        for (layout in listOf(strip, keyboard, LedLayout.linear(1))) {
            for (time in listOf(0.0, 0.37, 5.0, 123.456)) {
                val a = frame(effect, time, layout = layout, audio = audio)
                assertTrue(a.all { it in 0..0xFFFFFF }, "$effect produced alpha bits")
                assertEquals(a.toList(), frame(effect, time, layout = layout, audio = audio).toList(), "$effect must be deterministic")
                val dark = frame(effect, time, EffectParams(brightness = 0f), layout, audio)
                assertTrue(dark.all { it == 0 }, "$effect at brightness 0 must be black")
            }
        }
    }

    @Test
    fun `rainbow wave moves forward and reverses`() {
        val eleven = LedLayout.linear(11) // LEDs exactly 0.1 apart
        val at0 = frame(EffectId.RAINBOW_WAVE, 0.0, layout = eleven)
        // After 0.4 s at speed 1 the pattern moved by 0.1 (25 % of the length per second).
        val later = frame(EffectId.RAINBOW_WAVE, 0.4, layout = eleven)
        assertEquals(at0[0], later[1], "forward: LED 1 now shows what LED 0 showed")
        val reversed = frame(EffectId.RAINBOW_WAVE, 0.4, EffectParams(reverse = true), eleven)
        assertEquals(at0[1], reversed[0], "reverse: LED 0 now shows what LED 1 showed")
        assertEquals(0xFF0000, at0[0])
    }

    @Test
    fun `color cycle shows one color at a time and passes the palette colors`() {
        val params = EffectParams(colors = listOf(0xFF0000, 0x0000FF))
        val start = frame(EffectId.COLOR_CYCLE, 0.0, params)
        assertTrue(start.all { it == 0xFF0000 })
        assertTrue(frame(EffectId.COLOR_CYCLE, 5.0, params).all { it == 0x0000FF }, "half of the 10 s loop")
        assertEquals(start.toList(), frame(EffectId.COLOR_CYCLE, 10.0, params).toList(), "loops")
    }

    @Test
    fun `breathing peaks in the middle of its period and changes color each breath`() {
        val params = EffectParams(colors = listOf(0xFF0000, 0x00FF00))
        assertTrue(frame(EffectId.BREATHING, 0.0, params).all { it == 0 }, "starts dark")
        assertTrue(frame(EffectId.BREATHING, 2.0, params).all { it == 0xFF0000 }, "full at half period")
        assertTrue(frame(EffectId.BREATHING, 6.0, params).all { it == 0x00FF00 }, "second breath uses the next color")
        assertTrue(luminance(frame(EffectId.BREATHING, 1.0, params)[0]) < luminance(frame(EffectId.BREATHING, 1.5, params)[0]))
    }

    @Test
    fun `gradient flow loops seamlessly`() {
        val params = EffectParams(colors = listOf(0xFF0000, 0x00FF00, 0x0000FF))
        // One full loop of the palette takes 1 / 0.15 s.
        val period = 1.0 / 0.15
        assertEquals(frame(EffectId.GRADIENT_FLOW, 1.0, params).toList(), frame(EffectId.GRADIENT_FLOW, 1.0 + period, params).toList())
        assertNotEquals(frame(EffectId.GRADIENT_FLOW, 1.0, params).toList(), frame(EffectId.GRADIENT_FLOW, 2.0, params).toList())
    }

    @Test
    fun `fire stays within its palette, flickers over time and burns from the bottom of a matrix`() {
        val frames = (0 until 50).map { frame(EffectId.FIRE, it * 0.1) }
        assertTrue(frames.flatMap { it.toList() }.all { ((it shr 0) and 0xFF) <= ((it shr 8) and 0xFF) + 80 }, "fire colors are warm")
        assertTrue(frames.map { it.toList() }.toSet().size > 40, "flickers")

        val keyboard = LedLayout.of(RealDevices.all()[0])
        val totals = DoubleArray(2)
        repeat(40) { step ->
            val out = frame(EffectId.FIRE, step * 0.25, layout = keyboard)
            for (i in out.indices) if (keyboard.isMatrix[i]) totals[if (keyboard.y[i] > 0.5f) 1 else 0] += luminance(out[i]).toDouble()
        }
        assertTrue(totals[1] > totals[0] * 1.3, "bottom rows hotter than top rows: ${totals.toList()}")
    }

    @Test
    fun `pulse rings move outwards and inwards when reversed`() {
        val params = EffectParams(colors = listOf(0xFFFFFF))
        // At phase 0.1 the ring is near the center; LEDs 13..16 are the middle of 30.
        val early = frame(EffectId.PULSE, 0.2, params)
        assertTrue(red(early[15]) > red(early[0]))
        val late = frame(EffectId.PULSE, 1.8, params)
        assertTrue(red(late[0]) > red(late[15]), "ring reached the ends")
        val reversedEarly = frame(EffectId.PULSE, 0.2, params.copy(reverse = true))
        assertTrue(red(reversedEarly[0]) > red(reversedEarly[15]), "reverse starts outside")
    }

    @Test
    fun `aurora drifts slowly`() {
        val a = frame(EffectId.AURORA, 10.0)
        val b = frame(EffectId.AURORA, 10.05)
        val diff = a.indices.sumOf { kotlin.math.abs(luminance(a[it]) - luminance(b[it])) } / a.size
        assertTrue(diff < 20, "smooth between frames ($diff)")
        assertNotEquals(a.toList(), frame(EffectId.AURORA, 20.0).toList())
    }

    @Test
    fun `music maps bass to the left and treble to the right`() {
        val bass = AudioLevels(FloatArray(AudioLevels.BANDS) { if (it < 3) 1f else 0f }, 0.8f)
        val out = frame(EffectId.MUSIC, 0.0, audio = bass)
        assertTrue(luminance(out[0]) > luminance(out[29]) * 3, "left lit, right dim")
        val silent = frame(EffectId.MUSIC, 0.0)
        assertTrue(silent.all { luminance(it) < 60 }, "silence is almost dark")
        assertTrue(silent.any { it != 0 }, "but not off, so the user sees the effect runs")
    }

    @Test
    fun `layout follows zones and matrix maps of a real keyboard`() {
        val device = RealDevices.all()[0]
        val layout = LedLayout.of(device)
        assertEquals(device.ledCount, layout.size)
        val keyboardZone = device.zones[2]
        val matrixLeds = (keyboardZone.startIndex until keyboardZone.startIndex + keyboardZone.ledsCount)
        assertTrue(matrixLeds.all { layout.isMatrix[it] })
        assertEquals(0f, matrixLeds.minOf { layout.x[it] })
        assertEquals(1f, matrixLeds.maxOf { layout.x[it] })
        assertEquals(0f, layout.x[1], "linear zone starts at 0")
        assertEquals(1f, layout.x[10], "linear zone of 10 ends at 1")
        assertEquals(0.5f, layout.x[0], "single LED is centered")
    }
}
