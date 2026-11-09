package io.github.openrgbremote.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.ServerOverview
import io.github.openrgbremote.core.domain.repository.ConnectionRepository
import io.github.openrgbremote.core.domain.usecase.ObserveServerOverviews
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevicesUiState(
    val loading: Boolean = true,
    val servers: List<ServerOverview> = emptyList(),
    val refreshing: Boolean = false,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    observeServerOverviews: ObserveServerOverviews,
    private val connections: ConnectionRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<DevicesUiState> = combine(observeServerOverviews(), refreshing) { servers, isRefreshing ->
        DevicesUiState(loading = false, servers = servers, refreshing = isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                // Also retry servers that are currently waiting for their next attempt.
                state.value.servers.forEach { if (it.status !is ConnectionStatus.Connected) connections.retry(it.server.id) }
                connections.refreshAll()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun retry(serverId: Long) = connections.retry(serverId)

    fun connect(serverId: Long) = connections.connect(serverId)

    fun rescanHardware(serverId: Long) {
        viewModelScope.launch { connections.rescanHardware(serverId) }
    }
}
