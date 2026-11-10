package dev.eno2.openrgbremote.core.effects

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Small color helpers on `0xRRGGBB` ints, kept allocation-free for the render loop. */
internal object ColorMath {

    fun hsv(hueDegrees: Float, saturation: Float, value: Float): Int {
        val h = ((hueDegrees % 360f) + 360f) % 360f
        val c = value * saturation
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = value - c
        val r: Float
        val g: Float
        val b: Float
        when ((h / 60f).toInt()) {
            0 -> { r = c; g = x; b = 0f }
            1 -> { r = x; g = c; b = 0f }
            2 -> { r = 0f; g = c; b = x }
            3 -> { r = 0f; g = x; b = c }
            4 -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return rgb(r + m, g + m, b + m)
    }

    fun rgb(r: Float, g: Float, b: Float): Int =
        (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

    private fun channel(f: Float): Int = (f * 255f).roundToInt().coerceIn(0, 255)

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun c(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * k).roundToInt()
        }
        return (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    fun scale(rgb: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        if (f >= 1f) return rgb and 0xFFFFFF
        fun c(shift: Int) = (((rgb shr shift) and 0xFF) * f).roundToInt()
        return (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    /** Linear gradient through [palette] for [t] in 0..1. */
    fun gradient(palette: List<Int>, t: Float): Int {
        if (palette.size == 1) return palette[0]
        val scaled = t.coerceIn(0f, 1f) * (palette.size - 1)
        val i = scaled.toInt().coerceAtMost(palette.size - 2)
        return mix(palette[i], palette[i + 1], scaled - i)
    }

    /** Gradient that wraps from the last color back to the first, for [t] of any value. */
    fun loopedGradient(palette: List<Int>, t: Float): Int {
        if (palette.size == 1) return palette[0]
        val position = (t - floor(t)) * palette.size
        val i = position.toInt().coerceIn(0, palette.size - 1)
        return mix(palette[i], palette[(i + 1) % palette.size], position - i)
    }
}

/** Smooth 2D value noise in 0..1; deterministic, so effects without state look organic. */
internal object Noise {
    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    fun at(x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        val a = hash(x0, y0) + (hash(x0 + 1, y0) - hash(x0, y0)) * fx
        val b = hash(x0, y0 + 1) + (hash(x0 + 1, y0 + 1) - hash(x0, y0 + 1)) * fx
        return a + (b - a) * fy
    }
}
