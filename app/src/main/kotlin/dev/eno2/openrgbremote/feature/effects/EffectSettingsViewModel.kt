package dev.eno2.openrgbremote.feature.effects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.feature.color.TargetDevice
import dev.eno2.openrgbremote.service.EffectLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class EffectSettingsUiState(
    val config: EffectConfig,
    val running: Boolean = false,
    /** Connected devices that can show app effects (per-LED mode). */
    val devices: List<TargetDevice> = emptyList(),
    val loading: Boolean = true,
) {
    val palette: List<Int> get() = config.params.paletteFor(config.effect)
}

/**
 * Edits one effect. While this effect runs, every change is applied immediately; otherwise the
 * draft is kept until "Start".
 */
@HiltViewModel
class EffectSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeServerOverviews: ObserveServerOverviews,
    private val effects: EffectController,
    private val launcher: EffectLauncher,
) : ViewModel() {

    private val effect: EffectId = savedStateHandle.get<String>("effect")?.let { name -> EffectId.entries.firstOrNull { it.name == name } } ?: EffectId.RAINBOW_WAVE
    private val draft = MutableStateFlow(effects.lastConfig(effect))

    val state: StateFlow<EffectSettingsUiState> = combine(draft, effects.state, observeServerOverviews()) { config, effectState, servers ->
        val capable = servers.flatMap { it.devices }.filter { it.device.ledCount > 0 && it.device.directModeIndex() != null }
        EffectSettingsUiState(
            config = config,
            running = (effectState as? EffectState.Running)?.config?.effect == effect,
            devices = capable.map { remote ->
                val targets = config.targets
                TargetDevice(remote.key, remote.device.label, remote.device.type, targets == null || remote.key in targets, true)
            },
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EffectSettingsUiState(draft.value))

    private fun edit(transform: (EffectConfig) -> EffectConfig) {
        draft.update(transform)
        if ((effects.state.value as? EffectState.Running)?.config?.effect == effect) launcher.start(draft.value)
    }

    private fun params(transform: (EffectParams) -> EffectParams) = edit { it.copy(params = transform(it.params)) }

    fun setSpeed(speed: Float) = params { it.copy(speed = speed.coerceIn(EffectParams.MIN_SPEED, EffectParams.MAX_SPEED)) }

    fun setReverse(reverse: Boolean) = params { it.copy(reverse = reverse) }

    fun setBrightness(brightness: Float) = params { it.copy(brightness = brightness.coerceIn(0f, 1f)) }

    /** Edits the palette; the first change copies the effect's defaults into the custom palette. */
    fun setColor(index: Int, rgb: Int) = params { p ->
        val colors = p.paletteFor(effect).toMutableList()
        if (index in colors.indices) colors[index] = rgb
        p.copy(colors = colors)
    }

    fun addColor() = params { p ->
        val colors = p.paletteFor(effect)
        if (colors.size >= EffectParams.MAX_COLORS) p else p.copy(colors = colors + colors.last())
    }

    fun removeColor(index: Int) = params { p ->
        val colors = p.paletteFor(effect)
        if (colors.size <= 1 || index !in colors.indices) p else p.copy(colors = colors.filterIndexed { i, _ -> i != index })
    }

    fun resetColors() = params { it.copy(colors = emptyList()) }

    fun toggleDevice(key: DeviceKey) = edit { config ->
        val all = state.value.devices.map { it.key }.toSet()
        val current = config.targets ?: all
        val next = if (key in current) current - key else current + key
        config.copy(targets = if (next == all) null else next)
    }

    fun start() = launcher.start(draft.value)

    fun stop() = launcher.stop()
}
