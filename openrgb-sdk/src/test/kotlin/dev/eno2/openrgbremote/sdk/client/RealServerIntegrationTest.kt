package dev.eno2.openrgbremote.sdk.client

import dev.eno2.openrgbremote.sdk.discovery.OpenRgbProbe
import dev.eno2.openrgbremote.sdk.model.DeviceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs against a real OpenRGB server when `OPENRGB_TEST_SERVER=host:port` is set, e.g. the
 * debug-device setup described in `tools/capture_fixtures.py`. Skipped otherwise.
 */
class RealServerIntegrationTest {

    private val target: Pair<String, Int>? = System.getenv("OPENRGB_TEST_SERVER")?.let {
        val (host, port) = it.split(':')
        host to port.toInt()
    }

    @Test
    fun `full session against a real OpenRGB server`() = runBlocking {
        assumeTrue(target != null, "OPENRGB_TEST_SERVER not set")
        val (host, port) = target!!
        withTimeout(30_000) {
            val probe = assertNotNull(OpenRgbProbe().probe(host, port))
            assertTrue(probe.isConfirmed)

            for (maxVersion in listOf(6, 5, 4, 3, 1)) {
                OpenRgbClient(host, port, OpenRgbClientConfig(clientName = "SDK test v$maxVersion", maxProtocolVersion = maxVersion)).use { client ->
                    val info = client.connect()
                    assertEquals(minOf(maxVersion, info.serverProtocolVersion), info.protocolVersion)
                    val devices = client.devices.value
                    assertTrue(devices.isNotEmpty(), "server has no devices")
                    if (info.protocolVersion >= 6) assertNotNull(info.serverName)

                    val target = devices.first { it.ledCount > 0 }
                    target.directModeIndex()?.let { client.updateMode(target.id, it, target.modes[it]) }
                    val colors = IntArray(target.ledCount) { i -> if (i % 2 == 0) 0xFF0000 else 0x0000FF }
                    assertTrue(client.updateLeds(target.id, colors))

                    if (info.protocolVersion >= 6) {
                        // The server echoes the colors back via SIGNALUPDATE.
                        client.devices.first { list -> list.first { it.id == target.id }.colors.toIntArray().contentEquals(colors) }
                        assertTrue(client.awaitLedWindow(target.id, 1, 2_000), "ACK expected")
                    } else {
                        // Re-read the device: the server must have stored our colors.
                        val reloaded = assertNotNull(client.refreshDevice(target.id))
                        assertTrue(reloaded.colors.toIntArray().contentEquals(colors))
                    }
                }
            }

            // Rescan last: OpenRGB 1.0 ignores UpdateLEDs from index-based (protocol <= 5) clients for
            // a moment after a rescan, which would make the per-version checks above flaky.
            OpenRgbClient(host, port, OpenRgbClientConfig(clientName = "SDK test rescan")).use { client ->
                val info = client.connect()
                if (info.protocolVersion >= 5) {
                    val before = client.devices.value.map { it.name }.sorted()
                    assertTrue(client.rescanDevices())
                    client.events.first { it is ClientEvent.DevicesRefreshed }
                    client.devices.first { list -> list.map { it.name }.sorted() == before }
                    if (info.protocolVersion >= 6) assertTrue(client.devices.value.any { it.type == DeviceType.KEYBOARD })
                }
            }
        }
    }
}
