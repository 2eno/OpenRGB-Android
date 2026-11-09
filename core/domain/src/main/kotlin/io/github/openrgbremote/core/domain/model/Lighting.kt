package io.github.openrgbremote.core.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Color as hue (0..360), saturation and value (0..1). The picker keeps HSV as its source of
 * truth because RGB loses the hue at black and the saturation at white/grey.
 */
data class HsvColor(val hue: Float, val saturation: Float, val value: Float) {

    /** `0xRRGGBB`. */
    fun toRgb(): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    fun toHex(): String = "#%06X".format(toRgb())

    companion object {
        val WHITE = HsvColor(0f, 0f, 1f)

        fun fromRgb(rgb: Int): HsvColor {
            val r = ((rgb shr 16) and 0xFF) / 255f
            val g = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val hue = when {
                delta == 0f -> 0f
                max == r -> 60f * (((g - b) / delta) % 6f)
                max == g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }
            return HsvColor(hue, if (max == 0f) 0f else delta / max, max)
        }
    }
}

/** What a color is applied to. */
sealed interface ColorTarget {
    val key: DeviceKey

    /** Every LED of the device. */
    data class Device(override val key: DeviceKey) : ColorTarget

    /** Only the given zones (indices into the device's zone list). */
    data class Zones(override val key: DeviceKey, val zones: Set<Int>) : ColorTarget

    /** A single LED (index into the device's LED list). */
    data class Led(override val key: DeviceKey, val index: Int) : ColorTarget
}
