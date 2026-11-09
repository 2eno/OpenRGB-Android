package io.github.openrgbremote.sdk.client

import io.github.openrgbremote.sdk.protocol.DeviceCodec
import io.github.openrgbremote.sdk.protocol.LittleEndianReader
import io.github.openrgbremote.sdk.protocol.PacketId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Client tests over real TCP sockets against [FakeOpenRgbServer]. */
class OpenRgbClientTest {

    private val resources = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> T.closeLater(): T = also { resources += it }

    @AfterEach
    fun tearDown() = resources.reversed().forEach { runCatching { it.close() } }

    private val fastConfig = OpenRgbClientConfig(
        clientName = "Test Client",
        clientHostname = "test-phone",
        protocolVersionTimeoutMillis = 300,
        requestTimeoutMillis = 2_000,
        heartbeatIntervalMillis = 0,
        deviceListDebounceMillis = 20,
    )

    private fun server(version: Int, ackDelay: Long = 0, versionDelay: Long = 0) =
        FakeOpenRgbServer(version, ackDelayMillis = ackDelay, versionReplyDelayMillis = versionDelay).closeLater()

    private fun client(port: Int, config: OpenRgbClientConfig = fastConfig) =
        OpenRgbClient("127.0.0.1", port, config).closeLater()

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun eventually(message: String, condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(25)
        }
        throw AssertionError("Condition not met: $message")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
    fun `handshake and device list for every protocol version`(version: Int) = test {
        val server = server(version)
        val client = client(server.port)
        val info = client.connect()

        assertEquals(version, info.protocolVersion)
        assertEquals(version, client.protocolVersion)
        val devices = client.devices.value
        assertEquals(listOf("Fake Strip", "Fake Keyboard"), devices.map { it.name })
        assertEquals(if (version >= 6) listOf(100, 200) else listOf(0, 1), devices.map { it.id })
        assertEquals(if (version >= 1) "Fake" else "", devices[0].vendor)
        eventually("client name received") { server.clientNames == listOf("Test Client") }

        // Controller data requests carry the negotiated version, except for protocol 0.
        val dataRequests = server.packets(PacketId.REQUEST_CONTROLLER_DATA)
        assertTrue(dataRequests.all { it.payload.size == if (version == 0) 0 else 4 })
        if (version in 1..5) assertTrue(dataRequests.all { LittleEndianReader(it.payload).i32() == version })

        if (version >= 6) {
            eventually("server info") { client.serverInfo.value?.serverHostname == "fakehost" }
            assertEquals("Fake OpenRGB", client.serverInfo.value?.serverName)
            assertEquals(1, client.serverInfo.value?.serverFlags)
        } else {
            assertNull(client.serverInfo.value?.serverName)
            assertTrue(server.packets(PacketId.SET_CLIENT_FLAGS).isEmpty(), "flags are protocol 6 only")
        }
    }

    @Test
    fun `client offering an older version is served in that version`() = test {
        val server = server(6)
        val client = client(server.port, fastConfig.copy(maxProtocolVersion = 4))
        val info = client.connect()
        assertEquals(6, info.serverProtocolVersion)
        assertEquals(4, info.protocolVersion)
        assertEquals(listOf(0, 1), client.devices.value.map { it.id })
        assertFalse(client.supportsAcks)
    }

    @Test
    fun `UpdateLEDs fits colors to the LED count`() = test {
        val server = server(4)
        val client = client(server.port)
        client.connect()

        assertTrue(client.updateLeds(0, IntArray(20) { 0x010203 }))
        assertTrue(client.updateLeds(0, intArrayOf(0xFF0000)))
        eventually("two frames") { server.packets(PacketId.RGBCONTROLLER_UPDATELEDS).size == 2 }

        val (long, short) = server.packets(PacketId.RGBCONTROLLER_UPDATELEDS)
        val longColors = DeviceCodec.decodeColors(LittleEndianReader(long.payload, 4))
        assertEquals(8, longColors.size, "surplus colors must never reach old servers")
        val shortColors = DeviceCodec.decodeColors(LittleEndianReader(short.payload, 4))
        assertEquals(8, shortColors.size)
        assertEquals(0xFF0000, shortColors.rgbAt(0))
        assertEquals(0x010203, shortColors.rgbAt(1), "missing entries keep the current color")
        assertEquals(0xFF0000, client.devices.value[0].colors.rgbAt(0), "protocol < 6 updates local state")
        assertFalse(client.updateLeds(42, intArrayOf(1)), "unknown device")
    }

    @Test
    fun `protocol 6 colors are taken from the SIGNALUPDATE echo`() = test {
        val server = server(6)
        val observer = client(server.port)
        val sender = client(server.port)
        observer.connect()
        sender.connect()

        sender.updateLeds(200, intArrayOf(0x112233, 0x445566, 0x778899, 0xAABBCC))
        val updated = withTimeout(5_000) {
            observer.devices.first { list -> list.firstOrNull { it.id == 200 }?.colors?.rgbAt(0) == 0x112233 }
        }
        assertEquals(0xAABBCC, updated.first { it.id == 200 }.colors.rgbAt(3))
    }

    @Test
    fun `protocol 6 ACKs open the LED window`() = test {
        val server = server(6, ackDelay = 150)
        val client = client(server.port)
        client.connect()
        assertTrue(client.supportsAcks)
        client.updateLeds(100, IntArray(8))
        client.updateLeds(100, IntArray(8))
        // Two frames in flight: the window (max 2) is closed until the first ACK arrives.
        val started = System.nanoTime()
        assertTrue(client.awaitLedWindow(100, maxInFlight = 2, timeoutMillis = 3_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 50, "window must wait for an ACK")
    }

    @Test
    fun `ACK window times out and resets instead of blocking forever`() = test {
        val server = server(6)
        val client = client(server.port)
        client.connect()
        server.unresponsive = true
        client.updateLeds(100, IntArray(8))
        assertFalse(client.awaitLedWindow(100, maxInFlight = 1, timeoutMillis = 200))
        assertTrue(client.awaitLedWindow(100, maxInFlight = 1, timeoutMillis = 200), "window was reset")
    }

    @Test
    fun `protocol below 6 reloads everything after DEVICE_LIST_UPDATED`() = test {
        val server = server(5)
        val client = client(server.port)
        client.connect()
        val requestsBefore = server.packets(PacketId.REQUEST_CONTROLLER_DATA).size

        server.devices = server.devices + FakeOpenRgbServer.device(300, "Hot-plugged", 2)
        server.broadcastDeviceListUpdated()

        eventually("three devices") { client.devices.value.size == 3 }
        assertEquals("Hot-plugged", client.devices.value[2].name)
        assertEquals(requestsBefore + 3, server.packets(PacketId.REQUEST_CONTROLLER_DATA).size, "indices are reloaded completely")
    }

    @Test
    fun `protocol 6 only fetches new unique ids after DEVICE_LIST_UPDATED`() = test {
        val server = server(6)
        val client = client(server.port)
        client.connect()
        val requestsBefore = server.packets(PacketId.REQUEST_CONTROLLER_DATA).size

        server.devices = listOf(server.devices[1], FakeOpenRgbServer.device(300, "Hot-plugged", 2))
        server.broadcastDeviceListUpdated()

        eventually("new list") { client.devices.value.map { it.id } == listOf(200, 300) }
        assertEquals(requestsBefore + 1, server.packets(PacketId.REQUEST_CONTROLLER_DATA).size)
        assertEquals(listOf(0, 1), client.devices.value.map { it.index })
    }

    @Test
    fun `server going away ends the client with CONNECTION_LOST`() = test {
        val server = server(6)
        val client = client(server.port)
        client.connect()
        server.dropAllConnections()
        val error = client.awaitClosed()
        assertEquals(ConnectionErrorReason.CONNECTION_LOST, error?.reason)
        assertFalse(client.isConnected)
        assertFailsWith<OpenRgbException> { client.updateLeds(100, IntArray(8)) }
    }

    @Test
    fun `heartbeat detects a server that stopped answering`() = test {
        val server = server(6)
        val client = client(server.port, fastConfig.copy(heartbeatIntervalMillis = 100, requestTimeoutMillis = 300))
        client.connect()
        server.unresponsive = true
        assertEquals(ConnectionErrorReason.CONNECTION_LOST, client.awaitClosed()?.reason)
    }

    @Test
    fun `closed port is reported as CONNECTION_REFUSED`() = test {
        val e = assertFailsWith<OpenRgbException> { client(closedPort()).connect() }
        assertEquals(ConnectionErrorReason.CONNECTION_REFUSED, e.error.reason)
    }

    @Test
    fun `a different service on the port is reported as NOT_OPENRGB`() = test {
        val garbage = GarbageServer().closeLater()
        val e = assertFailsWith<OpenRgbException> { client(garbage.port).connect() }
        assertEquals(ConnectionErrorReason.NOT_OPENRGB, e.error.reason)
    }

    @Test
    fun `unknown host is classified`() = test {
        val e = assertFailsWith<OpenRgbException> { OpenRgbClient("does-not-exist.invalid", 6742, fastConfig).closeLater().connect() }
        assertEquals(ConnectionErrorReason.UNKNOWN_HOST, e.error.reason)
    }

    @Test
    fun `late protocol version reply restarts the connection instead of guessing`() = test {
        val server = server(6, versionDelay = 600)
        val client = client(server.port)
        // The reply arrives after the 300 ms timeout: the client first assumes protocol 0 ...
        runCatching { client.connect() }
        // ... and must then give up once the late reply shows up.
        assertEquals(ConnectionErrorReason.PROTOCOL_ERROR, client.awaitClosed()?.reason)
    }

    @Test
    fun `SetCustomMode reloads the device to learn the new active mode`() = test {
        val server = server(3)
        val client = client(server.port)
        client.connect()
        val before = server.packets(PacketId.REQUEST_CONTROLLER_DATA).size
        assertTrue(client.setCustomMode(0))
        assertEquals(before + 1, server.packets(PacketId.REQUEST_CONTROLLER_DATA).size)
    }

    @Test
    fun `UpdateMode sends the mode and updates local state`() = test {
        val server = server(3)
        val client = client(server.port)
        client.connect()
        val rainbow = client.devices.value[0].modes[1].copy(speed = 9)
        assertTrue(client.updateMode(0, 1, rainbow))
        eventually("mode packet") { server.packets(PacketId.RGBCONTROLLER_UPDATEMODE).isNotEmpty() }
        val payload = server.packets(PacketId.RGBCONTROLLER_UPDATEMODE).single().payload
        assertEquals(1, LittleEndianReader(payload, 4).i32())
        assertEquals(rainbow, DeviceCodec.decodeMode(LittleEndianReader(payload, 8), 3))
        assertEquals(9L, client.devices.value[0].modes[1].speed)
        assertFalse(client.saveMode(0, 5, rainbow), "invalid index")
    }

    @Test
    fun `features missing in old protocols are refused locally`() = test {
        val server = server(2)
        val client = client(server.port)
        client.connect()
        val mode = client.devices.value[0].modes[0]
        assertFalse(client.saveMode(0, 0, mode), "SaveMode needs protocol 3")
        assertFalse(client.rescanDevices(), "rescan needs protocol 5")
        assertFalse(client.updateZoneMode(0, 0, -1, null), "zone modes need protocol 6")
        assertTrue(server.packets(PacketId.RGBCONTROLLER_SAVEMODE).isEmpty())
    }

    @Test
    fun `resizeZone validates bounds`() = test {
        val server = server(4)
        val client = client(server.port)
        client.connect()
        assertFalse(client.resizeZone(0, 0, 9), "above leds_max")
        assertTrue(client.resizeZone(0, 0, 4))
        eventually("resize packet") { server.packets(PacketId.RGBCONTROLLER_RESIZEZONE).size == 1 }
    }

    @Test
    fun `connect can only be called once`() = test {
        val server = server(6)
        val client = client(server.port)
        client.connect()
        assertFailsWith<IllegalStateException> { client.connect() }
    }
}
