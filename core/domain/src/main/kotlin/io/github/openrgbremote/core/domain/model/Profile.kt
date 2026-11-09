package io.github.openrgbremote.core.domain.model

import io.github.openrgbremote.sdk.model.OpenRgbDevice
import io.github.openrgbremote.sdk.model.Zone
import kotlin.math.roundToInt

/** How a profile's colors are spread over the LEDs of a zone. */
enum class ColorLayout {
    /** Smooth transition from the first to the last color. */
    GRADIENT,

    /** Equal blocks, one per color. */
    BLOCKS,

    /** LED by LED: color 1, color 2, ..., color 1, ... */
    ALTERNATING,
}

/**
 * A static lighting profile: a palette, its layout and a brightness.
 * Built-in profiles have a [presetKey] and negative ids; they cannot be edited or deleted.
 */
data class Profile(
    val id: Long = 0,
    val name: String,
    /** `0xRRGGBB`, at least one. */
    val colors: List<Int>,
    val layout: ColorLayout = ColorLayout.GRADIENT,
    /** 0..1, scales every color. */
    val brightness: Float = 1f,
    val presetKey: String? = null,
    val createdAtMillis: Long = 0,
) {
    val isBuiltIn: Boolean get() = presetKey != null

    companion object {
        const val MAX_COLORS = 16
        const val MAX_NAME_LENGTH = 60
    }
}

/** The profiles shipped with the app. */
object BuiltInProfiles {
    val all: List<Profile> = listOf(
        preset(-1, "ocean", "Ocean", ColorLayout.GRADIENT, 1f, 0x001A66, 0x0066FF, 0x00C8FF, 0x00FFC8),
        preset(-2, "sunset", "Sunset", ColorLayout.GRADIENT, 1f, 0xFF2D00, 0xFF6A00, 0xFFB000, 0xFF2D70, 0x7A1FA2),
        preset(-3, "cyberpunk", "Cyberpunk", ColorLayout.GRADIENT, 1f, 0xFF00C8, 0x7A00FF, 0x00E5FF),
        preset(-4, "forest", "Forest", ColorLayout.GRADIENT, 1f, 0x0B3D0B, 0x1FA84A, 0x9BE564, 0x2E7D32),
        preset(-5, "gaming_red", "Gaming Red", ColorLayout.GRADIENT, 1f, 0xFF0000),
        preset(-6, "warm_white", "Warm White", ColorLayout.GRADIENT, 1f, 0xFFB46B),
        preset(-7, "night", "Night Mode", ColorLayout.GRADIENT, 0.15f, 0xFF3B00),
        preset(-8, "off", "Off", ColorLayout.GRADIENT, 1f, 0x000000),
    )

    fun byId(id: Long): Profile? = all.firstOrNull { it.id == id }

    private fun preset(id: Long, key: String, name: String, layout: ColorLayout, brightness: Float, vararg colors: Int) =
        Profile(id, name, colors.toList(), layout, brightness, presetKey = key)
}

/** Turns a [Profile] into LED colors. Pure functions, so the UI preview and the device agree. */
object ProfileRenderer {

    /** Color of LED [index] out of [count] in a row. */
    fun colorAt(profile: Profile, index: Int, count: Int): Int {
        val colors = profile.colors
        if (colors.isEmpty()) return 0
        val raw = when {
            colors.size == 1 -> colors[0]
            profile.layout == ColorLayout.ALTERNATING -> colors[index.mod(colors.size)]
            profile.layout == ColorLayout.BLOCKS -> colors[(index.toLong() * colors.size / count.coerceAtLeast(1)).toInt().coerceIn(0, colors.lastIndex)]
            else -> {
                val position = if (count <= 1) 0f else index.toFloat() / (count - 1)
                val scaled = position.coerceIn(0f, 1f) * (colors.size - 1)
                val lower = scaled.toInt().coerceAtMost(colors.size - 2)
                mix(colors[lower], colors[lower + 1], scaled - lower)
            }
        }
        return dim(raw, profile.brightness)
    }

    /** One color per LED of [zone]. Matrix zones are colored by column, so gradients run left to right. */
    fun renderZone(profile: Profile, zone: Zone): IntArray {
        val out = IntArray(zone.ledsCount)
        val matrix = zone.matrixMap
        if (zone.type.isMatrix && matrix != null) {
            out.fill(colorAt(profile, 0, 1)) // LEDs missing from the map
            for (row in 0 until matrix.height) for (column in 0 until matrix.width) {
                val led = matrix.ledAt(row, column) ?: continue
                if (led in out.indices) out[led] = colorAt(profile, column, matrix.width)
            }
        } else {
            for (i in out.indices) out[i] = colorAt(profile, i, out.size)
        }
        return out
    }

    /** One color per LED of [device]; every zone shows the complete profile. */
    fun renderDevice(profile: Profile, device: OpenRgbDevice): IntArray {
        val out = IntArray(device.ledCount) { colorAt(profile, 0, 1) }
        for (zone in device.zones) {
            val colors = renderZone(profile, zone)
            for (i in colors.indices) {
                val target = zone.startIndex + i
                if (target in out.indices) out[target] = colors[i]
            }
        }
        return out
    }

    /** [samples] colors for a preview strip. */
    fun preview(profile: Profile, samples: Int): IntArray = IntArray(samples) { colorAt(profile, it, samples) }

    /** The single color used for devices that cannot show per-LED colors. */
    fun dominantColor(profile: Profile): Int = dim(profile.colors.firstOrNull() ?: 0, profile.brightness)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).roundToInt().coerceIn(0, 255)
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun dim(rgb: Int, brightness: Float): Int {
        val f = brightness.coerceIn(0f, 1f)
        if (f == 1f) return rgb and 0xFFFFFF
        fun channel(shift: Int) = (((rgb shr shift) and 0xFF) * f).roundToInt()
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
