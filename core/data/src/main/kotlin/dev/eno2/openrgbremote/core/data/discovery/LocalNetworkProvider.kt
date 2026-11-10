package dev.eno2.openrgbremote.core.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject

/** IPv4 address and prefix length of the phone in a local network. */
data class LocalIpv4(val address: Inet4Address, val prefixLength: Int)

fun interface LocalNetworkProvider {
    /** The Wi-Fi/Ethernet IPv4 address to derive the scan range from, null if there is none. */
    fun localIpv4(): LocalIpv4?
}

/**
 * Prefers the active network if it is Wi-Fi or Ethernet; with an active VPN the underlying
 * Wi-Fi network is used, because VPN subnets are usually not scannable.
 */
class AndroidLocalNetworkProvider @Inject constructor(
    @ApplicationContext context: Context,
) : LocalNetworkProvider {

    private val connectivity = requireNotNull(context.getSystemService<ConnectivityManager>())

    override fun localIpv4(): LocalIpv4? {
        val active = connectivity.activeNetwork
        @Suppress("DEPRECATION") // getAllNetworks is the only way to see the network beneath a VPN.
        val candidates = listOfNotNull(active) + connectivity.allNetworks.filter { it != active }
        for (network in candidates) {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLan) continue
            connectivity.getLinkProperties(network)?.firstIpv4()?.let { return it }
        }
        return null
    }

    private fun LinkProperties.firstIpv4(): LocalIpv4? = linkAddresses
        .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
        ?.let { LocalIpv4(it.address as Inet4Address, it.prefixLength) }
}
