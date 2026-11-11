package dev.eno2.openrgbremote.core.domain.model

import dev.eno2.openrgbremote.core.effects.AudioLevels
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.core.effects.EffectRenderer
import dev.eno2.openrgbremote.core.effects.LedLayout
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.model.Zone
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
 * Turns a profile into a dynamic one: instead of painting static colors, applying it starts
 * [effect] with the profile's colors and brightness.
 */
data class ProfileAnimation(
    val effect: EffectId,
    /** [EffectParams.MIN_SPEED]..[EffectParams.MAX_SPEED]. */
    val speed: Float = 1f,
    val reverse: Boolean = false,
)

/**
 * A lighting profile: a palette, its layout and a brightness, optionally animated by an effect.
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
    /** Null for a static profile. */
    val animation: ProfileAnimation? = null,
) {
    val isBuiltIn: Boolean get() = presetKey != null
    val isDynamic: Boolean get() = animation != null

    /** Effect parameters of a dynamic profile; the palette is only passed to effects that use it. */
    fun effectParams(): EffectParams {
        val animation = animation ?: return EffectParams(brightness = brightness)
        return EffectParams(
            speed = animation.speed.coerceIn(EffectParams.MIN_SPEED, EffectParams.MAX_SPEED),
            reverse = animation.reverse,
            colors = if (animation.effect.usesColors) colors.take(EffectParams.MAX_COLORS) else emptyList(),
            brightness = brightness.coerceIn(0f, 1f),
        )
    }

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

    /**
     * [samples] colors for a preview strip. Dynamic profiles show one representative frame of
     * their effect, rendered by the same code that drives the devices.
     */
    fun preview(profile: Profile, samples: Int): IntArray {
        val animation = profile.animation ?: return IntArray(samples) { colorAt(profile, it, samples) }
        val params = profile.effectParams()
        val out = IntArray(samples)
        if (animation.effect in SPATIAL_EFFECTS) {
            EffectRenderer.render(animation.effect, PREVIEW_TIME, LedLayout.linear(samples), params, AudioLevels.SILENCE, out)
        } else {
            // Effects that change over time rather than along the strip would show a single
            // (or, for music, a dark) frame; their palette says more.
            val strip = profile.copy(colors = params.paletteFor(animation.effect), layout = ColorLayout.GRADIENT, animation = null)
            for (i in out.indices) out[i] = colorAt(strip, i, samples)
        }
        return out
    }

    /**
     * The single color used for devices that cannot show per-LED colors. For a dynamic profile
     * that is the first color of the effect's palette.
     */
    fun dominantColor(profile: Profile): Int {
        val animation = profile.animation
        val first = if (animation != null) profile.effectParams().paletteFor(animation.effect).first() else profile.colors.firstOrNull() ?: 0
        return dim(first, profile.brightness)
    }

    /** Effect time of the still preview frame: far enough in that every effect shows its colors. */
    private const val PREVIEW_TIME = 1.7
    private val SPATIAL_EFFECTS = setOf(EffectId.RAINBOW_WAVE, EffectId.FIRE, EffectId.AURORA, EffectId.GRADIENT_FLOW)

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
