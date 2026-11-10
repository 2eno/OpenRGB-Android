package dev.eno2.openrgbremote.feature.effects

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.designsystem.component.rgbColor
import dev.eno2.openrgbremote.core.effects.AudioLevels
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.core.effects.EffectRenderer
import dev.eno2.openrgbremote.core.effects.LedLayout
import kotlin.math.sin

@StringRes
fun effectNameRes(effect: EffectId): Int = when (effect) {
    EffectId.RAINBOW_WAVE -> R.string.effect_rainbow_wave
    EffectId.COLOR_CYCLE -> R.string.effect_color_cycle
    EffectId.BREATHING -> R.string.effect_breathing
    EffectId.FIRE -> R.string.effect_fire
    EffectId.AURORA -> R.string.effect_aurora
    EffectId.PULSE -> R.string.effect_pulse
    EffectId.GRADIENT_FLOW -> R.string.effect_gradient_flow
    EffectId.MUSIC -> R.string.effect_music
}

@StringRes
fun effectDescriptionRes(effect: EffectId): Int = when (effect) {
    EffectId.RAINBOW_WAVE -> R.string.effect_rainbow_wave_text
    EffectId.COLOR_CYCLE -> R.string.effect_color_cycle_text
    EffectId.BREATHING -> R.string.effect_breathing_text
    EffectId.FIRE -> R.string.effect_fire_text
    EffectId.AURORA -> R.string.effect_aurora_text
    EffectId.PULSE -> R.string.effect_pulse_text
    EffectId.GRADIENT_FLOW -> R.string.effect_gradient_flow_text
    EffectId.MUSIC -> R.string.effect_music_text
}

/** Disabled in screenshot tests: an endless frame loop would never let the UI become idle. */
val LocalAnimateEffectPreviews = staticCompositionLocalOf { true }

private const val PREVIEW_LEDS = 40
private val previewLayout = LedLayout.linear(PREVIEW_LEDS)

/** Made-up music for the preview of the music effect, so it shows what it would do. */
private fun demoAudio(time: Double): AudioLevels {
    val beat = (0.5 + 0.5 * sin(time * 6.0)).toFloat()
    val bands = FloatArray(AudioLevels.BANDS) { band ->
        val wave = 0.5f + 0.5f * sin(time * (1.3 + band * 0.4) + band).toFloat()
        (wave * (1f - band / 24f) * (0.4f + 0.6f * beat)).coerceIn(0f, 1f)
    }
    return AudioLevels(bands, beat)
}

/**
 * Animated strip rendered with [EffectRenderer], the same code that drives the devices. Animation
 * stops when the system's "remove animations" setting is on; the strip then shows one frame.
 */
@Composable
fun EffectPreview(effect: EffectId, params: EffectParams, shape: Shape, modifier: Modifier = Modifier, startTime: Double = 1.7) {
    val context = LocalContext.current
    val systemAnimations = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val animate = LocalAnimateEffectPreviews.current && systemAnimations
    var time by remember { mutableDoubleStateOf(startTime) }
    val currentParams by rememberUpdatedState(params)
    if (animate) {
        LaunchedEffect(effect) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L) time += (now - last) / 1e9 * currentParams.speed
                    last = now
                }
            }
        }
    }
    val colors = IntArray(PREVIEW_LEDS)
    EffectRenderer.render(effect, time, previewLayout, params, demoAudio(time), colors)
    val description = stringResource(effectNameRes(effect))
    Canvas(
        modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val width = size.width / PREVIEW_LEDS
        for (i in 0 until PREVIEW_LEDS) drawRect(rgbColor(colors[i]), Offset(i * width, 0f), Size(width + 1f, size.height))
    }
}
