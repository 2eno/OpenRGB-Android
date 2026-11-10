package dev.eno2.openrgbremote.sdk.client

import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants
import dev.eno2.openrgbremote.sdk.transport.SocketTransport
import dev.eno2.openrgbremote.sdk.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Exponential backoff with jitter for [OpenRgbConnection]. */
public data class ReconnectPolicy(
    val initialDelayMillis: Long = 1_000,
    val maxDelayMillis: Long = 30_000,
    val multiplier: Double = 2.0,
    /** +/- fraction of random jitter, avoids synchronized retries of several phones. */
    val jitter: Double = 0.2,
    /** Give up after this many consecutive failures; [Int.MAX_VALUE] retries forever. */
    val maxAttempts: Int = Int.MAX_VALUE,
) {
    init {
        require(initialDelayMillis > 0 && maxDelayMillis >= initialDelayMillis && multiplier >= 1.0 && jitter in 0.0..1.0)
    }

    /** Delay before retry number [attempt] (1-based). */
    public fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        val base = min(maxDelayMillis.toDouble(), initialDelayMillis * multiplier.pow((attempt - 1).coerceAtLeast(0)))
        val factor = 1.0 + if (jitter == 0.0) 0.0 else random.nextDouble(-jitter, jitter)
        return (base * factor).toLong().coerceIn(1, maxDelayMillis)
    }
}

/** Connection state as shown in the UI. */
public sealed interface ConnectionState {
    /** [OpenRgbConnection.start] not called yet or [OpenRgbConnection.stop] was called. */
    public data object Idle : ConnectionState

    /** Connecting; [attempt] is 0 for the first try. */
    public data class Connecting(val attempt: Int) : ConnectionState

    public data class Connected(val serverInfo: ServerInfo) : ConnectionState

    /** Waiting [delayMillis] before retry number [attempt] after [error]. */
    public data class Reconnecting(val attempt: Int, val delayMillis: Long, val error: ConnectionError) : ConnectionState

    /** Gave up according to [ReconnectPolicy.maxAttempts]. */
    public data class Failed(val error: ConnectionError) : ConnectionState
}

/**
 * Keeps a connection to one server alive: connects, watches for disconnects and reconnects with
 * [ReconnectPolicy]. Each (re)connect uses a fresh [OpenRgbClient]; [devices] and [client] always
 * reflect the current one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class OpenRgbConnection(
    public val host: String,
    public val port: Int = ProtocolConstants.DEFAULT_PORT,
    private val scope: CoroutineScope,
    private val config: OpenRgbClientConfig = OpenRgbClientConfig(),
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val transportFactory: TransportFactory = SocketTransport.Factory,
    private val random: Random = Random.Default,
    private val clientFactory: (String, Int, OpenRgbClientConfig, TransportFactory) -> OpenRgbClient =
        { h, p, c, t -> OpenRgbClient(h, p, c, t) },
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _client = MutableStateFlow<OpenRgbClient?>(null)
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    public val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** The connected client, null while disconnected. */
    public val client: StateFlow<OpenRgbClient?> = _client.asStateFlow()

    public val devices: StateFlow<List<OpenRgbDevice>> = _client
        .flatMapLatest { it?.devices ?: flowOf(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    public val events: Flow<ClientEvent> = _client.flatMapLatest { it?.events ?: emptyFlow() }

    /** Starts the connect/reconnect loop; no-op if already running. */
    @Synchronized
    public fun start() {
        // After giving up the loop sets Failed and then returns; its job can still be active for
        // a moment. A restart requested in that window must not be ignored.
        if (job?.isActive == true && _state.value !is ConnectionState.Failed) return
        job?.cancel()
        job = scope.launch { runLoop() }
    }

    /** Skips the current backoff delay (e.g. user tapped "retry" or the network came back). */
    public fun retryNow() {
        if (_state.value is ConnectionState.Failed) {
            start()
        } else {
            retryNow.trySend(Unit)
        }
    }

    /** Disconnects and stops reconnecting. */
    public fun stop() {
        synchronized(this) {
            job?.cancel()
            job = null
        }
        _client.value?.close()
        _client.value = null
        _state.value = ConnectionState.Idle
    }

    private suspend fun runLoop() {
        var failures = 0
        while (true) {
            _state.value = ConnectionState.Connecting(failures)
            val client = clientFactory(host, port, config, transportFactory)
            val error: ConnectionError = try {
                val info = client.connect()
                failures = 0
                _client.value = client
                _state.value = ConnectionState.Connected(info)
                client.awaitClosed() ?: ConnectionError(ConnectionErrorReason.CONNECTION_LOST, "Connection closed")
            } catch (e: CancellationException) {
                client.close()
                _client.value = null
                throw e
            } catch (e: OpenRgbException) {
                e.error
            } catch (e: Exception) {
                ConnectionError.from(e)
            }
            client.close()
            _client.value = null
            failures++
            if (failures >= policy.maxAttempts) {
                _state.value = ConnectionState.Failed(error)
                return
            }
            val wait = policy.delayFor(failures, random)
            // Drop stale retry requests *before* publishing the state: a retry triggered by someone
            // observing Reconnecting must not be swallowed.
            retryNow.tryReceive()
            _state.value = ConnectionState.Reconnecting(failures, wait, error)
            select {
                retryNow.onReceive { }
                onTimeout(wait) { }
            }
        }
    }
}
