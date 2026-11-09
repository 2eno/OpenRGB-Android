package io.github.openrgbremote.core.data.discovery

import io.github.openrgbremote.core.domain.model.DiscoveredServer
import io.github.openrgbremote.core.domain.model.ScanUpdate
import io.github.openrgbremote.core.domain.repository.DiscoveryRepository
import io.github.openrgbremote.sdk.discovery.Ipv4Subnet
import io.github.openrgbremote.sdk.discovery.ScanEvent
import io.github.openrgbremote.sdk.discovery.SubnetScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** Scans the subnet of the phone's Wi-Fi/Ethernet address for OpenRGB servers. */
class NetworkScanRepository @Inject constructor(
    private val localNetwork: LocalNetworkProvider,
    private val scanner: SubnetScanner,
) : DiscoveryRepository {

    override fun scan(port: Int): Flow<ScanUpdate> = flow {
        val local = localNetwork.localIpv4()
        if (local == null) {
            emit(ScanUpdate.NoLocalNetwork)
            return@flow
        }
        val subnet = Ipv4Subnet.scanRange(local.address, local.prefixLength)
        val hosts = subnet.hosts().filter { it != local.address }.map { requireNotNull(it.hostAddress) }
        emit(ScanUpdate.Started(subnet.toString(), hosts.size))
        scanner.scan(hosts, port).collect { event ->
            when (event) {
                is ScanEvent.Progress -> emit(ScanUpdate.Progress(event.scanned, event.total))
                is ScanEvent.Found -> emit(
                    ScanUpdate.Found(
                        DiscoveredServer(
                            host = event.result.host,
                            port = event.result.port,
                            protocolVersion = event.result.protocolVersion,
                            serverName = event.result.serverName,
                            serverHostname = event.result.serverHostname,
                        ),
                    ),
                )
            }
        }
    }
}
