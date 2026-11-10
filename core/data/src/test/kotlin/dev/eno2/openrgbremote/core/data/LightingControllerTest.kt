package dev.eno2.openrgbremote.core.data

import dev.eno2.openrgbremote.core.data.connection.ConnectionManager
import dev.eno2.openrgbremote.core.data.connection.NetworkMonitor
import dev.eno2.openrgbremote.core.data.lighting.LightingController
import dev.eno2.openrgbremote.core.domain.model.ColorTarget
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.DeviceKey
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import dev.eno2.openrgbremote.sdk.client.OpenRgbClientConfig
import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.DeviceType
import dev.eno2.openrgbremote.sdk.model.Led
import dev.eno2.openrgbremote.sdk.model.ModeFlags
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.model.Zone
import dev.eno2.openrgbremote.sdk.model.ZoneType
import dev.eno2.openrgbremote.sdk.protocol.PacketId
import dev.eno2.openrgbremote.sdk.testing.FakeOpenRgbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightingControllerTest {

    private class Servers(val server: Server) : ServerRepository {
        override fun observeServers(): Flow<List<Server>> = MutableStateFlow(listOf(server))
        override suspend fun getServer(id: Long) = server
        override suspend fun findByAddress(host: String, port: Int) = server
        override suspend fun save(server: Server) = server.id
        override suspend fun delete(id: Long) = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fake: FakeOpenRgbServer? = null

    @After
    fun tearDown() {
        scope.cancel()
        fake?.close()
    }

    /** Strip with two zones (3 + 5 LEDs) in "Rainbow" mode, plus a device without per-LED mode. */
    private fun devices(): List<OpenRgbDevice> {
        val strip = FakeOpenRgbServer.device(100, "Strip", 8).copy(
            zones = listOf(
                Zone("Front", ZoneType.LINEAR, 1, 3, 3, 3, startIndex = 0),
                Zone("Back", ZoneType.LINEAR, 1, 5, 5, 5, startIndex = 3),
            ),
            location = "usb1", serial = "A",
        )
        val ram = OpenRgbDevice(
            id = 200, index = 1, type = DeviceType.DRAM, rawType = 1, name = "RAM", location = "smbus", serial = "B",
            modes = listOf(
                DeviceMode("Static", value = 1, flags = ModeFlags.HAS_MODE_SPECIFIC_COLOR, colorsMin = 1, colorsMax = 2, colorMode = 2, colors = ColorArray.of(intArrayOf(0x111111))),
            ),
            zones = listOf(Zone("Stick", ZoneType.LINEAR, 1, 4, 4, 4)),
            leds = List(4) { Led("LED $it") },
            colors = ColorArray.of(IntArray(4)),
        )
        return listOf(strip, ram)
    }

    private val stripKey = DeviceKey(1, "Strip", "usb1", "A")
    private val ramKey = DeviceKey(1, "RAM", "smbus", "B")

    private fun test(version: Int, block: suspend (LightingController, FakeOpenRgbServer) -> Unit) = runBlocking {
        withTimeout(20_000) {
            val server = FakeOpenRgbServer(version, devices()).also { fake = it }
            val network = object : NetworkMonitor { override val isOnline = MutableStateFlow(true) }
            val config = OpenRgbClientConfig(protocolVersionTimeoutMillis = 300, heartbeatIntervalMillis = 0)
            val manager = ConnectionManager(Servers(Server(1, "PC", "127.0.0.1", server.port)), network, scope, config)
            manager.statuses.first { it[1L] is ConnectionStatus.Connected }
            manager.devices.first { it[1L]?.size == 2 }
            block(LightingController(manager, scope), server)
        }
    }

    private suspend fun FakeOpenRgbServer.awaitColors(name: String, expected: List<Int>) {
        repeat(200) {
            if (devices.first { it.name == name }.colors.toIntArray().toList() == expected) return
            delay(25)
        }
        throw AssertionError("$name colors are ${devices.first { it.name == name }.colors}, expected $expected")
    }

    @Test
    fun `whole device color switches to the direct mode first`() = test(6) { lighting, server ->
        assertEquals(1, lighting.setColor(listOf(ColorTarget.Device(stripKey)), 0xFF0000, preview = false))
        server.awaitColors("Strip", List(8) { 0xFF0000 })
        assertEquals(0, server.devices[0].activeModeIndex, "Direct mode active")
        // A second color must not send another mode change.
        lighting.setColor(listOf(ColorTarget.Device(stripKey)), 0x00FF00, preview = true)
        server.awaitColors("Strip", List(8) { 0x00FF00 })
        assertEquals(1, server.packets(PacketId.RGBCONTROLLER_UPDATEMODE).size)
    }

    @Test
    fun `zones and single LEDs keep the other colors`() = test(4) { lighting, server ->
        lighting.setColor(listOf(ColorTarget.Device(stripKey)), 0x000011, preview = false)
        server.awaitColors("Strip", List(8) { 0x000011 })
        lighting.setColor(listOf(ColorTarget.Zones(stripKey, setOf(1))), 0xAA0000, preview = false)
        server.awaitColors("Strip", List(3) { 0x000011 } + List(5) { 0xAA0000 })
        lighting.setColor(listOf(ColorTarget.Led(stripKey, 0)), 0x00BB00, preview = false)
        server.awaitColors("Strip", listOf(0x00BB00, 0x000011, 0x000011) + List(5) { 0xAA0000 })
        assertEquals(0, lighting.setColor(listOf(ColorTarget.Led(stripKey, 99)), 1, preview = false))
    }

    @Test
    fun `devices without per-LED mode get the color through their mode, but never for previews`() = test(5) { lighting, server ->
        assertEquals(0, lighting.setColor(listOf(ColorTarget.Device(ramKey)), 0x123456, preview = true))
        assertTrue(server.packets(PacketId.RGBCONTROLLER_UPDATEMODE).isEmpty())
        assertEquals(1, lighting.setColor(listOf(ColorTarget.Device(ramKey)), 0x123456, preview = false))
        repeat(200) { if (server.devices[1].modes[0].colors.rgbAt(0) == 0x123456) return@test; delay(25) }
        throw AssertionError("mode color not applied")
    }

    @Test
    fun `frames restore a snapshot and modes are forwarded`() = test(6) { lighting, server ->
        val frame = IntArray(8) { it * 0x010101 }
        assertTrue(lighting.setFrame(stripKey, frame))
        server.awaitColors("Strip", frame.toList())
        assertFalse(lighting.setFrame(ramKey, IntArray(4)), "no per-LED mode")

        val rainbow = server.devices[0].modes[1].copy(speed = 9)
        assertTrue(lighting.setMode(stripKey, 1, rainbow))
        repeat(200) { if (server.devices[0].activeModeIndex == 1 && server.devices[0].modes[1].speed == 9L) return@test; delay(25) }
        throw AssertionError("mode not applied")
    }

    @Test
    fun `unknown devices and servers are reported as not reached`() = test(6) { lighting, _ ->
        assertEquals(0, lighting.setColor(listOf(ColorTarget.Device(DeviceKey(1, "Nope", "", ""))), 1, false))
        assertEquals(0, lighting.setColor(listOf(ColorTarget.Device(stripKey.copy(serverId = 7))), 1, false))
        assertFalse(lighting.setMode(DeviceKey(9, "x", "", ""), 0, DeviceMode("x")))
    }
}
