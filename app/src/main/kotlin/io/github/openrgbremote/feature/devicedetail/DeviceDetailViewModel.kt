package io.github.openrgbremote.feature.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.usecase.ObserveDevice
import io.github.openrgbremote.sdk.model.OpenRgbDevice
import io.github.openrgbremote.ui.navigation.DeviceDetailRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DeviceDetailUiState(
    val loading: Boolean = true,
    val device: OpenRgbDevice? = null,
    val serverName: String? = null,
    val status: ConnectionStatus = ConnectionStatus.Offline,
    /** Fallback title while the device is unavailable. */
    val keyName: String = "",
)

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeDevice: ObserveDevice,
) : ViewModel() {

    private val key: DeviceKey? = DeviceKey.decode(savedStateHandle.toRoute<DeviceDetailRoute>().key)

    val state: StateFlow<DeviceDetailUiState> = (
        key?.let { k ->
            observeDevice(k).map { DeviceDetailUiState(false, it.device?.device, it.server?.name, it.status, k.name) }
        } ?: flowOf(DeviceDetailUiState(loading = false))
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailUiState(keyName = key?.name.orEmpty()))
}
