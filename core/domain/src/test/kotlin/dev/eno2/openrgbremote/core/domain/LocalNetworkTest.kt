package dev.eno2.openrgbremote.core.domain

import dev.eno2.openrgbremote.core.domain.model.isLocalNetworkAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkTest {
    @Test
    fun `private and link-local addresses need the local network permission`() {
        for (host in listOf("10.0.0.204", "172.16.0.1", "172.31.255.254", "192.168.1.20", "169.254.3.4", "fe80::1%wlan0", "[fd12:3456::1]", "gaming-pc.local", "debianhost", "pc.fritz.box")) {
            assertTrue(isLocalNetworkAddress(host), host)
        }
    }

    @Test
    fun `loopback and public addresses do not`() {
        for (host in listOf("127.0.0.1", "localhost", "::1", "8.8.8.8", "172.32.0.1", "100.101.102.103", "2001:db8::1", "")) {
            assertFalse(isLocalNetworkAddress(host), host)
        }
    }
}
