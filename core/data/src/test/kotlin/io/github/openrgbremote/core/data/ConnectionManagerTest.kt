package io.github.openrgbremote.core.data

import io.github.openrgbremote.core.data.connection.ConnectionManager
import io.github.openrgbremote.core.data.connection.NetworkMonitor
import io.github.openrgbremote.core.domain.model.ConnectionProblem
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.ConnectionTestResult
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.core.domain.repository.ServerRepository
import io.github.openrgbremote.sdk.client.OpenRgbClientConfig
import io.github.openrgbremote.sdk.client.ReconnectPolicy
import io.github.openrgbremote.sdk.testing.FakeOpenRgbServer
import io.github.openrgbremote.sdk.testing.closedPort
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
        override suspend fun findByAddress(host: String, port: Int) = list.value.firstOrNull { it.host == host && it.port == port }
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

    private fun manager(servers: ServerRepository, grace: Long = 30_000, retryPolicy: ReconnectPolicy = policy) =
        ConnectionManager(servers, network, scope, config, retryPolicy, backgroundGraceMillis = grace)

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
