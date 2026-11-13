package dev.eno2.openrgbremote

import dev.eno2.openrgbremote.core.domain.model.DiscoveredServer
import dev.eno2.openrgbremote.core.domain.model.ScanUpdate
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.repository.LocalNetworkPermission
import dev.eno2.openrgbremote.core.domain.testing.FakeConnectionRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeDiscoveryRepository
import dev.eno2.openrgbremote.core.domain.testing.FakeEffectController
import dev.eno2.openrgbremote.core.domain.testing.FakeServerRepository
import dev.eno2.openrgbremote.core.domain.usecase.ObserveServerOverviews
import dev.eno2.openrgbremote.core.domain.usecase.ScanNetwork
import dev.eno2.openrgbremote.feature.devices.DevicesViewModel
import dev.eno2.openrgbremote.feature.servers.ScanViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android 17's local network permission in the screens that need the LAN. */
class LocalNetworkViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val granted = MutableStateFlow(false)
    private val permission = object : LocalNetworkPermission { override val granted = this@LocalNetworkViewModelTest.granted }
    private val servers = FakeServerRepository()

    @Test
    fun `the scan waits for the permission instead of finding nothing`() = runTest {
        val found = DiscoveredServer("192.168.1.20", 6742, 6, "OpenRGB 1.0", "pc")
        val discovery = FakeDiscoveryRepository(listOf(ScanUpdate.Started("192.168.1.0/24", 254), ScanUpdate.Found(found)))
        val vm = ScanViewModel(ScanNetwork(discovery, servers), permission)
        assertTrue(vm.state.value.permissionMissing)
        assertFalse(vm.state.value.running)
        assertTrue(vm.state.value.found.isEmpty())

        granted.value = true // granted in the system dialog
        assertFalse(vm.state.value.permissionMissing)
        assertEquals(listOf("192.168.1.20"), vm.state.value.found.map { it.server.host }, "scanned right after the grant")
    }

    @Test
    fun `the device list asks for the permission only for LAN addresses`() = runTest {
        val connections = FakeConnectionRepository()
        val effects = FakeEffectController()
        servers.save(Server(1, "Remote", "203.0.113.5"))
        val vm = DevicesViewModel(ObserveServerOverviews(servers, connections), connections, effects, effects, permission)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertFalse(vm.state.value.localNetworkDenied, "a public address is internet traffic")

        servers.save(Server(2, "Desk", "127.0.0.1", jumpHost = dev.eno2.openrgbremote.core.domain.model.JumpHost("10.0.0.204", username = "zeno")))
        assertTrue(vm.state.value.localNetworkDenied, "the jump host is in the LAN")
        granted.value = true
        assertFalse(vm.state.value.localNetworkDenied)
    }
}
