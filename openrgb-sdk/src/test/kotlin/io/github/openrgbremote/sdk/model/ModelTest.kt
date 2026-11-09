package io.github.openrgbremote.sdk.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelTest {

    @Test
    fun `device type mapping follows the enum of each OpenRGB release`() {
        // 0.3/0.4 (protocol 0): HEADSET_STAND = 9 was the last type, 10 meant unknown.
        assertEquals(DeviceType.HEADSET_STAND, DeviceType.fromRaw(9, 0))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromRaw(10, 0))
        // 0.5 (protocol 1) added GAMEPAD and LIGHT.
        assertEquals(DeviceType.GAMEPAD, DeviceType.fromRaw(10, 1))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromRaw(12, 1))
        // 0.6 (protocol 2) added SPEAKER and VIRTUAL.
        assertEquals(DeviceType.SPEAKER, DeviceType.fromRaw(12, 2))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromRaw(14, 2))
        // Protocol 3 covers 0.7 and 0.8; resolved towards 0.8 (CASE = 15, UNKNOWN = 19).
        assertEquals(DeviceType.STORAGE, DeviceType.fromRaw(14, 3))
        assertEquals(DeviceType.CASE, DeviceType.fromRaw(15, 3))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromRaw(19, 3))
        // 0.9 (protocol 4): KEYPAD = 18, UNKNOWN = 19.
        assertEquals(DeviceType.KEYPAD, DeviceType.fromRaw(18, 4))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromRaw(19, 4))
        // 1.0rc2+ (protocol 5/6): LAPTOP = 19, MONITOR = 20, UNKNOWN = 21.
        assertEquals(DeviceType.LAPTOP, DeviceType.fromRaw(19, 6))
        assertEquals(DeviceType.MONITOR, DeviceType.fromRaw(20, 6))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromRaw(21, 6))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromRaw(-1, 6))
        assertEquals(DeviceType.UNKNOWN, DeviceType.fromRaw(999, 6))
    }

    @Test
    fun `rgb color accessors and hex`() {
        val c = RgbColor.of(0x12, 0x34, 0x56)
        assertEquals(0x123456, c.rgb)
        assertEquals(0x12, c.red)
        assertEquals(0x34, c.green)
        assertEquals(0x56, c.blue)
        assertEquals("#123456", c.toHex())
        assertEquals(0xFF123456.toInt(), c.toArgb())
    }

    @Test
    fun `hex parsing accepts common notations`() {
        assertEquals(RgbColor(0xFF8800), RgbColor.parseHex("#ff8800"))
        assertEquals(RgbColor(0xFF8800), RgbColor.parseHex("FF8800"))
        assertEquals(RgbColor(0xFF8800), RgbColor.parseHex(" #f80 "))
        assertNull(RgbColor.parseHex("#12345"))
        assertNull(RgbColor.parseHex("zzzzzz"))
    }

    @Test
    fun `color arrays compare by content and are immutable`() {
        val source = intArrayOf(1, 2, 3)
        val a = ColorArray.of(source)
        source[0] = 99
        assertEquals(ColorArray.of(intArrayOf(1, 2, 3)), a)
        a.toIntArray()[1] = 42
        assertEquals(2, a.rgbAt(1))
    }

    @Test
    fun `direct mode lookup prefers Direct and falls back to any per-LED mode`() {
        val perLed = ModeFlags.HAS_PER_LED_COLOR
        fun dev(vararg modes: DeviceMode) = OpenRgbDevice(0, 0, DeviceType.LEDSTRIP, 4, "x", modes = modes.toList())
        assertEquals(1, dev(DeviceMode("Static", flags = perLed), DeviceMode("Direct", flags = perLed)).directModeIndex())
        assertEquals(0, dev(DeviceMode("Custom", flags = perLed), DeviceMode("Rainbow")).directModeIndex())
        assertEquals(1, dev(DeviceMode("Rainbow"), DeviceMode("Per Key", flags = perLed)).directModeIndex())
        assertNull(dev(DeviceMode("Rainbow")).directModeIndex())
    }
}
