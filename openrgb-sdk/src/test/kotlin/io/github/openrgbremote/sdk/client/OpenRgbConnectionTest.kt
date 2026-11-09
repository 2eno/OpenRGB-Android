package io.github.openrgbremote.sdk.client

import io.github.openrgbremote.sdk.testing.FakeOpenRgbServer
import io.github.openrgbremote.sdk.testing.GarbageServer
import io.github.openrgbremote.sdk.testing.closedPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenRgbConnectionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        scope.cancel()
        closeables.forEach { runCatching { it.close() } }
    }

    private val config = OpenRgbClientConfig(protocolVersionTimeoutMillis = 300, heartbeatIntervalMillis = 0, deviceListDebounceMillis = 20)
    private val fastPolicy = ReconnectPolicy(initialDelayMillis = 50, maxDelayMillis = 200, jitter = 0.0)

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun `backoff grows exponentially up to the cap`() {
        val policy = ReconnectPolicy(initialDelayMillis = 1_000, maxDelayMillis = 30_000, multiplier = 2.0, jitter = 0.0)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map { policy.delayFor(it) })
    }

    @Test
    fun `jitter stays within bounds`() {
        val policy = ReconnectPolicy(initialDelayMillis = 1_000, jitter = 0.2)
        val random = Random(7)
        repeat(100) { assertTrue(policy.delayFor(1, random) in 800L..1_200L) }
    }

    @Test
    fun `reconnects after the server dropped the connection`() = test {
        val server = FakeOpenRgbServer(6).also { closeables += it }
        val connection = OpenRgbConnection("127.0.0.1", server.port, scope, config, fastPolicy)
        connection.start()

        assertIs<ConnectionState.Connected>(connection.state.first { it is ConnectionState.Connected })
        assertEquals(2, connection.devices.first { it.isNotEmpty() }.size)

        server.dropAllConnections()
        val reconnecting = connection.state.first { it is ConnectionState.Reconnecting } as ConnectionState.Reconnecting
        assertEquals(ConnectionErrorReason.CONNECTION_LOST, reconnecting.error.reason)

        connection.state.first { it is ConnectionState.Connected }
        assertEquals(2, server.connectionCount)
        assertEquals(2, connection.devices.first { it.isNotEmpty() }.size)
        connection.stop()
        assertEquals(ConnectionState.Idle, connection.state.value)
    }

    @Test
    fun `gives up after maxAttempts and a manual retry starts a new round`() = test {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val connection = OpenRgbConnection(
            "127.0.0.1", closedPort(), scope, config, fastPolicy.copy(maxAttempts = 2),
            clientFactory = { h, p, c, t -> attempts.incrementAndGet(); OpenRgbClient(h, p, c, t) },
        )
        connection.start()
        val failed = connection.state.first { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals(ConnectionErrorReason.CONNECTION_REFUSED, failed.error.reason)
        assertEquals(2, attempts.get())

        connection.retryNow()
        while (attempts.get() < 4) kotlinx.coroutines.delay(10)
        connection.state.first { it is ConnectionState.Failed }
        assertEquals(4, attempts.get())
        connection.stop()
    }

    @Test
    fun `retryNow skips a long backoff`() = test {
        val server = FakeOpenRgbServer(6).also { closeables += it }
        val slowPolicy = ReconnectPolicy(initialDelayMillis = 60_000, maxDelayMillis = 60_000, jitter = 0.0)
        val connection = OpenRgbConnection("127.0.0.1", server.port, scope, config, slowPolicy)
        connection.start()
        connection.state.first { it is ConnectionState.Connected }
        server.dropAllConnections()
        connection.state.first { it is ConnectionState.Reconnecting }
        connection.retryNow()
        connection.state.first { it is ConnectionState.Connected }
        connection.stop()
    }
}
