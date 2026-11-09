package io.github.openrgbremote.sdk.discovery

import io.github.openrgbremote.sdk.client.FakeOpenRgbServer
import io.github.openrgbremote.sdk.client.GarbageServer
import io.github.openrgbremote.sdk.client.closedPort
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryTest {

    @Test
    fun `subnet host enumeration`() {
        val net = Ipv4Subnet.of(Ipv4Subnet.parseIpv4("192.168.1.77")!!, 24)
        assertEquals("192.168.1.0/24", net.toString())
        val hosts = net.hosts()
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first().hostAddress)
        assertEquals("192.168.1.254", hosts.last().hostAddress)
        assertTrue(Ipv4Subnet.parseIpv4("192.168.1.200")!! in net)
        assertFalse(Ipv4Subnet.parseIpv4("192.168.2.1")!! in net)
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), Ipv4Subnet.parse("10.0.0.0/30")!!.hosts().map { it.hostAddress })
        assertEquals(2, Ipv4Subnet.parse("10.0.0.0/31")!!.hosts().size)
    }

    @Test
    fun `large networks are reduced to the surrounding 24`() {
        val address = Ipv4Subnet.parseIpv4("10.20.30.40")!!
        assertEquals("10.20.30.0/24", Ipv4Subnet.scanRange(address, 16).toString())
        assertEquals("10.20.28.0/22", Ipv4Subnet.scanRange(address, 22).toString())
    }

    @Test
    fun `ipv4 parser rejects host names and malformed input`() {
        assertNull(Ipv4Subnet.parseIpv4("my-pc.local"))
        assertNull(Ipv4Subnet.parseIpv4("256.1.1.1"))
        assertNull(Ipv4Subnet.parseIpv4("1.2.3"))
        assertNull(Ipv4Subnet.parseIpv4("01.2.3.4"))
        assertNull(Ipv4Subnet.parse("10.0.0.0/33"))
    }

    @Test
    fun `probe identifies protocol 6 servers including host name`() = runBlocking {
        FakeOpenRgbServer(6).use { server ->
            val result = assertNotNull(OpenRgbProbe().probe("127.0.0.1", server.port))
            assertTrue(result.isConfirmed)
            assertEquals(6, result.protocolVersion)
            assertEquals("Fake OpenRGB", result.serverName)
            assertEquals("fakehost", result.serverHostname)
        }
    }

    @Test
    fun `probe reports protocol 4 servers without names`() = runBlocking {
        FakeOpenRgbServer(4).use { server ->
            val result = assertNotNull(OpenRgbProbe().probe("127.0.0.1", server.port))
            assertEquals(4, result.protocolVersion)
            assertNull(result.serverName)
        }
    }

    @Test
    fun `silent protocol 0 server is reported as unconfirmed`() = runBlocking {
        FakeOpenRgbServer(0).use { server ->
            val result = assertNotNull(OpenRgbProbe().probe("127.0.0.1", server.port, replyTimeoutMillis = 300))
            assertFalse(result.isConfirmed)
        }
    }

    @Test
    fun `closed ports and other services are not reported`() = runBlocking {
        assertNull(OpenRgbProbe().probe("127.0.0.1", closedPort()))
        GarbageServer().use { assertNull(OpenRgbProbe().probe("127.0.0.1", it.port)) }
    }

    @Test
    fun `scanner reports progress and findings`() = runBlocking {
        withTimeout(20_000) {
            FakeOpenRgbServer(6).use { server ->
                // Only 127.0.0.1 is bound; other loopback addresses refuse the connection.
                val hosts = listOf("127.0.0.1", "127.0.0.2", "127.0.0.3")
                val events = SubnetScanner().scan(hosts, server.port).toList()
                val found = events.filterIsInstance<ScanEvent.Found>().map { it.result.host }
                assertEquals(listOf("127.0.0.1"), found)
                assertEquals(ScanEvent.Progress(3, 3), events.filterIsInstance<ScanEvent.Progress>().maxBy { it.scanned })
            }
        }
    }
}
