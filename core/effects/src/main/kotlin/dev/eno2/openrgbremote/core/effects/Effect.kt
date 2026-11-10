package dev.eno2.openrgbremote.core.effects

/** The effects the app renders itself and streams to the devices. */
enum class EffectId(
    /** False if the effect ignores [EffectParams.colors] (e.g. the rainbow uses the full spectrum). */
    val usesColors: Boolean,
    /** True if [EffectParams.reverse] changes something visible. */
    val hasDirection: Boolean,
    val defaultColors: List<Int>,
) {
    RAINBOW_WAVE(usesColors = false, hasDirection = true, defaultColors = emptyList()),
    COLOR_CYCLE(usesColors = true, hasDirection = false, defaultColors = listOf(0xFF0000, 0xFFD000, 0x00FF00, 0x00E5FF, 0x0040FF, 0xC000FF)),
    BREATHING(usesColors = true, hasDirection = false, defaultColors = listOf(0x0A84FF, 0xBF5AF2)),
    FIRE(usesColors = true, hasDirection = false, defaultColors = listOf(0x600000, 0xFF2000, 0xFF8C00, 0xFFD24A)),
    AURORA(usesColors = true, hasDirection = true, defaultColors = listOf(0x00FF7F, 0x00C8C8, 0x7A3CFF)),
    PULSE(usesColors = true, hasDirection = true, defaultColors = listOf(0xFF2D55, 0x00C7BE, 0xFFD60A)),
    GRADIENT_FLOW(usesColors = true, hasDirection = true, defaultColors = listOf(0xFF00C8, 0x7A00FF, 0x00E5FF)),
    MUSIC(usesColors = true, hasDirection = false, defaultColors = listOf(0x0040FF, 0x00E5FF, 0x00FF7F, 0xFFD000, 0xFF2000)),
    ;

    val needsMicrophone: Boolean get() = this == MUSIC
}

/** User-adjustable parameters shared by all effects. */
data class EffectParams(
    /** Multiplier of the effect's natural speed, [MIN_SPEED]..[MAX_SPEED]. */
    val speed: Float = 1f,
    val reverse: Boolean = false,
    /** Palette (`0xRRGGBB`); empty means the effect's default colors. */
    val colors: List<Int> = emptyList(),
    /** 0..1. */
    val brightness: Float = 1f,
) {
    fun paletteFor(effect: EffectId): List<Int> = colors.ifEmpty { effect.defaultColors }.ifEmpty { listOf(0xFFFFFF) }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3f
        const val MAX_COLORS = 8
    }
}

/**
 * Loudness per frequency band, all values 0..1. Produced by [AudioAnalyzer], consumed by the
 * music-reactive effect.
 */
class AudioLevels(val bands: FloatArray, val level: Float) {
    val bass: Float get() = average(0, bands.size / 4)
    val mid: Float get() = average(bands.size / 4, bands.size * 5 / 8)
    val treble: Float get() = average(bands.size * 5 / 8, bands.size)

    private fun average(from: Int, to: Int): Float {
        if (to <= from) return 0f
        var sum = 0f
        for (i in from until to) sum += bands[i]
        return sum / (to - from)
    }

    companion object {
        const val BANDS = 16
        val SILENCE = AudioLevels(FloatArray(BANDS), 0f)
    }
}
