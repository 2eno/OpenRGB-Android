package io.github.openrgbremote.core.domain

import io.github.openrgbremote.core.domain.testing.FakeConnectionRepository
import io.github.openrgbremote.core.domain.testing.FakeDiscoveryRepository
import io.github.openrgbremote.core.domain.testing.FakeServerRepository

import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.DeviceKey
import io.github.openrgbremote.core.domain.model.DiscoveredServer
import io.github.openrgbremote.core.domain.model.ScanUpdate
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.usecase.ObserveDevice
import io.github.openrgbremote.core.domain.usecase.ObserveServerOverviews
import io.github.openrgbremote.core.domain.usecase.SaveServer
import io.github.openrgbremote.core.domain.usecase.SaveServerResult
import io.github.openrgbremote.core.domain.usecase.ScanNetwork
import io.github.openrgbremote.sdk.model.DeviceType
import io.github.openrgbremote.sdk.model.OpenRgbDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UseCaseTest {

    private fun device(name: String, location: String = "HID: /dev/hidraw0") =
        OpenRgbDevice(id = 3, index = 0, type = DeviceType.KEYBOARD, rawType = 5, name = name, location = location, serial = "S1")

    @Test
    fun `save creates and updates servers and keeps the creation time`() = runTest {
        val repo = FakeServerRepository()
        val save = SaveServer(repo, clock = { 1_000 })
        val created = assertIs<SaveServerResult.Saved>(save(0, "", "PC", "6742"))
        assertEquals(Server(created.id, "pc", "pc", 6742, true, 1_000), repo.getServer(created.id))

        val laterSave = SaveServer(repo, clock = { 9_999 })
        laterSave(created.id, "Desk", "pc", "6743", autoConnect = false)
        assertEquals(Server(created.id, "Desk", "pc", 6743, false, 1_000), repo.getServer(created.id))
    }

    @Test
    fun `save rejects a second entry for the same address`() = runTest {
        val existing = Server(1, "PC", "192.168.1.2", 6742)
        val save = SaveServer(FakeServerRepository(listOf(existing)))
        assertEquals(SaveServerResult.Duplicate(existing), save(0, "Other", "192.168.1.2", "6742"))
        assertIs<SaveServerResult.Saved>(save(1, "Renamed", "192.168.1.2", "6742"), "editing itself is fine")
        assertIs<SaveServerResult.Invalid>(save(0, "x", "", "6742"))
    }

    @Test
    fun `overviews combine servers, status and devices sorted by name`() = runTest {
        val servers = FakeServerRepository(listOf(Server(1, "zeta", "a", 1), Server(2, "Alpha", "b", 1)))
        val connections = FakeConnectionRepository()
        connections.statuses.value = mapOf(1L to ConnectionStatus.Connected(6, null, null))
        connections.devices.value = mapOf(1L to listOf(device("Keyboard")))

        val overviews = ObserveServerOverviews(servers, connections)().first()
        assertEquals(listOf("Alpha", "zeta"), overviews.map { it.server.name })
        assertEquals(ConnectionStatus.Offline, overviews[0].status)
        assertEquals("Keyboard", overviews[1].devices.single().device.name)
        assertEquals(1L, overviews[1].devices.single().serverId)
    }

    @Test
    fun `device key survives encoding and matches by name location and serial`() {
        val key = DeviceKey.of(42, device("Ärger \u001F Keyboard/1", "Loc ?&="))
        assertEquals(key, DeviceKey.decode(key.encode()))
        assertTrue(key.encode().all { it.isLetterOrDigit() || it in "-_." })
        assertNull(DeviceKey.decode("not base64 !"))
    }

    @Test
    fun `observe device finds it again after the protocol id changed`() = runTest {
        val servers = FakeServerRepository(listOf(Server(1, "PC", "a", 1)))
        val connections = FakeConnectionRepository()
        val original = device("Keyboard")
        connections.devices.value = mapOf(1L to listOf(original))
        val key = DeviceKey.of(1, original)
        val observe = ObserveDevice(ObserveServerOverviews(servers, connections))

        connections.devices.value = mapOf(1L to listOf(original.copy(id = 99, index = 4)))
        assertEquals(99, observe(key).first().device?.device?.id)
        connections.devices.value = emptyMap()
        assertNull(observe(key).first().device)
    }

    @Test
    fun `scan accumulates results, sorts them and marks saved servers`() = runTest {
        val saved = Server(1, "PC", "192.168.1.30", 6742)
        val updates = listOf(
            ScanUpdate.Started("192.168.1.0/24", 254),
            ScanUpdate.Found(DiscoveredServer("192.168.1.100", 6742, null, null, null)),
            ScanUpdate.Found(DiscoveredServer("192.168.1.30", 6742, 6, "OpenRGB 1.0", "desk")),
            ScanUpdate.Found(DiscoveredServer("192.168.1.9", 6742, 4, null, null)),
            ScanUpdate.Progress(254, 254),
        )
        val scan = ScanNetwork(FakeDiscoveryRepository(updates), FakeServerRepository(listOf(saved)))
        assertTrue(scan().first().running)
        // The state stays live (saved markers follow the server list), so wait for the scan to end.
        val final = scan().first { !it.running }
        assertEquals(false, final.running)
        assertEquals("192.168.1.0/24", final.network)
        assertEquals(1f, final.progress)
        assertEquals(listOf("192.168.1.9", "192.168.1.30", "192.168.1.100"), final.found.map { it.server.host })
        assertEquals(listOf(false, true, false), final.found.map { it.alreadySaved })
        assertEquals("desk", final.found[1].server.suggestedName)
    }

    @Test
    fun `scan reports a missing local network`() = runTest {
        val final = ScanNetwork(FakeDiscoveryRepository(listOf(ScanUpdate.NoLocalNetwork)), FakeServerRepository())().first { !it.running }
        assertTrue(final.noLocalNetwork)
        assertEquals(false, final.running)
    }
}
