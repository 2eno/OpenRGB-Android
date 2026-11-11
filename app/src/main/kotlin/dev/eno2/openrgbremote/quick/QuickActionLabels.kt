package dev.eno2.openrgbremote.quick

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.eno2.openrgbremote.R
import dev.eno2.openrgbremote.core.domain.model.ProfileRenderer
import dev.eno2.openrgbremote.core.domain.model.QuickAction
import dev.eno2.openrgbremote.core.domain.usecase.GetProfile
import dev.eno2.openrgbremote.core.effects.AudioLevels
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.effects.EffectRenderer
import dev.eno2.openrgbremote.core.effects.LedLayout
import dev.eno2.openrgbremote.feature.effects.effectNameRes
import dev.eno2.openrgbremote.feature.profiles.presetNameRes
import javax.inject.Inject

/**
 * Name and a few preview colors of an action, for widget, tiles and pickers outside Compose.
 * [effect] (and for dynamic profiles [params]) tell whether the running effect is this action.
 */
data class QuickActionLabel(val name: String, val colors: List<Int>, val effect: EffectId? = null, val params: EffectParams? = null) {
    fun isRunning(running: EffectConfig?): Boolean =
        effect != null && running?.effect == effect && (params == null || running.params == params)
}

class QuickActionLabels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getProfile: GetProfile,
) {
    /** null when the action points to a deleted profile. */
    suspend fun label(action: QuickAction): QuickActionLabel? = when (action) {
        QuickAction.Off -> QuickActionLabel(context.getString(R.string.quick_off), listOf(0x000000))
        is QuickAction.StartEffect -> {
            val colors = IntArray(SAMPLES)
            val params = EffectParams()
            EffectRenderer.render(action.effect, 1.7, LedLayout.linear(SAMPLES), params, AudioLevels(FloatArray(AudioLevels.BANDS) { 0.6f }, 0.6f), colors)
            QuickActionLabel(context.getString(effectNameRes(action.effect)), colors.toList(), effect = action.effect)
        }
        is QuickAction.ApplyProfile -> getProfile(action.profileId)?.let { profile ->
            val name = profile.presetKey?.let { presetNameRes(it) }?.let(context::getString) ?: profile.name
            QuickActionLabel(name, ProfileRenderer.preview(profile, SAMPLES).toList(), profile.animation?.effect, profile.animation?.let { profile.effectParams() })
        }
    }

    private companion object {
        const val SAMPLES = 6
    }
}
