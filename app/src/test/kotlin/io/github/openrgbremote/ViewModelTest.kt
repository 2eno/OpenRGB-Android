package io.github.openrgbremote

import androidx.lifecycle.SavedStateHandle
import io.github.openrgbremote.core.domain.model.ConnectionProblem
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.ConnectionTestResult
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.testing.FakeConnectionRepository
import io.github.openrgbremote.core.domain.testing.FakeServerRepository
import io.github.openrgbremote.core.domain.usecase.HostError
import io.github.openrgbremote.core.domain.usecase.ObserveServerOverviews
import io.github.openrgbremote.core.domain.usecase.PortError
import io.github.openrgbremote.core.domain.usecase.SaveServer
import io.github.openrgbremote.core.domain.usecase.ValidateServerInput
import io.github.openrgbremote.feature.devices.DevicesViewModel
import io.github.openrgbremote.feature.servers.ServerEditViewModel
import io.github.openrgbremote.feature.servers.TestState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val servers = FakeServerRepository(listOf(Server(1, "Desk", "192.168.1.30", 6742, autoConnect = false, createdAtMillis = 5)))
    private val connections = FakeConnectionRepository()

    private fun editViewModel(vararg args: Pair<String, Any?>) =
        ServerEditViewModel(SavedStateHandle(mapOf(*args)), servers, connections, SaveServer(servers), ValidateServerInput())

    @Test
    fun `edit form loads an existing server`() = runTest {
        val vm = editViewModel("serverId" to 1L)
        val state = vm.state.value
        assertEquals("Desk", state.name)
        assertEquals("192.168.1.30", state.host)
        assertEquals(false, state.autoConnect)
        assertTrue(state.isEdit)
    }

    @Test
    fun `edit form is prefilled from the scan`() = runTest {
        val vm = editViewModel("host" to "192.168.1.9", "port" to 6743, "name" to "gaming")
        assertEquals("192.168.1.9", vm.state.value.host)
        assertEquals("6743", vm.state.value.port)
        assertEquals("gaming", vm.state.value.name)
    }

    @Test
    fun `invalid input is reported before testing`() = runTest {
        val vm = editViewModel()
        vm.onHostChange("my pc")
        vm.onPortChange("0")
        vm.testConnection()
        assertEquals(HostError.CONTAINS_SPACES, vm.state.value.hostError)
        assertEquals(PortError.OUT_OF_RANGE, vm.state.value.portError)
        assertTrue(connections.calls.none { it.startsWith("test") })
        vm.onHostChange("pc")
        assertNull(vm.state.value.hostError, "typing clears the error")
    }

    @Test
    fun `port field accepts digits only`() = runTest {
        val vm = editViewModel()
        vm.onPortChange("67a42 99")
        assertEquals("67429", vm.state.value.port)
    }

    @Test
    fun `successful test suggests the PC name`() = runTest {
        connections.testResult = ConnectionTestResult.Success(6, "OpenRGB 1.0", "gaming-rig", 4)
        val vm = editViewModel()
        vm.onHostChange("192.168.1.50")
        vm.testConnection()
        assertIs<TestState.Success>(vm.state.value.test)
        assertEquals("gaming-rig", vm.state.value.name)
        assertEquals(listOf("test 192.168.1.50:6742"), connections.calls)
    }

    @Test
    fun `failed test shows the problem`() = runTest {
        connections.testResult = ConnectionTestResult.Failure(ConnectionProblem.CONNECTION_REFUSED, "refused")
        val vm = editViewModel()
        vm.onHostChange("192.168.1.50")
        vm.testConnection()
        assertEquals(ConnectionProblem.CONNECTION_REFUSED, (vm.state.value.test as TestState.Failure).problem)
    }

    @Test
    fun `duplicate addresses are rejected and new servers are saved`() = runTest {
        val vm = editViewModel()
        vm.onHostChange("192.168.1.30")
        vm.save()
        assertEquals("Desk", vm.state.value.duplicateOf)
        vm.onHostChange("192.168.1.31")
        vm.save()
        val id = vm.state.value.savedId
        assertEquals("192.168.1.31", servers.getServer(id!!)?.host)
    }

    @Test
    fun `refresh retries servers that are not connected and reloads the others`() = runTest {
        servers.save(Server(2, "Media", "192.168.1.40"))
        connections.statuses.value = mapOf(2L to ConnectionStatus.Connected(6, null, null))
        val vm = DevicesViewModel(ObserveServerOverviews(servers, connections), connections)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(listOf("Desk", "Media"), vm.state.value.servers.map { it.server.name })
        vm.refresh()
        assertEquals(listOf("retry 1", "refreshAll"), connections.calls)
        assertEquals(false, vm.state.value.refreshing)
    }
}
