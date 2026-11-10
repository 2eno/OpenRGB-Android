package dev.eno2.openrgbremote.core.domain.model

import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.core.effects.LoopStats

/** An effect with its parameters; [targets] null means every connected device. */
data class EffectConfig(
    val effect: EffectId,
    val params: EffectParams = EffectParams(),
    val targets: Set<DeviceKey>? = null,
)

sealed interface EffectState {
    data object Idle : EffectState

    data class Running(
        val config: EffectConfig,
        val stats: LoopStats = LoopStats(),
        /** Devices currently receiving frames (connected, per-LED capable, selected). */
        val deviceCount: Int = 0,
        /** The music effect runs but the microphone permission is missing. */
        val microphoneMissing: Boolean = false,
    ) : EffectState
}

/** Frame rates offered in the settings. Higher looks smoother but costs network and battery. */
object EffectFrameRates {
    val options = listOf(30, 45, 60)
    const val DEFAULT = 30
}
