package dev.eno2.openrgbremote.sdk.model

/**
 * 24-bit color stored as `0xRRGGBB`.
 *
 * Note: OpenRGB's native `RGBColor` is `0x00BBGGRR`. The conversion happens exclusively in the
 * protocol codec, so everything above the protocol layer uses the conventional layout that
 * matches Android's `Color` (minus alpha).
 */
@JvmInline
public value class RgbColor(public val rgb: Int) {
    public val red: Int get() = (rgb ushr 16) and 0xFF
    public val green: Int get() = (rgb ushr 8) and 0xFF
    public val blue: Int get() = rgb and 0xFF

    /** ARGB with full opacity, ready for `androidx.compose.ui.graphics.Color(Int)`. */
    public fun toArgb(): Int = rgb or (0xFF shl 24)

    public fun toHex(): String = "#%06X".format(rgb and 0xFFFFFF)

    override fun toString(): String = toHex()

    public companion object {
        public val BLACK: RgbColor = RgbColor(0x000000)
        public val WHITE: RgbColor = RgbColor(0xFFFFFF)

        public fun of(red: Int, green: Int, blue: Int): RgbColor =
            RgbColor(((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF))

        /** Parses `#RRGGBB`, `RRGGBB` or `#RGB`; returns null for anything else. */
        public fun parseHex(text: String): RgbColor? {
            val hex = text.trim().removePrefix("#")
            val expanded = when (hex.length) {
                3 -> buildString { hex.forEach { append(it).append(it) } }
                6 -> hex
                else -> return null
            }
            return expanded.toIntOrNull(16)?.let { RgbColor(it) }
        }
    }
}

/**
 * Immutable, content-compared array of `0xRRGGBB` colors.
 *
 * Avoids boxing [RgbColor] values in lists for LED buffers that can be updated many times
 * per second by the server (`SIGNALUPDATE`).
 */
public class ColorArray private constructor(private val values: IntArray) {
    public val size: Int get() = values.size

    public operator fun get(index: Int): RgbColor = RgbColor(values[index])

    public fun rgbAt(index: Int): Int = values[index]

    public fun isEmpty(): Boolean = values.isEmpty()

    /** Returns a defensive copy. */
    public fun toIntArray(): IntArray = values.copyOf()

    public fun toList(): List<RgbColor> = values.map { RgbColor(it) }

    public fun copyRange(from: Int, to: Int): IntArray = values.copyOfRange(from, to)

    override fun equals(other: Any?): Boolean = other is ColorArray && other.values.contentEquals(values)

    override fun hashCode(): Int = values.contentHashCode()

    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]") { "#%06X".format(it and 0xFFFFFF) }

    public companion object {
        public val EMPTY: ColorArray = ColorArray(IntArray(0))

        /** Wraps a copy of [rgb]. */
        public fun of(rgb: IntArray): ColorArray = if (rgb.isEmpty()) EMPTY else ColorArray(rgb.copyOf())

        public fun of(colors: List<RgbColor>): ColorArray = ColorArray(IntArray(colors.size) { colors[it].rgb })

        /** Takes ownership of [rgb] without copying; caller must not modify it afterwards. */
        internal fun wrap(rgb: IntArray): ColorArray = if (rgb.isEmpty()) EMPTY else ColorArray(rgb)
    }
}
