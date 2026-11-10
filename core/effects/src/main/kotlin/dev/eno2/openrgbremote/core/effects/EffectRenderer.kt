package dev.eno2.openrgbremote.core.effects

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Renders one frame of an effect into [out] (one `0xRRGGBB` per LED of [layout]).
 *
 * [time] is *effect time* in seconds: the loop advances it by `realDelta * speed`, so changing the
 * speed while an effect runs continues smoothly instead of jumping. Renderers are pure functions;
 * the same call always produces the same colors, which keeps previews and devices in sync.
 */
object EffectRenderer {

    fun render(effect: EffectId, time: Double, layout: LedLayout, params: EffectParams, audio: AudioLevels, out: IntArray) {
        require(out.size == layout.size) { "Output has ${out.size} entries for ${layout.size} LEDs" }
        val palette = params.paletteFor(effect)
        val t = time.toFloat()
        val direction = if (params.reverse) -1f else 1f
        when (effect) {
            EffectId.RAINBOW_WAVE -> for (i in out.indices) {
                // Moves towards +x by default: hue(x - v*t).
                out[i] = ColorMath.hsv(360f * (layout.x[i] - direction * 0.25f * t), 1f, 1f)
            }
            EffectId.COLOR_CYCLE -> out.fill(ColorMath.loopedGradient(palette, t * 0.1f))
            EffectId.BREATHING -> {
                val period = 4f
                val phase = (t / period) - floor(t / period)
                // Raised cosine, squared: long dark pause, soft peak, like a breathing LED.
                val level = (0.5f - 0.5f * cos(2f * PI.toFloat() * phase)).pow(2)
                val color = palette[(floor(t / period).toInt()).mod(palette.size)]
                out.fill(ColorMath.scale(color, level))
            }
            EffectId.FIRE -> {
                val heatPalette = listOf(0x000000) + palette
                for (i in out.indices) {
                    val x = layout.x[i]
                    var heat = Noise.at(x * 4f, t * 1.6f) * 0.65f + Noise.at(x * 11f + 17f, t * 3.2f) * 0.35f
                    // Matrix zones burn from the bottom row (y = 1) upwards.
                    if (layout.isMatrix[i]) heat *= 0.25f + 0.85f * layout.y[i]
                    out[i] = ColorMath.gradient(heatPalette, (heat * 1.25f - 0.1f).coerceIn(0f, 1f))
                }
            }
            EffectId.AURORA -> for (i in out.indices) {
                val x = layout.x[i]
                val y = layout.y[i]
                val hue = Noise.at(x * 1.8f + direction * t * 0.12f, y * 1.4f + t * 0.04f)
                val glow = Noise.at(x * 3.1f - direction * t * 0.2f, y * 2f + 7.3f)
                val brightness = 0.2f + 0.8f * smoothstep(0.2f, 0.75f, glow)
                out[i] = ColorMath.scale(ColorMath.gradient(palette, hue), brightness)
            }
            EffectId.PULSE -> {
                val cycle = t * 0.5f
                val phase = cycle - floor(cycle)
                val radius = if (params.reverse) 1f - phase else phase
                val color = palette[floor(cycle).toInt().mod(palette.size)]
                for (i in out.indices) {
                    val distance = if (layout.isMatrix[i]) {
                        hypot(layout.x[i] - 0.5f, layout.y[i] - 0.5f) / (sqrt(0.5f))
                    } else {
                        abs(layout.x[i] - 0.5f) * 2f
                    }
                    val intensity = (1f - abs(distance - radius) / 0.18f).coerceIn(0f, 1f)
                    out[i] = ColorMath.scale(color, intensity * intensity)
                }
            }
            EffectId.GRADIENT_FLOW -> for (i in out.indices) {
                out[i] = ColorMath.loopedGradient(palette, layout.x[i] - direction * t * 0.15f)
            }
            EffectId.MUSIC -> renderMusic(layout, palette, audio, out)
        }
        if (params.brightness < 1f) for (i in out.indices) out[i] = ColorMath.scale(out[i], params.brightness)
    }

    /**
     * Spectrum: x selects the frequency band (bass left, treble right). Matrix zones become a bar
     * graph (rows light up from the bottom), linear zones show the band's loudness as brightness.
     * A beat on the bass adds a short glow everywhere.
     */
    private fun renderMusic(layout: LedLayout, palette: List<Int>, audio: AudioLevels, out: IntArray) {
        val bands = audio.bands
        val boost = audio.bass * 0.25f
        for (i in out.indices) {
            val position = layout.x[i]
            val band = (position * (bands.size - 1) + 0.5f).toInt().coerceIn(0, bands.size - 1)
            val level = bands[band]
            val color = ColorMath.gradient(palette, position)
            val brightness = if (layout.isMatrix[i]) {
                // y = 1 is the bottom row; a row lights up once the level reaches its height.
                val height = 1f - layout.y[i]
                if (level >= height - 0.001f) 1f else boost
            } else {
                0.04f + 0.96f * (level + boost).coerceAtMost(1f)
            }
            out[i] = ColorMath.scale(color, brightness)
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
