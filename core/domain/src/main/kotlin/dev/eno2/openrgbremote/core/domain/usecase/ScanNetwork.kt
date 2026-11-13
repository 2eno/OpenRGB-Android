package dev.eno2.openrgbremote.core.domain.usecase

import dev.eno2.openrgbremote.core.domain.model.DiscoveredServer
import dev.eno2.openrgbremote.core.domain.model.ScanUpdate
import dev.eno2.openrgbremote.core.domain.repository.DiscoveryRepository
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

data class ScanResultItem(val server: DiscoveredServer, val alreadySaved: Boolean)

data class ScanState(
    val running: Boolean = true,
    val network: String? = null,
    val scanned: Int = 0,
    val total: Int = 0,
    val found: List<ScanResultItem> = emptyList(),
    val noLocalNetwork: Boolean = false,
    /** The scan waits for the local network permission (Android 17+). */
    val permissionMissing: Boolean = false,
) {
    val progress: Float get() = if (total == 0) 0f else scanned.toFloat() / total
}

/**
 * Scans the local network and accumulates the results into a [ScanState]. Confirmed servers come
 * first, sorted by address; servers that are already saved are marked.
 */
class ScanNetwork(
    private val discovery: DiscoveryRepository,
    private val servers: ServerRepository,
) {
    operator fun invoke(port: Int = ProtocolConstants.DEFAULT_PORT): Flow<ScanState> {
        val raw = flow {
            var state = ScanState()
            emit(state)
            discovery.scan(port).collect { update ->
                state = reduce(state, update)
                emit(state)
            }
            emit(state.copy(running = false))
        }
        return combine(raw, servers.observeServers()) { state, saved ->
            val addresses = saved.map { it.host to it.port }.toSet()
            state.copy(found = state.found.map { it.copy(alreadySaved = (it.server.host to it.server.port) in addresses) })
        }
    }

    companion object {
        fun reduce(state: ScanState, update: ScanUpdate): ScanState = when (update) {
            is ScanUpdate.Started -> state.copy(network = update.network, total = update.total)
            is ScanUpdate.Progress -> state.copy(scanned = update.scanned, total = update.total)
            is ScanUpdate.NoLocalNetwork -> state.copy(running = false, noLocalNetwork = true)
            is ScanUpdate.Found -> state.copy(
                found = (state.found.filterNot { it.server.host == update.server.host } + ScanResultItem(update.server, false))
                    .sortedWith(compareBy<ScanResultItem> { !it.server.isConfirmed }.thenBy { ipSortKey(it.server.host) }),
            )
        }

        private fun ipSortKey(host: String): String =
            host.split('.').joinToString(".") { part -> part.padStart(3, '0') }
    }
}
