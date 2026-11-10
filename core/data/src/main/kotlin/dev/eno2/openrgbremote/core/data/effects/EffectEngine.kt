package dev.eno2.openrgbremote.core.data.effects

import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.EffectConfig
import dev.eno2.openrgbremote.core.domain.model.EffectFrameRates
import dev.eno2.openrgbremote.core.domain.model.EffectState
import dev.eno2.openrgbremote.core.domain.model.RemoteDevice
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.repository.LightingRepository
import dev.eno2.openrgbremote.core.effects.AudioLevels
import dev.eno2.openrgbremote.core.effects.EffectId
import dev.eno2.openrgbremote.core.effects.EffectLoop
import dev.eno2.openrgbremote.core.effects.EffectParams
import dev.eno2.openrgbremote.core.effects.EffectSpec
import dev.eno2.openrgbremote.core.effects.LedLayout
import dev.eno2.openrgbremote.core.effects.RenderTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/** Microphone levels for the music effect. */
interface AudioLevelSource {
    /** False while the microphone permission is missing. */
    val isAvailable: Boolean

    /** Cold flow: collecting it records, cancelling stops the microphone. */
    fun levels(): Flow<AudioLevels>
}

/**
 * Runs the [EffectLoop] against all connected devices.
 *
 * - Frames go through [LightingRepository.setFrame], i.e. the conflating per-device streamer.
 * - While an effect runs, [keepAlive] keeps the connections open even if the app is in the
 *   background (the foreground service keeps the process alive).
 * - Device colors change with every frame, so targets are rebuilt only when the *structure*
 *   changes (devices appear or disappear, a zone is resized), not on each echoed frame.
 * - Starting while an effect runs updates the running loop: switching effects or moving a slider
 *   continues the animation instead of restarting it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EffectEngine(
    private val devices: Flow<List<RemoteDevice>>,
    private val lighting: LightingRepository,
    private val keepAlive: () -> Closeable,
    private val targetFps: Flow<Int>,
    private val audio: AudioLevelSource,
    private val scope: CoroutineScope,
    private val loop: EffectLoop<DeviceKey> = EffectLoop(),
) : EffectController {

    private val _state = MutableStateFlow<EffectState>(EffectState.Idle)
    override val state: StateFlow<EffectState> = _state.asStateFlow()

    private val spec = MutableStateFlow(EffectSpec(EffectId.RAINBOW_WAVE, EffectParams()))
    private val selection = MutableStateFlow<Set<DeviceKey>?>(null)
    private val config = MutableStateFlow<EffectConfig?>(null)
    private val remembered = ConcurrentHashMap<EffectId, EffectConfig>()
    private var job: Job? = null
    private var keepAliveHandle: Closeable? = null

    @Synchronized
    override fun start(config: EffectConfig) {
        remembered[config.effect] = config
        spec.value = EffectSpec(config.effect, config.params)
        selection.value = config.targets
        this.config.value = config
        if (job?.isActive == true) return
        keepAliveHandle = keepAlive()
        _state.value = EffectState.Running(config)
        job = scope.launch { runEffect() }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        keepAliveHandle?.close()
        keepAliveHandle = null
        config.value = null
        _state.value = EffectState.Idle
    }

    override fun lastConfig(effect: EffectId): EffectConfig = remembered[effect] ?: EffectConfig(effect)

    private suspend fun runEffect() = coroutineScope {
        val targets: StateFlow<List<RenderTarget<DeviceKey>>> = combine(devices, selection) { list, selected ->
            list.filter { remote ->
                remote.device.ledCount > 0 && remote.device.directModeIndex() != null && (selected == null || remote.key in selected)
            }
        }
            .distinctUntilChangedBy { list -> list.map { Triple(it.key, it.device.ledCount, it.device.zones.map { z -> z.ledsCount }) } }
            .map { list -> list.map { RenderTarget(it.key, LedLayout.of(it.device)) } }
            .stateIn(this, SharingStarted.Eagerly, emptyList())
        val fps = targetFps
            .map { it.coerceIn(EffectLoop.MIN_FPS, EffectLoop.MAX_FPS) }
            .stateIn(this, SharingStarted.Eagerly, EffectFrameRates.DEFAULT)
        val audioLevels = MutableStateFlow(AudioLevels.SILENCE)

        launch {
            spec.map { it.effect.needsMicrophone }.distinctUntilChanged().collectLatest { needed ->
                audioLevels.value = AudioLevels.SILENCE
                if (needed && audio.isAvailable) audio.levels().collect { audioLevels.value = it }
            }
        }
        launch {
            combine(config, loop.stats, targets) { current, stats, list ->
                current?.let {
                    EffectState.Running(it, stats, list.size, microphoneMissing = it.effect.needsMicrophone && !audio.isAvailable)
                }
            }.collect { running -> if (running != null) _state.value = running }
        }
        loop.run(spec, targets, fps, audioLevels) { key, colors -> lighting.setFrame(key, colors) }
    }
}
