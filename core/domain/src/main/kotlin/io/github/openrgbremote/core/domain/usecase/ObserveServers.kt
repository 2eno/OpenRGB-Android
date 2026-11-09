package io.github.openrgbremote.core.domain.usecase

import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.model.RemoteDevice
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.model.ServerOverview
import io.github.openrgbremote.core.domain.repository.ConnectionRepository
import io.github.openrgbremote.core.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Saved servers with their live status and devices, sorted by name. */
class ObserveServerOverviews(
    private val servers: ServerRepository,
    private val connections: ConnectionRepository,
) {
    operator fun invoke(): Flow<List<ServerOverview>> =
        combine(servers.observeServers(), connections.statuses, connections.devices) { list, statuses, devices ->
            list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Server::name).thenBy { it.id }).map { server ->
                ServerOverview(
                    server = server,
                    status = statuses[server.id] ?: ConnectionStatus.Offline,
                    devices = devices[server.id].orEmpty().map { RemoteDevice(server.id, it) },
                )
            }
        }.distinctUntilChanged()
}

/** A single device plus its server, or null while it is unavailable. */
data class DeviceWithServer(val device: RemoteDevice?, val server: Server?, val status: ConnectionStatus)

class ObserveDevice(private val overviews: ObserveServerOverviews) {
    operator fun invoke(key: DeviceKey): Flow<DeviceWithServer> = overviews().map { list ->
        val overview = list.firstOrNull { it.server.id == key.serverId }
        DeviceWithServer(
            device = overview?.devices?.firstOrNull { key.matches(it.device) },
            server = overview?.server,
            status = overview?.status ?: ConnectionStatus.Offline,
        )
    }.distinctUntilChanged()
}
