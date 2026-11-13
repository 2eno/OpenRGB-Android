package dev.eno2.openrgbremote.sdk.transport

import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SocketConnectorTest {

    @Test
    fun `falls back to the next address like OpenSSH`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            // 127.0.0.2 is loopback too, but nothing listens there: like an unreachable first address.
            val addresses = arrayOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
            SocketConnector.connect("pc", server.localPort, 2_000) { addresses }.use { socket ->
                assertEquals("127.0.0.1", socket.inetAddress.hostAddress)
            }
        }
    }

    @Test
    fun `reports the first address's error when none works`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val addresses = arrayOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1"))
        val error = assertFailsWith<ConnectException> { SocketConnector.connect("pc", port, 2_000) { addresses } }
        assertEquals(1, error.suppressed.size, "the second attempt is kept for logs")
    }
}
