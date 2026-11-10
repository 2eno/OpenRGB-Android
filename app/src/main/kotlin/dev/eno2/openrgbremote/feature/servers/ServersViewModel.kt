package dev.eno2.openrgbremote.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.model.ServerOverview
import dev.eno2.openrgbremote.core.domain.repository.ConnectionRepository
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    observeServerOverviews: ObserveServerOverviews,
    private val servers: ServerRepository,
    private val connections: ConnectionRepository,
) : ViewModel() {

    val state: StateFlow<List<ServerOverview>?> = observeServerOverviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAutoConnect(server: Server, enabled: Boolean) {
        viewModelScope.launch {
            servers.save(server.copy(autoConnect = enabled))
            if (enabled) connections.connect(server.id) else connections.disconnect(server.id)
        }
    }

    fun connect(id: Long) = connections.connect(id)

    fun disconnect(id: Long) = connections.disconnect(id)

    fun delete(id: Long) {
        viewModelScope.launch { servers.delete(id) }
    }
}
