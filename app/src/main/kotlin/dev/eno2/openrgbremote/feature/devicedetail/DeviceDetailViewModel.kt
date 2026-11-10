package dev.eno2.openrgbremote.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.repository.LightingRepository
import dev.eno2.openrgbremote.core.domain.usecase.DeviceWithServer
import dev.eno2.openrgbremote.core.domain.usecase.ObserveDevice
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DetailMessage { MODE_SAVED, DEVICE_UNREACHABLE }

data class DeviceDetailUiState(
    val loading: Boolean = true,
    val device: OpenRgbDevice? = null,
    val serverName: String? = null,
    val status: ConnectionStatus = ConnectionStatus.Offline,
    /** Fallback title while the device is unavailable. */
    val keyName: String = "",
    /** Encoded [DeviceKey] for navigation to the color screen. */
    val encodedKey: String = "",
    val message: DetailMessage? = null,
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeDevice: ObserveDevice,
    private val lighting: LightingRepository,
) : ViewModel() {

    // Argument of DeviceDetailRoute.
    private val encodedKey: String = savedStateHandle.get<String>("key").orEmpty()
    private val key: DeviceKey? = DeviceKey.decode(encodedKey)
    private val message = MutableStateFlow<DetailMessage?>(null)

    val state: StateFlow<DeviceDetailUiState> = combine(
        key?.let { observeDevice(it) } ?: flowOf(DeviceWithServer(null, null, ConnectionStatus.Offline)),
        message,
    ) { found, msg ->
        DeviceDetailUiState(false, found.device?.device, found.server?.name, found.status, key?.name.orEmpty(), encodedKey, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailUiState(keyName = key?.name.orEmpty(), encodedKey = encodedKey))

    /** Activates a mode with its current parameters. */
    fun selectMode(index: Int) {
        val mode = state.value.device?.modes?.getOrNull(index) ?: return
        setMode(index, mode)
    }

    /** Applies changed parameters of the active mode. */
    fun updateActiveMode(mode: DeviceMode) {
        val device = state.value.device ?: return
        setMode(device.activeModeIndex, mode)
    }

    fun saveActiveMode() {
        val k = key ?: return
        val device = state.value.device ?: return
        val mode = device.activeMode ?: return
        viewModelScope.launch {
            message.value = if (lighting.saveMode(k, device.activeModeIndex, mode)) DetailMessage.MODE_SAVED else DetailMessage.DEVICE_UNREACHABLE
        }
    }

    fun messageShown() {
        message.value = null
    }

    private fun setMode(index: Int, mode: DeviceMode) {
        val k = key ?: return
        viewModelScope.launch {
            if (!lighting.setMode(k, index, mode)) message.value = DetailMessage.DEVICE_UNREACHABLE
        }
    }
}
