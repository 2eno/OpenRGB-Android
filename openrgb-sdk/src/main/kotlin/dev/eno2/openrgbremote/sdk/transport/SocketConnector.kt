package dev.eno2.openrgbremote.sdk.transport

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens a TCP connection like OpenSSH does: every address the name resolves to is tried in turn,
 * each with the full timeout. `Socket(host, port)` only tries the first one, so a host with an
 * unreachable IPv6 address (stale DNS entry, firewall that only opens IPv4) would fail although
 * its IPv4 address works.
 *
 * Blocking; call it on an IO thread.
 */
public object SocketConnector {

    public fun connect(
        host: String,
        port: Int,
        timeoutMillis: Int,
        resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ): Socket {
        val addresses = resolve(host)
        var firstError: IOException? = null
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(address, port), timeoutMillis)
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                // The first address is the preferred one; its error describes the problem best.
                if (firstError == null) firstError = e else firstError.addSuppressed(e)
            }
        }
        throw firstError ?: IOException("$host has no address")
    }
}
