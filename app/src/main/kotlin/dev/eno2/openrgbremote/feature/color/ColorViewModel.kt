package dev.eno2.openrgbremote.feature.color

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.model.ColorTarget
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.HsvColor
import dev.eno2.openrgbremote.core.domain.model.RemoteDevice
import dev.eno2.openrgbremote.core.domain.repository.EffectController
import dev.eno2.openrgbremote.core.domain.repository.FavoriteColorRepository
import dev.eno2.openrgbremote.core.domain.repository.LightingRepository
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.sdk.model.DeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TargetZone(val index: Int, val label: String, val selected: Boolean)

data class TargetDevice(
    val key: DeviceKey,
    val label: String,
    val type: DeviceType,
    val selected: Boolean,
    /** False for devices that only take a color through a mode change (applied on release). */
    val livePreview: Boolean,
)

enum class ColorMessage { NONE_SELECTED, NOT_REACHED }

data class ColorUiState(
    val loading: Boolean = true,
    val color: HsvColor = HsvColor.WHITE,
    val devices: List<TargetDevice> = emptyList(),
    /** Zones of the single selected device; empty when several devices are selected. */
    val zones: List<TargetZone> = emptyList(),
    /** Set when a single LED is edited; device and zone selection are fixed then. */
    val ledLabel: String? = null,
    val singleDeviceLabel: String? = null,
    val favorites: List<Int> = emptyList(),
    val livePreview: Boolean = true,
    val canUndo: Boolean = false,
    val message: ColorMessage? = null,
) {
    val rgb: Int get() = color.toRgb()
    val isFavorite: Boolean get() = rgb in favorites
}

/**
 * Color control for all devices, selected devices, zones of one device or a single LED.
 * With live preview every change is streamed as a droppable frame and the final value is sent
 * when the finger lifts; without it nothing is sent until [apply].
 */
@HiltViewModel
class ColorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeServerOverviews: ObserveServerOverviews,
    private val lighting: LightingRepository,
    private val favoriteColors: FavoriteColorRepository,
    private val effects: EffectController,
) : ViewModel() {

    private data class Local(
        val color: HsvColor = HsvColor.WHITE,
        /** null = every connected device. */
        val devices: Set<DeviceKey>? = null,
        /** null = every zone of the single selected device. */
        val zones: Set<Int>? = null,
        val livePreview: Boolean = true,
        val canUndo: Boolean = false,
        val message: ColorMessage? = null,
    )

    private val fixedDevice: DeviceKey? = savedStateHandle.get<String>("device")?.let(DeviceKey::decode)
    private val ledIndex: Int = savedStateHandle.get<Int>("led") ?: -1
    private val initialZone: Int = savedStateHandle.get<Int>("zone") ?: -1

    private val local = MutableStateFlow(
        Local(devices = fixedDevice?.let { setOf(it) }, zones = initialZone.takeIf { it >= 0 }?.let { setOf(it) }),
    )
    private val connectedDevices = observeServerOverviews().map { list -> list.flatMap { it.devices } }
    private var latestDevices: List<RemoteDevice> = emptyList()
    private val snapshot = mutableMapOf<DeviceKey, IntArray>()

    val state: StateFlow<ColorUiState> = combine(connectedDevices, local, favoriteColors.observeFavorites()) { devices, l, favorites ->
        latestDevices = devices
        val selected = devices.filter { l.devices == null || it.key in l.devices }
        val single = selected.singleOrNull()
        ColorUiState(
            loading = false,
            color = l.color,
            devices = if (ledIndex >= 0) emptyList() else devices.map {
                TargetDevice(it.key, it.device.label, it.device.type, l.devices == null || it.key in l.devices, it.device.directModeIndex() != null)
            },
            zones = if (ledIndex >= 0 || single == null || single.device.zones.size < 2) {
                emptyList()
            } else {
                single.device.zones.mapIndexed { i, zone -> TargetZone(i, zone.label, l.zones == null || i in l.zones) }
            },
            ledLabel = if (ledIndex >= 0) single?.device?.ledLabel(ledIndex) else null,
            singleDeviceLabel = if (fixedDevice != null) single?.device?.label ?: fixedDevice.name else null,
            favorites = favorites,
            livePreview = l.livePreview,
            canUndo = l.canUndo,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ColorUiState())

    init {
        // Start from the color the target currently shows instead of an arbitrary one.
        viewModelScope.launch {
            val devices = connectedDevices.first { it.isNotEmpty() }
            val device = devices.firstOrNull { fixedDevice == null || it.key == fixedDevice }?.device ?: return@launch
            val index = when {
                ledIndex >= 0 -> ledIndex
                initialZone >= 0 -> device.zones.getOrNull(initialZone)?.startIndex ?: 0
                else -> 0
            }
            val rgb = if (index in 0 until device.colors.size) device.colors.rgbAt(index) else 0
            if (rgb != 0) local.update { it.copy(color = HsvColor.fromRgb(rgb)) }
        }
    }

    fun onColorChange(color: HsvColor) {
        local.update { it.copy(color = color) }
        if (local.value.livePreview) send(preview = true)
    }

    /** The finger lifted: make sure the last value arrives, also on devices without preview. */
    fun onChangeFinished() {
        if (local.value.livePreview) send(preview = false)
    }

    fun onPickColor(rgb: Int) {
        local.update { it.copy(color = HsvColor.fromRgb(rgb)) }
        onChangeFinished()
    }

    fun apply() = send(preview = false)

    fun setLivePreview(enabled: Boolean) = local.update { it.copy(livePreview = enabled) }

    fun toggleDevice(key: DeviceKey) = local.update { l ->
        val all = latestDevices.map { it.key }.toSet()
        val current = l.devices ?: all
        val next = if (key in current) current - key else current + key
        l.copy(devices = if (next == all) null else next, zones = null)
    }

    fun toggleZone(index: Int) = local.update { l ->
        val device = selectedDevices(l).singleOrNull() ?: return@update l
        val all = device.device.zones.indices.toSet()
        val current = l.zones ?: all
        val next = if (index in current) current - index else current + index
        l.copy(zones = if (next == all) null else next)
    }

    fun toggleFavorite() {
        val rgb = local.value.color.toRgb()
        viewModelScope.launch { if (state.value.isFavorite) favoriteColors.remove(rgb) else favoriteColors.add(rgb) }
    }

    /** Restores the colors the devices had before the first change on this screen. */
    fun undo() {
        effects.stop()
        val frames = snapshot.toMap()
        snapshot.clear()
        local.update { it.copy(canUndo = false) }
        viewModelScope.launch { frames.forEach { (key, colors) -> lighting.setFrame(key, colors) } }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun selectedDevices(l: Local): List<RemoteDevice> = latestDevices.filter { l.devices == null || it.key in l.devices }

    private fun targets(l: Local): List<ColorTarget> {
        val selected = selectedDevices(l)
        return selected.mapNotNull { remote ->
            when {
                ledIndex >= 0 -> ColorTarget.Led(remote.key, ledIndex)
                selected.size == 1 && l.zones != null -> if (l.zones.isEmpty()) null else ColorTarget.Zones(remote.key, l.zones)
                else -> ColorTarget.Device(remote.key)
            }
        }
    }

    private fun send(preview: Boolean) {
        val l = local.value
        val targets = targets(l)
        if (targets.isEmpty()) {
            if (!preview) local.update { it.copy(message = ColorMessage.NONE_SELECTED) }
            return
        }
        for (target in targets) {
            val device = latestDevices.firstOrNull { it.key == target.key }?.device ?: continue
            if (device.ledCount > 0 && device.directModeIndex() != null) snapshot.getOrPut(target.key) { device.colors.toIntArray() }
        }
        // A running effect would paint over the chosen color with its next frame.
        effects.stop()
        val rgb = l.color.toRgb()
        viewModelScope.launch {
            val reached = lighting.setColor(targets, rgb, preview)
            if (!preview && reached == 0) {
                local.update { it.copy(message = ColorMessage.NOT_REACHED) }
            } else if (reached > 0 && snapshot.isNotEmpty()) {
                local.update { it.copy(canUndo = true) }
            }
        }
    }
}
