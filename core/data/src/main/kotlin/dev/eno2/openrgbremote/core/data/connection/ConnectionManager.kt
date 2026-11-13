package dev.eno2.openrgbremote.core.data.connection

import dev.eno2.openrgbremote.core.domain.model.ConnectionProblem
import dev.eno2.openrgbremote.core.domain.model.ConnectionStatus
import dev.eno2.openrgbremote.core.domain.model.ConnectionTestResult
import dev.eno2.openrgbremote.core.domain.model.Server
import dev.eno2.openrgbremote.core.domain.repository.ConnectionRepository
import dev.eno2.openrgbremote.core.domain.repository.ServerRepository
import dev.eno2.openrgbremote.sdk.client.ConnectionErrorReason
import dev.eno2.openrgbremote.sdk.client.ConnectionState
import dev.eno2.openrgbremote.sdk.client.OpenRgbClient
import dev.eno2.openrgbremote.sdk.client.OpenRgbClientConfig
import dev.eno2.openrgbremote.sdk.client.OpenRgbConnection
import dev.eno2.openrgbremote.sdk.client.OpenRgbException
import dev.eno2.openrgbremote.sdk.client.ReconnectPolicy
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.transport.SocketTransport
import dev.eno2.openrgbremote.sdk.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import android.util.Log
import dev.eno2.openrgbremote.core.domain.model.isLocalNetworkAddress
import dev.eno2.openrgbremote.core.domain.repository.LocalNetworkPermission
import dev.eno2.openrgbremote.core.data.ssh.JumpTransports
import dev.eno2.openrgbremote.core.data.ssh.UntrustedHostKeyException
import dev.eno2.openrgbremote.core.domain.model.JumpHost
import java.util.concurrent.atomic.AtomicInteger

/** Keeps connections open while held, e.g. for a widget action or a running effect. */
fun interface ConnectionKeepAlive {
    fun acquireKeepAlive(): Closeable
}

/**
 * Keeps one [OpenRgbConnection] per saved server that should be connected right now.
 *
 * A server is connected while the app is *active* (in the foreground, or kept alive by a running
 * effect via [acquireKeepAlive]) and either has auto-connect enabled or was connected manually.
 * The decision is recomputed by a single collector whenever the server list, the activity or the
 * manual overrides change, so there is no locking between UI calls and lifecycle events.
 */
class ConnectionManager(
    private val servers: ServerRepository,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val clientConfig: OpenRgbClientConfig,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val transportFactory: TransportFactory = SocketTransport.Factory,
    /** Connections stay open this long after the app went to the background (quick app switches). */
    private val backgroundGraceMillis: Long = 30_000,
    /**
     * False in the app: Android also starts the process in the background (widget, tile, alarm,
     * boot), where no lifecycle event would ever report "background". The app's lifecycle
     * observer reports the foreground instead.
     */
    startInForeground: Boolean = true,
    /** Transports for servers behind an SSH jump host. */
    private val jumpTransports: JumpTransports = JumpTransports.Unsupported,
    /** Android 17's local network permission; without it LAN connections only time out. */
    private val localNetwork: LocalNetworkPermission = LocalNetworkPermission.Granted,
) : ConnectionRepository, ConnectionKeepAlive {

    private data class Overrides(val connect: Set<Long> = emptySet(), val disconnect: Set<Long> = emptySet())

    private class Entry(val server: Server, val connection: OpenRgbConnection, val jobs: List<Job>)

    private val entries = mutableMapOf<Long, Entry>() // only touched by the reconcile collector
    private val overrides = MutableStateFlow(Overrides())
    private val foreground = MutableStateFlow(startInForeground)
    private val keepAliveCount = MutableStateFlow(0)
    private val active = MutableStateFlow(startInForeground)
    private var backgroundJob: Job? = null

    private val _statuses = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())
    private val _devices = MutableStateFlow<Map<Long, List<OpenRgbDevice>>>(emptyMap())
    override val statuses: StateFlow<Map<Long, ConnectionStatus>> = _statuses.asStateFlow()
    override val devices: StateFlow<Map<Long, List<OpenRgbDevice>>> = _devices.asStateFlow()

    init {
        scope.launch {
            combine(servers.observeServers(), active, overrides) { list, isActive, manual -> Triple(list, isActive, manual) }
                .collect { (list, isActive, manual) -> reconcile(list, isActive, manual) }
        }
        scope.launch {
            combine(foreground, keepAliveCount) { fg, keepAlive -> fg || keepAlive > 0 }
                .distinctUntilChanged()
                .collect { wanted -> onActivityChanged(wanted) }
        }
        scope.launch {
            // Network came back: skip the backoff instead of waiting up to 30 s.
            networkMonitor.isOnline.drop(1).filter { it }.collect { retryAllNow() }
        }
        scope.launch {
            // The user just allowed local network access: connect now.
            localNetwork.granted.drop(1).filter { it }.collect { retryAllNow() }
        }
    }

    // ------------------------------------------------------------------------------------
    // ConnectionRepository
    // ------------------------------------------------------------------------------------

    override fun connect(serverId: Long) =
        overrides.update { Overrides(it.connect + serverId, it.disconnect - serverId) }

    override fun disconnect(serverId: Long) =
        overrides.update { Overrides(it.connect - serverId, it.disconnect + serverId) }

    override fun retry(serverId: Long) {
        val entry = synchronized(entries) { entries[serverId] }
        if (entry != null) entry.connection.retryNow() else connect(serverId)
    }

    override suspend fun refreshAll() {
        clients().forEach { client ->
            try {
                client.refreshDevices()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The connection reports its own failure through its state.
            }
        }
    }

    override suspend fun rescanHardware(serverId: Long): Boolean {
        val client = client(serverId) ?: return false
        return try {
            client.rescanDevices()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun testConnection(host: String, port: Int, jumpHost: JumpHost?, password: String?, serverId: Long): ConnectionTestResult {
        if (!localNetwork.granted.value && isLocalNetworkAddress(jumpHost?.host ?: host)) {
            // It would only run into a timeout.
            return ConnectionTestResult.Failure(ConnectionProblem.LOCAL_NETWORK_DENIED, "ACCESS_LOCAL_NETWORK not granted")
        }
        val transports = jumpHost?.let { jumpTransports.create(it, serverId, password) } ?: transportFactory
        val client = OpenRgbClient(host, port, clientConfig.copy(heartbeatIntervalMillis = 0), transports)
        // The SSH login and host key exchange come on top of the OpenRGB handshake.
        val timeout = if (jumpHost != null) TEST_TIMEOUT_MILLIS + JUMP_TEST_EXTRA_MILLIS else TEST_TIMEOUT_MILLIS
        return try {
            val result = withTimeoutOrNull(timeout) {
                val info = client.connect()
                ConnectionTestResult.Success(info.protocolVersion, info.serverName, info.serverHostname, client.devices.value.size)
            }
            result ?: ConnectionTestResult.Failure(ConnectionProblem.TIMEOUT, "No answer within ${timeout / 1000} s")
        } catch (e: OpenRgbException) {
            val untrusted = e.error.cause as? UntrustedHostKeyException ?: e.cause as? UntrustedHostKeyException
            if (untrusted != null) {
                ConnectionTestResult.UntrustedHostKey(untrusted.key, untrusted.changed)
            } else {
                ConnectionTestResult.Failure(e.error.reason.toProblem(networkMonitor.isOnline.value), e.error.message)
                .also { Log.i(TAG, "Test of $host:$port${jumpHost?.let { " via ${it.address}" }.orEmpty()} failed: ${e.error.reason} ${e.error.message}", e.error.cause) }
            }
        } finally {
            client.close()
        }
    }

    // ------------------------------------------------------------------------------------
    // Lifecycle and access for other components
    // ------------------------------------------------------------------------------------

    /** Called by the app's process lifecycle observer. */
    fun setAppInForeground(inForeground: Boolean) {
        foreground.value = inForeground
    }

    /**
     * Keeps connections open while the app is in the background, e.g. for a running effect.
     * Close the returned handle to release it.
     */
    override fun acquireKeepAlive(): Closeable {
        keepAliveCount.update { it + 1 }
        val released = AtomicInteger(0)
        return Closeable { if (released.compareAndSet(0, 1)) keepAliveCount.update { it - 1 } }
    }

    /** The connected client of [serverId], for commands such as LED updates. */
    fun client(serverId: Long): OpenRgbClient? = synchronized(entries) { entries[serverId] }?.connection?.client?.value

    private fun clients(): List<OpenRgbClient> = synchronized(entries) { entries.values.mapNotNull { it.connection.client.value } }

    // ------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------

    private fun onActivityChanged(wanted: Boolean) {
        backgroundJob?.cancel()
        if (wanted) {
            active.value = true
        } else {
            backgroundJob = scope.launch {
                delay(backgroundGraceMillis)
                active.value = false
            }
        }
    }

    private fun retryAllNow() = synchronized(entries) { entries.values.toList() }.forEach { it.connection.retryNow() }

    private fun reconcile(list: List<Server>, isActive: Boolean, manual: Overrides) {
        val wanted = if (!isActive) {
            emptyMap()
        } else {
            list.filter { (it.autoConnect && it.id !in manual.disconnect) || it.id in manual.connect }.associateBy { it.id }
        }
        val removed = mutableListOf<Entry>()
        synchronized(entries) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val (id, entry) = iterator.next()
                val target = wanted[id]
                // A changed jump host, login method or confirmed key needs a new connection too.
                if (target == null || target.host != entry.server.host || target.port != entry.server.port || target.jumpHost != entry.server.jumpHost) {
                    removed += entry
                    iterator.remove()
                }
            }
        }
        removed.forEach { stop(it) }

        _statuses.update { current ->
            list.associate { s -> s.id to if (s.id in wanted) current[s.id] ?: ConnectionStatus.Connecting(0) else ConnectionStatus.Offline }
        }
        _devices.update { current -> current.filterKeys { it in wanted } }

        for (server in wanted.values) {
            val existing = synchronized(entries) { entries[server.id] }
            if (existing == null) {
                val entry = start(server)
                synchronized(entries) { entries[server.id] = entry }
            }
        }
    }

    private fun start(server: Server): Entry {
        val transports = server.jumpHost?.let { jumpTransports.create(it, server.id, null) } ?: transportFactory
        val connection = OpenRgbConnection(server.host, server.port, scope, clientConfig, reconnectPolicy, transports)
        // The address the phone itself connects to: the jump host, if there is one.
        val contactsLocalNetwork = isLocalNetworkAddress(server.jumpHost?.host ?: server.host)
        val statusJob = scope.launch {
            combine(connection.state, networkMonitor.isOnline, localNetwork.granted) { state, online, granted ->
                state.toStatus(online, lanDenied = !granted && contactsLocalNetwork)
            }
                .distinctUntilChanged()
                .collect { status ->
                    if (status is ConnectionStatus.Reconnecting || status is ConnectionStatus.Failed) logFailure(server, connection.state.value)
                    _statuses.update { it + (server.id to status) }
                }
        }
        val devicesJob = scope.launch {
            connection.devices.collect { list -> _devices.update { it + (server.id to list) } }
        }
        connection.start()
        return Entry(server, connection, listOf(statusJob, devicesJob))
    }

    private fun logFailure(server: Server, state: ConnectionState) {
        val error = when (state) {
            is ConnectionState.Reconnecting -> state.error
            is ConnectionState.Failed -> state.error
            else -> return
        }
        // Only the technical reason; the user sees a translated explanation.
        Log.i(TAG, "${server.address}${server.jumpHost?.let { " via ${it.address}" }.orEmpty()}: ${error.reason} ${error.message}", error.cause)
    }

    private fun stop(entry: Entry) {
        entry.jobs.forEach { it.cancel() }
        entry.connection.stop()
        _statuses.update { it - entry.server.id }
        _devices.update { it - entry.server.id }
    }

    companion object {
        private const val TAG = "OpenRgbConnection"
        const val TEST_TIMEOUT_MILLIS = 8_000L
        const val JUMP_TEST_EXTRA_MILLIS = 7_000L

        fun ConnectionState.toStatus(online: Boolean, lanDenied: Boolean = false): ConnectionStatus = when (this) {
            ConnectionState.Idle -> ConnectionStatus.Offline
            is ConnectionState.Connecting -> ConnectionStatus.Connecting(attempt)
            is ConnectionState.Connected ->
                ConnectionStatus.Connected(serverInfo.protocolVersion, serverInfo.serverName, serverInfo.serverHostname)
            is ConnectionState.Reconnecting ->
                ConnectionStatus.Reconnecting(attempt, delayMillis, error.reason.toProblem(online, lanDenied), error.message)
            is ConnectionState.Failed -> ConnectionStatus.Failed(error.reason.toProblem(online, lanDenied), error.message)
        }

        /** Failures that a blocked local network produces: it lets connections time out. */
        private val LAN_BLOCK_SYMPTOMS = setOf(
            ConnectionErrorReason.TIMEOUT,
            ConnectionErrorReason.UNREACHABLE,
            ConnectionErrorReason.CONNECTION_REFUSED,
            ConnectionErrorReason.CONNECTION_LOST,
            ConnectionErrorReason.UNKNOWN,
            ConnectionErrorReason.JUMP_HOST_UNREACHABLE,
        )

        /**
         * Without a network every failure has the same cause, which is also the easiest to fix.
         * The same goes for a missing local network permission ([lanDenied]).
         */
        fun ConnectionErrorReason.toProblem(online: Boolean, lanDenied: Boolean = false): ConnectionProblem = if (!online) {
            ConnectionProblem.NO_NETWORK
        } else if (lanDenied && this in LAN_BLOCK_SYMPTOMS) {
            ConnectionProblem.LOCAL_NETWORK_DENIED
        } else {
            when (this) {
                ConnectionErrorReason.UNKNOWN_HOST -> ConnectionProblem.UNKNOWN_HOST
                ConnectionErrorReason.CONNECTION_REFUSED -> ConnectionProblem.CONNECTION_REFUSED
                ConnectionErrorReason.TIMEOUT -> ConnectionProblem.TIMEOUT
                ConnectionErrorReason.UNREACHABLE -> ConnectionProblem.UNREACHABLE
                ConnectionErrorReason.NOT_OPENRGB -> ConnectionProblem.NOT_OPENRGB
                ConnectionErrorReason.PROTOCOL_ERROR -> ConnectionProblem.PROTOCOL_ERROR
                ConnectionErrorReason.CONNECTION_LOST -> ConnectionProblem.CONNECTION_LOST
                ConnectionErrorReason.UNKNOWN -> ConnectionProblem.UNKNOWN
                ConnectionErrorReason.JUMP_HOST_UNREACHABLE -> ConnectionProblem.JUMP_HOST_UNREACHABLE
                ConnectionErrorReason.JUMP_HOST_AUTH_FAILED -> ConnectionProblem.JUMP_HOST_AUTH_FAILED
                ConnectionErrorReason.JUMP_HOST_KEY_UNKNOWN -> ConnectionProblem.JUMP_HOST_KEY_UNKNOWN
                ConnectionErrorReason.JUMP_HOST_KEY_CHANGED -> ConnectionProblem.JUMP_HOST_KEY_CHANGED
                ConnectionErrorReason.JUMP_TARGET_UNREACHABLE -> ConnectionProblem.JUMP_TARGET_UNREACHABLE
            }
        }
    }
}
