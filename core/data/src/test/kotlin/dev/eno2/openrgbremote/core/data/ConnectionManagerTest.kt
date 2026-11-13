package dev.eno2.openrgbremote.core.data

import dev.eno2.openrgbremote.core.domain.model.isLocalNetworkAddress
import java.net.SocketTimeoutException
import dev.eno2.openrgbremote.sdk.transport.SocketTransport
import dev.eno2.openrgbremote.sdk.transport.TransportFactory
import dev.eno2.openrgbremote.core.domain.repository.LocalNetworkPermission
import dev.eno2.openrgbremote.core.data.connection.ConnectionManager
import dev.eno2.openrgbremote.core.data.connection.NetworkMonitor
import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.ConnectionTestResult
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import dev.eno2.openrgbremote.sdk.client.OpenRgbClientConfig
import dev.eno2.openrgbremote.sdk.client.ReconnectPolicy
import dev.eno2.openrgbremote.sdk.testing.FakeOpenRgbServer
import dev.eno2.openrgbremote.sdk.testing.closedPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionManagerTest {

    private class InMemoryServers(initial: List<Server>) : ServerRepository {
        val list = MutableStateFlow(initial)
        override fun observeServers(): Flow<List<Server>> = list
        override suspend fun getServer(id: Long) = list.value.firstOrNull { it.id == id }
        override suspend fun findByAddress(host: String, port: Int, jumpHost: String?) = list.value.firstOrNull { it.host == host && it.port == port && it.jumpHost?.host == jumpHost }
        override suspend fun save(server: Server): Long { list.update { l -> l.filterNot { it.id == server.id } + server }; return server.id }
        override suspend fun delete(id: Long) = list.update { l -> l.filterNot { it.id == id } }
    }

    private class FakeNetwork : NetworkMonitor {
        override val isOnline = MutableStateFlow(true)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fakes = mutableListOf<AutoCloseable>()
    private val network = FakeNetwork()
    private val config = OpenRgbClientConfig(clientHostname = "test-phone", protocolVersionTimeoutMillis = 300, heartbeatIntervalMillis = 0, deviceListDebounceMillis = 20)
    private val policy = ReconnectPolicy(initialDelayMillis = 50, maxDelayMillis = 100, jitter = 0.0)

    @After
    fun tearDown() {
        scope.cancel()
        fakes.forEach { runCatching { it.close() } }
    }

    private fun server(version: Int = 6) = FakeOpenRgbServer(version).also { fakes += it }

    private fun manager(servers: ServerRepository, grace: Long = 30_000, retryPolicy: ReconnectPolicy = policy, foreground: Boolean = true) =
        ConnectionManager(servers, network, scope, config, retryPolicy, backgroundGraceMillis = grace, startInForeground = foreground)

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun ConnectionManager.awaitStatus(id: Long, predicate: (ConnectionStatus?) -> Boolean): ConnectionStatus? =
        statuses.first { predicate(it[id]) }[id]

    @Test
    fun `auto-connect servers connect and expose their devices`() = test {
        val fake = server()
        val manager = manager(InMemoryServers(listOf(Server(1, "PC", "127.0.0.1", fake.port))))
        val connected = assertIs<ConnectionStatus.Connected>(manager.awaitStatus(1) { it is ConnectionStatus.Connected })
        assertEquals(6, connected.protocolVersion)
        assertEquals("Fake OpenRGB", connected.serverName)
        assertEquals(listOf("Fake Strip", "Fake Keyboard"), manager.devices.first { it[1L]?.size == 2 }.getValue(1).map { it.name })
        assertNotNull(manager.client(1))
    }

    @Test
    fun `servers without auto-connect stay offline until connected manually`() = test {
        val fake = server()
        val manager = manager(InMemoryServers(listOf(Server(1, "PC", "127.0.0.1", fake.port, autoConnect = false))))
        assertEquals(ConnectionStatus.Offline, manager.awaitStatus(1) { it != null })
        manager.connect(1)
        manager.awaitStatus(1) { it is ConnectionStatus.Connected }
        manager.disconnect(1)
        manager.awaitStatus(1) { it == ConnectionStatus.Offline }
        assertNull(manager.client(1))
        assertTrue(manager.devices.value[1L].isNullOrEmpty())
    }

    @Test
    fun `changing the address reconnects and deleting removes the server`() = test {
        val first = server(4)
        val second = server(6)
        val servers = InMemoryServers(listOf(Server(1, "PC", "127.0.0.1", first.port)))
        val manager = manager(servers)
        assertEquals(4, (manager.awaitStatus(1) { it is ConnectionStatus.Connected } as ConnectionStatus.Connected).protocolVersion)

        servers.save(Server(1, "PC", "127.0.0.1", second.port))
        manager.awaitStatus(1) { (it as? ConnectionStatus.Connected)?.protocolVersion == 6 }
        assertEquals(1, second.connectionCount)

        servers.delete(1)
        manager.statuses.first { 1L !in it }
        assertNull(manager.client(1))
    }

    @Test
    fun `refused connections retry with a readable problem`() = test {
        val manager = manager(InMemoryServers(listOf(Server(1, "PC", "127.0.0.1", closedPort()))))
        val status = assertIs<ConnectionStatus.Reconnecting>(manager.awaitStatus(1) { it is ConnectionStatus.Reconnecting })
        assertEquals(ConnectionProblem.CONNECTION_REFUSED, status.problem)
    }

    @Test
    fun `without network every problem is reported as NO_NETWORK`() = test {
        network.isOnline.value = false
        val manager = manager(InMemoryServers(listOf(Server(1, "PC", "127.0.0.1", closedPort()))))
        val status = manager.awaitStatus(1) { it is ConnectionStatus.Reconnecting } as ConnectionStatus.Reconnecting
        assertEquals(ConnectionProblem.NO_NETWORK, status.problem)
    }

    @Test
    fun `network coming back skips the backoff`() = test {
        val port = closedPort()
        val slow = ReconnectPolicy(initialDelayMillis = 60_000, maxDelayMillis = 60_000, jitter = 0.0)
        val manager = manager(InMemoryServers(listOf(Server(1, "PC", "127.0.0.1", port))), retryPolicy = slow)
        manager.awaitStatus(1) { it is ConnectionStatus.Reconnecting } // now waiting 60 s

        network.isOnline.value = false
        // StateFlow conflates: flipping back at once could be seen as "no change" under load.
        manager.awaitStatus(1) { (it as? ConnectionStatus.Reconnecting)?.problem == ConnectionProblem.NO_NETWORK }
        FakeOpenRgbServer(6, fixedPort = port).also { fakes += it } // the PC is back ...
        network.isOnline.value = true // ... and so is Wi-Fi: no 60 s wait
        manager.awaitStatus(1) { it is ConnectionStatus.Connected }
    }

    @Test
    fun `background closes connections after the grace period unless kept alive`() = test {
        val fake = server()
        val manager = manager(InMemoryServers(listOf(Server(1, "PC", "127.0.0.1", fake.port))), grace = 100)
        manager.awaitStatus(1) { it is ConnectionStatus.Connected }

        val keepAlive = manager.acquireKeepAlive()
        manager.setAppInForeground(false)
        kotlinx.coroutines.delay(300)
        assertIs<ConnectionStatus.Connected>(manager.statuses.value[1L], "effect keeps the connection")

        keepAlive.close()
        manager.awaitStatus(1) { it == ConnectionStatus.Offline }

        manager.setAppInForeground(true)
        manager.awaitStatus(1) { it is ConnectionStatus.Connected }
        assertEquals(2, fake.connectionCount)
    }

    @Test
    fun `a process started in the background connects only while kept alive`() = test {
        val fake = server()
        val manager = manager(InMemoryServers(listOf(Server(1, "PC", "127.0.0.1", fake.port))), grace = 100, foreground = false)
        kotlinx.coroutines.delay(300)
        assertEquals(ConnectionStatus.Offline, manager.statuses.value[1L], "a widget or boot start must not open connections")
        assertEquals(0, fake.connectionCount)

        val keepAlive = manager.acquireKeepAlive()
        manager.awaitStatus(1) { it is ConnectionStatus.Connected }
        keepAlive.close()
        manager.awaitStatus(1) { it == ConnectionStatus.Offline }

        manager.setAppInForeground(true)
        manager.awaitStatus(1) { it is ConnectionStatus.Connected }
    }

    @Test
    fun `without the local network permission the problem is named and fixed by granting it`() = test {
        val fake = server()
        val granted = MutableStateFlow(false)
        val permission = object : LocalNetworkPermission { override val granted = granted }
        // Android 17 lets blocked LAN connections time out; this transport does the same until "granted".
        val transport = TransportFactory { host, _, timeout ->
            if (!granted.value && isLocalNetworkAddress(host)) throw SocketTimeoutException("connect timed out")
            SocketTransport.connect("127.0.0.1", fake.port, timeout)
        }
        val slow = ReconnectPolicy(initialDelayMillis = 60_000, maxDelayMillis = 60_000, jitter = 0.0)
        val manager = ConnectionManager(
            InMemoryServers(listOf(Server(1, "PC", "192.168.1.20", fake.port))), network, scope, config, slow,
            transportFactory = transport, localNetwork = permission,
        )
        val waiting = manager.awaitStatus(1) { it is ConnectionStatus.Reconnecting }
        assertEquals(ConnectionProblem.LOCAL_NETWORK_DENIED, (waiting as ConnectionStatus.Reconnecting).problem, "not a vague timeout")

        val test = manager.testConnection("192.168.1.20", fake.port)
        assertEquals(ConnectionProblem.LOCAL_NETWORK_DENIED, assertIs<ConnectionTestResult.Failure>(test).problem, "answered at once instead of after a timeout")
        assertIs<ConnectionTestResult.Success>(manager.testConnection("127.0.0.1", fake.port), "loopback needs no permission")

        granted.value = true // the user tapped "Allow": no 60 s backoff
        manager.awaitStatus(1) { it is ConnectionStatus.Connected }
    }

    @Test
    fun `test connection reports success details and classified failures`() = test {
        val fake = server()
        val manager = manager(InMemoryServers(emptyList()))
        val ok = assertIs<ConnectionTestResult.Success>(manager.testConnection("127.0.0.1", fake.port))
        assertEquals(6, ok.protocolVersion)
        assertEquals("fakehost", ok.serverHostname)
        assertEquals(2, ok.deviceCount)

        val failed = assertIs<ConnectionTestResult.Failure>(manager.testConnection("127.0.0.1", closedPort()))
        assertEquals(ConnectionProblem.CONNECTION_REFUSED, failed.problem)
    }

    @Test
    fun `rescan is forwarded to protocol 5+ servers only`() = test {
        val modern = server(6)
        val old = server(4)
        val manager = manager(InMemoryServers(listOf(Server(1, "A", "127.0.0.1", modern.port), Server(2, "B", "127.0.0.1", old.port))))
        manager.awaitStatus(1) { it is ConnectionStatus.Connected }
        manager.awaitStatus(2) { it is ConnectionStatus.Connected }
        assertTrue(manager.rescanHardware(1))
        assertEquals(false, manager.rescanHardware(2))
        assertEquals(false, manager.rescanHardware(99))
    }
}
