package dev.eno2.openrgbremote.feature.effects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.service.EffectLauncher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class EffectCard(val effect: EffectId, val params: EffectParams, val running: Boolean)

data class EffectsUiState(
    val running: EffectState.Running? = null,
    val cards: List<EffectCard> = EffectId.entries.map { EffectCard(it, EffectParams(), false) },
)

@HiltViewModel
class EffectsViewModel @Inject constructor(
    private val effects: EffectController,
    private val launcher: EffectLauncher,
) : ViewModel() {

    val state: StateFlow<EffectsUiState> = effects.state.map { state ->
        val running = state as? EffectState.Running
        EffectsUiState(
            running = running,
            // Previews use the last parameters of each effect, so cards look like what starts.
            cards = EffectId.entries.map { EffectCard(it, effects.lastConfig(it).params, running?.config?.effect == it) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EffectsUiState())

    fun stop() = launcher.stop()
}
