package dev.eno2.openrgbremote.sdk.client

import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.protocol.AckStatus
import dev.eno2.openrgbremote.sdk.protocol.ClientFlags
import dev.eno2.openrgbremote.sdk.protocol.DeviceCodec
import dev.eno2.openrgbremote.sdk.protocol.Packet
import dev.eno2.openrgbremote.sdk.protocol.PacketId
import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants
import dev.eno2.openrgbremote.sdk.protocol.ProtocolException
import dev.eno2.openrgbremote.sdk.protocol.ProtocolFeatures
import dev.eno2.openrgbremote.sdk.protocol.Requests
import dev.eno2.openrgbremote.sdk.protocol.Responses
import dev.eno2.openrgbremote.sdk.protocol.SignalUpdate
import dev.eno2.openrgbremote.sdk.transport.PacketTransport
import dev.eno2.openrgbremote.sdk.transport.SocketTransport
import dev.eno2.openrgbremote.sdk.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Client for one OpenRGB SDK server connection.
 *
 * Lifecycle: create, [connect] once, use, [close]. A dropped connection is final for an
 * instance; [OpenRgbConnection] wraps this class with automatic reconnects.
 *
 * Concurrency model:
 * - one reader coroutine consumes every incoming packet and dispatches it
 *   (replies, ACKs, DEVICE_LIST_UPDATED, SIGNALUPDATE, server info),
 * - request/reply exchanges are serialized by [requestMutex] because the protocol has no
 *   request ids; a reply is matched by packet id (and device id for controller data),
 * - fire-and-forget commands (LED updates, mode changes) bypass [requestMutex] and are only
 *   serialized at the socket level, so effect frames are never stuck behind a slow refresh.
 */
public class OpenRgbClient(
    public val host: String,
    public val port: Int = ProtocolConstants.DEFAULT_PORT,
    private val config: OpenRgbClientConfig = OpenRgbClientConfig(),
    private val transportFactory: TransportFactory = SocketTransport.Factory,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {

    private enum class Lifecycle { NEW, CONNECTING, CONNECTED, CLOSED }

    private class PendingReply(val packetId: Int, val deviceId: Int?, val deferred: CompletableDeferred<Packet>)

    /** Server facts that can arrive before [ServerInfo] exists (SET_SERVER_NAME follows the version reply immediately). */
    private data class ServerExtras(val name: String? = null, val hostname: String? = null, val flags: Int? = null)

    private val lifecycle = AtomicReference(Lifecycle.NEW)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("OpenRgbClient[$host:$port]"))

    @Volatile
    private var transport: PacketTransport? = null
    private val requestMutex = Mutex()
    private val pending = AtomicReference<PendingReply?>(null)
    private val closedSignal = CompletableDeferred<ConnectionError?>()
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val ackWindows = ConcurrentHashMap<Int, MutableStateFlow<Int>>()
    private val serverExtras = AtomicReference(ServerExtras())

    private val _devices = MutableStateFlow<List<OpenRgbDevice>>(emptyList())
    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Current device list; empty while (re)loading with protocol < 6. */
    public val devices: StateFlow<List<OpenRgbDevice>> = _devices.asStateFlow()

    /** Handshake results; null before [connect] finished. */
    public val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    public val events: SharedFlow<ClientEvent> = _events.asSharedFlow()

    /** Negotiated protocol version (valid after [connect]). */
    @Volatile
    public var protocolVersion: Int = 0
        private set

    /**
     * For protocol < 6 device ids are list indices. After DEVICE_LIST_UPDATED they must not be
     * used until the list was reloaded (see "Device IDs" in the SDK docs).
     */
    @Volatile
    private var indicesValid: Boolean = false

    public val isConnected: Boolean get() = lifecycle.get() == Lifecycle.CONNECTED

    /**
     * Opens the TCP connection, negotiates the protocol version, announces the client and loads
     * the device list.
     *
     * @throws OpenRgbException with a classified [ConnectionError] on failure.
     */
    public suspend fun connect(): ServerInfo {
        check(lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.CONNECTING)) { "connect() may only be called once per client" }
        try {
            val t = try {
                transportFactory.connect(host, port, config.connectTimeoutMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw OpenRgbException(ConnectionError.from(e))
            }
            transport = t
            if (lifecycle.get() == Lifecycle.CLOSED) {
                t.close()
                throw OpenRgbException(ConnectionErrorReason.CONNECTION_LOST, "Client was closed while connecting")
            }
            scope.launch { readLoop(t) }

            val serverVersion = negotiateProtocolVersion()
            val version = minOf(serverVersion, config.maxProtocolVersion)
            protocolVersion = version
            _serverInfo.value = ServerInfo(host, port, serverVersion, version)
            applyServerExtras()

            send(Requests.setClientName(config.clientName))
            if (version >= 6) {
                config.clientHostname?.let { send(Requests.setClientHostname(it)) }
                send(Requests.setClientFlags(ClientFlags.SUPPORTS_RGBCONTROLLER))
            }

            requestMutex.withLock { reloadDeviceList() }

            if (!lifecycle.compareAndSet(Lifecycle.CONNECTING, Lifecycle.CONNECTED)) {
                throw OpenRgbException(ConnectionErrorReason.CONNECTION_LOST, "Connection closed during handshake")
            }
            scope.launch { refreshLoop() }
            if (config.heartbeatIntervalMillis > 0) scope.launch { heartbeatLoop() }
            return checkNotNull(_serverInfo.value)
        } catch (e: Throwable) {
            val error = when (e) {
                is OpenRgbException -> e.error
                is CancellationException -> ConnectionError(ConnectionErrorReason.CONNECTION_LOST, "Connect cancelled", e)
                else -> ConnectionError.from(e)
            }
            shutdown(error)
            if (e is CancellationException) throw e
            throw (e as? OpenRgbException) ?: OpenRgbException(error)
        }
    }

    /** Suspends until the connection ended; returns the error, or null after a regular [close]. */
    public suspend fun awaitClosed(): ConnectionError? = closedSignal.await()

    override fun close() {
        shutdown(null)
    }

    // ------------------------------------------------------------------------------------
    // Device list
    // ------------------------------------------------------------------------------------

    /** Re-synchronizes the device list with the server and returns it. */
    public suspend fun refreshDevices(): List<OpenRgbDevice> {
        ensureConnected()
        return requestMutex.withLock { reloadDeviceList() }
    }

    /** Re-requests the description of one device (e.g. after a mode change on protocol < 6). */
    public suspend fun refreshDevice(deviceId: Int): OpenRgbDevice? {
        ensureConnected()
        return requestMutex.withLock {
            val current = _devices.value.firstOrNull { it.id == deviceId } ?: return@withLock null
            val updated = fetchDevice(deviceId, current.index) ?: return@withLock null
            replaceDevice(updated)
            updated
        }
    }

    public fun device(deviceId: Int): OpenRgbDevice? = _devices.value.firstOrNull { it.id == deviceId }

    private suspend fun reloadDeviceList(): List<OpenRgbDevice> {
        val version = protocolVersion
        if (!ProtocolFeatures.hasUniqueIds(version)) {
            indicesValid = false
            _devices.value = emptyList()
        }
        val countReply = exchange(Requests.requestControllerCount(), PacketId.REQUEST_CONTROLLER_COUNT, null, config.requestTimeoutMillis)
        val ids = Responses.controllerIds(countReply, version)
        val existing = _devices.value.associateBy { it.id }
        val result = ArrayList<OpenRgbDevice>(ids.size)
        ids.forEachIndexed { index, id ->
            // Protocol 6+: ids are stable, unchanged devices keep their state (SIGNALUPDATE keeps them fresh).
            val known = if (ProtocolFeatures.hasUniqueIds(version)) existing[id] else null
            val device = known?.copy(index = index) ?: fetchDevice(id, index)
            if (device != null) result += device
        }
        _devices.value = result
        indicesValid = true
        ackWindows.keys.retainAll(result.map { it.id }.toSet())
        _events.tryEmit(ClientEvent.DevicesRefreshed(result.size))
        return result
    }

    private suspend fun fetchDevice(id: Int, index: Int): OpenRgbDevice? {
        val reply = exchangeOrNull(
            Requests.requestControllerData(id, protocolVersion),
            PacketId.REQUEST_CONTROLLER_DATA,
            id,
            config.requestTimeoutMillis,
        ) ?: return null // device vanished between count and data request
        return DeviceCodec.decodeControllerData(reply.payload, protocolVersion, id, index)
    }

    private fun replaceDevice(device: OpenRgbDevice) {
        _devices.update { list -> list.map { if (it.id == device.id) device else it } }
    }

    // ------------------------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------------------------

    /**
     * Sets all LEDs of a device (`UpdateLEDs`). [colors] (`0xRRGGBB`) is fitted to the device's
     * LED count: surplus entries are dropped, missing ones keep their current color. Old servers
     * (< 1.0) do not validate the count, so sending more colors than LEDs must be avoided.
     *
     * @return false if the device is unknown or ids are currently invalid (list reload pending).
     */
    public suspend fun updateLeds(deviceId: Int, colors: IntArray): Boolean {
        val device = usableDevice(deviceId) ?: return false
        val fitted = fitToSize(colors, device.colors)
        trackAck(deviceId)
        send(Requests.updateLeds(deviceId, fitted))
        if (!ProtocolFeatures.hasSignalUpdate(protocolVersion)) {
            // Protocol 6+ servers echo the new colors via SIGNALUPDATE; older ones do not.
            replaceDevice(device.copy(colors = ColorArray.of(fitted)))
        }
        return true
    }

    /** Sets every LED of one zone (`UpdateZoneLEDs`). */
    public suspend fun updateZoneLeds(deviceId: Int, zoneIndex: Int, colors: IntArray): Boolean {
        val device = usableDevice(deviceId) ?: return false
        val zone = device.zones.getOrNull(zoneIndex) ?: return false
        val current = IntArray(zone.ledsCount) { i -> device.colors.rgbAtOrBlack(zone.startIndex + i) }
        val fitted = fitToSize(colors, ColorArray.of(current))
        send(Requests.updateZoneLeds(deviceId, zoneIndex, fitted))
        if (!ProtocolFeatures.hasSignalUpdate(protocolVersion)) {
            val all = device.colors.toIntArray()
            fitted.copyInto(all, zone.startIndex, 0, minOf(fitted.size, all.size - zone.startIndex))
            replaceDevice(device.copy(colors = ColorArray.of(all)))
        }
        return true
    }

    /** Sets a single LED (`UpdateSingleLED`). */
    public suspend fun updateSingleLed(deviceId: Int, ledIndex: Int, color: Int): Boolean {
        val device = usableDevice(deviceId) ?: return false
        if (ledIndex !in 0 until device.ledCount) return false
        send(Requests.updateSingleLed(deviceId, ledIndex, color))
        if (!ProtocolFeatures.hasSignalUpdate(protocolVersion)) {
            val all = device.colors.toIntArray()
            all[ledIndex] = color
            replaceDevice(device.copy(colors = ColorArray.of(all)))
        }
        return true
    }

    /**
     * Switches the device into its software-controlled mode (`SetCustomMode`) and reloads its
     * description so [devices] reflects the newly active mode, like the reference client does.
     */
    public suspend fun setCustomMode(deviceId: Int): Boolean {
        usableDevice(deviceId) ?: return false
        send(Requests.setCustomMode(deviceId))
        refreshDevice(deviceId)
        return true
    }

    /**
     * Activates [modeIndex] with the given parameters (`UpdateMode`). For protocol < 6 the mode's
     * internal `value` must be the one received from the server, which is the case for modes
     * taken from [devices] and modified with `copy()`.
     */
    public suspend fun updateMode(deviceId: Int, modeIndex: Int, mode: DeviceMode): Boolean {
        val device = usableDevice(deviceId) ?: return false
        if (modeIndex !in device.modes.indices) return false
        send(Requests.updateMode(deviceId, modeIndex, mode, protocolVersion))
        replaceDevice(device.copy(activeModeIndex = modeIndex, modes = device.modes.replaceAt(modeIndex, mode)))
        return true
    }

    /** Persists a mode in the device's flash (`SaveMode`, protocol 3+). */
    public suspend fun saveMode(deviceId: Int, modeIndex: Int, mode: DeviceMode): Boolean {
        if (!ProtocolFeatures.hasSaveMode(protocolVersion)) return false
        val device = usableDevice(deviceId) ?: return false
        if (modeIndex !in device.modes.indices) return false
        send(Requests.saveMode(deviceId, modeIndex, mode, protocolVersion))
        replaceDevice(device.copy(activeModeIndex = modeIndex, modes = device.modes.replaceAt(modeIndex, mode)))
        return true
    }

    /** Per-zone mode (`UpdateZoneMode`, protocol 6+). [modeIndex] -1 returns to the device mode. */
    public suspend fun updateZoneMode(deviceId: Int, zoneIndex: Int, modeIndex: Int, mode: DeviceMode?): Boolean {
        if (!ProtocolFeatures.hasZoneModes(protocolVersion)) return false
        val device = usableDevice(deviceId) ?: return false
        val zone = device.zones.getOrNull(zoneIndex) ?: return false
        if (modeIndex >= 0 && modeIndex !in zone.modes.indices) return false
        send(Requests.updateZoneMode(deviceId, zoneIndex, modeIndex, mode, protocolVersion))
        return true
    }

    /** Resizes a resizable zone (`ResizeZone`), e.g. an ARGB header, then reloads the device. */
    public suspend fun resizeZone(deviceId: Int, zoneIndex: Int, newSize: Int): Boolean {
        val device = usableDevice(deviceId) ?: return false
        val zone = device.zones.getOrNull(zoneIndex) ?: return false
        if (newSize < zone.ledsMin || newSize > zone.ledsMax) return false
        send(Requests.resizeZone(deviceId, zoneIndex, newSize))
        refreshDevice(deviceId)
        return true
    }

    /** Asks the server to rescan its hardware (protocol 5+); DEVICE_LIST_UPDATED follows. */
    public suspend fun rescanDevices(): Boolean {
        ensureConnected()
        if (!ProtocolFeatures.hasRescan(protocolVersion)) return false
        send(Requests.requestRescanDevices())
        return true
    }

    // ------------------------------------------------------------------------------------
    // Backpressure support (protocol 6+ ACKs)
    // ------------------------------------------------------------------------------------

    /** True if the server acknowledges LED updates, enabling ACK-based flow control. */
    public val supportsAcks: Boolean get() = ProtocolFeatures.hasAcks(protocolVersion)

    /**
     * Suspends until fewer than [maxInFlight] `UpdateLEDs` packets for [deviceId] are
     * unacknowledged. Returns false on timeout (the window is then reset, so a lost ACK can never
     * stall a stream forever). Without ACK support this returns true immediately.
     */
    public suspend fun awaitLedWindow(deviceId: Int, maxInFlight: Int, timeoutMillis: Long): Boolean {
        if (!supportsAcks) return true
        val window = ackWindows.getOrPut(deviceId) { MutableStateFlow(0) }
        val ok = withTimeoutOrNull(timeoutMillis) { window.first { it < maxInFlight } } != null
        if (!ok) window.value = 0
        return ok
    }

    private fun trackAck(deviceId: Int) {
        if (supportsAcks) ackWindows.getOrPut(deviceId) { MutableStateFlow(0) }.update { it + 1 }
    }

    // ------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------

    private suspend fun negotiateProtocolVersion(): Int {
        if (config.maxProtocolVersion == 0) return 0
        val reply = exchangeOrNull(
            Requests.requestProtocolVersion(config.maxProtocolVersion),
            PacketId.REQUEST_PROTOCOL_VERSION,
            null,
            config.protocolVersionTimeoutMillis,
        )
        // No reply: protocol 0 server (OpenRGB 0.3/0.4), as documented for REQUEST_PROTOCOL_VERSION.
        return reply?.let { Responses.protocolVersion(it) } ?: 0
    }

    private suspend fun readLoop(t: PacketTransport) {
        try {
            while (true) {
                dispatch(t.receive())
            }
        } catch (e: CancellationException) {
            shutdown(null)
        } catch (e: Throwable) {
            val error = ConnectionError.from(e).let {
                if (it.reason == ConnectionErrorReason.UNKNOWN || it.reason == ConnectionErrorReason.TIMEOUT) {
                    it.copy(reason = ConnectionErrorReason.CONNECTION_LOST)
                } else {
                    it
                }
            }
            shutdown(error)
        }
    }

    private fun dispatch(packet: Packet) {
        val waiting = pending.get()
        if (waiting != null && waiting.packetId == packet.packetId &&
            (waiting.deviceId == null || waiting.deviceId == packet.deviceId)
        ) {
            if (pending.compareAndSet(waiting, null)) waiting.deferred.complete(packet)
            return
        }
        when (packet.packetId) {
            PacketId.DEVICE_LIST_UPDATED -> {
                if (!ProtocolFeatures.hasUniqueIds(protocolVersion)) indicesValid = false
                _events.tryEmit(ClientEvent.DeviceListChanged)
                refreshRequests.trySend(Unit)
            }
            PacketId.RGBCONTROLLER_SIGNALUPDATE -> handleSignalUpdate(packet)
            PacketId.ACK -> {
                val ack = Responses.ack(packet)
                if (ack.ackedPacketId == PacketId.RGBCONTROLLER_UPDATELEDS) {
                    ackWindows[ack.deviceId]?.update { (it - 1).coerceAtLeast(0) }
                }
                if (ack.status != AckStatus.OK) _events.tryEmit(ClientEvent.CommandRejected(ack))
            }
            PacketId.SET_SERVER_NAME -> updateServerExtras { it.copy(name = Responses.string(packet)) }
            PacketId.SET_SERVER_HOSTNAME -> updateServerExtras { it.copy(hostname = Responses.string(packet)) }
            PacketId.SET_SERVER_FLAGS -> updateServerExtras { it.copy(flags = Responses.serverFlags(packet)) }
            PacketId.REQUEST_PROTOCOL_VERSION -> {
                // A version reply after we already fell back to protocol 0 means the network was
                // slower than the timeout. The server now expects a newer protocol than we speak
                // (e.g. unique ids instead of indices), so restart the connection instead of guessing.
                if (protocolVersion == 0 && config.maxProtocolVersion > 0) {
                    shutdown(ConnectionError(ConnectionErrorReason.PROTOCOL_ERROR, "Protocol version reply arrived too late"))
                }
            }
            PacketId.DETECTION_STARTED -> _events.tryEmit(ClientEvent.DetectionStarted)
            PacketId.DETECTION_COMPLETE -> _events.tryEmit(ClientEvent.DetectionCompleted)
            else -> Unit // late replies after a timeout and packets for APIs we do not use
        }
    }

    private fun updateServerExtras(transform: (ServerExtras) -> ServerExtras) {
        serverExtras.updateAndGet(transform)
        applyServerExtras()
    }

    /** Copies [serverExtras] into [serverInfo]; called by both sides, so whichever runs last wins with complete data. */
    private fun applyServerExtras() {
        val extras = serverExtras.get()
        _serverInfo.update { it?.copy(serverName = extras.name, serverHostname = extras.hostname, serverFlags = extras.flags) }
    }

    private fun handleSignalUpdate(packet: Packet) {
        val index = _devices.value.indexOfFirst { it.id == packet.deviceId }
        if (index < 0) return // unknown device: a DEVICE_LIST_UPDATED will follow
        val update = try {
            Responses.signalUpdate(packet, protocolVersion, index)
        } catch (e: ProtocolException) {
            // Do not kill the connection for one bad notification; resync instead.
            refreshRequests.trySend(Unit)
            return
        }
        _devices.update { list ->
            list.map { device ->
                if (device.id != update.deviceId) {
                    device
                } else {
                    when (update) {
                        is SignalUpdate.Colors -> device.copy(colors = update.colors)
                        is SignalUpdate.Description -> update.device.copy(index = device.index)
                    }
                }
            }
        }
    }

    private suspend fun refreshLoop() {
        for (signal in refreshRequests) {
            delay(config.deviceListDebounceMillis)
            refreshRequests.tryReceive() // coalesce bursts that arrived during the debounce
            try {
                requestMutex.withLock { reloadDeviceList() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                shutdown(ConnectionError.from(e))
                return
            }
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(config.heartbeatIntervalMillis)
            try {
                requestMutex.withLock {
                    val reply = exchange(Requests.requestControllerCount(), PacketId.REQUEST_CONTROLLER_COUNT, null, config.requestTimeoutMillis)
                    val ids = Responses.controllerIds(reply, protocolVersion)
                    // A missed DEVICE_LIST_UPDATED (or a server without that packet) still converges.
                    if (ids != _devices.value.map { it.id }) refreshRequests.trySend(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                shutdown(ConnectionError(ConnectionErrorReason.CONNECTION_LOST, "Server stopped responding", e))
                return
            }
        }
    }

    /** Sends [request] and waits for the reply; caller must hold [requestMutex] (except during connect). */
    private suspend fun exchange(request: Packet, replyId: Int, replyDeviceId: Int?, timeoutMillis: Long): Packet =
        exchangeOrNull(request, replyId, replyDeviceId, timeoutMillis)
            ?: throw OpenRgbException(ConnectionErrorReason.TIMEOUT, "No reply to ${PacketId.nameOf(request.packetId)} within $timeoutMillis ms")

    private suspend fun exchangeOrNull(request: Packet, replyId: Int, replyDeviceId: Int?, timeoutMillis: Long): Packet? {
        val entry = PendingReply(replyId, replyDeviceId, CompletableDeferred())
        check(pending.compareAndSet(null, entry)) { "Concurrent request/reply exchange" }
        try {
            send(request)
            return withTimeoutOrNull(timeoutMillis) { entry.deferred.await() }
        } finally {
            pending.compareAndSet(entry, null)
        }
    }

    private suspend fun send(packet: Packet) {
        val t = transport ?: throw OpenRgbException(ConnectionErrorReason.CONNECTION_LOST, "Not connected")
        try {
            t.send(packet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = ConnectionError(ConnectionErrorReason.CONNECTION_LOST, e.message ?: "Send failed", e)
            shutdown(error)
            throw OpenRgbException(error)
        }
    }

    private fun ensureConnected() {
        if (lifecycle.get() != Lifecycle.CONNECTED) {
            throw OpenRgbException(ConnectionErrorReason.CONNECTION_LOST, "Not connected")
        }
    }

    private fun usableDevice(deviceId: Int): OpenRgbDevice? {
        ensureConnected()
        if (!ProtocolFeatures.hasUniqueIds(protocolVersion) && !indicesValid) return null
        return device(deviceId)
    }

    private fun shutdown(error: ConnectionError?) {
        val previous = lifecycle.getAndSet(Lifecycle.CLOSED)
        if (previous == Lifecycle.CLOSED) return
        transport?.close()
        pending.getAndSet(null)?.deferred?.completeExceptionally(
            OpenRgbException(error ?: ConnectionError(ConnectionErrorReason.CONNECTION_LOST, "Client closed")),
        )
        refreshRequests.close()
        indicesValid = false
        closedSignal.complete(error)
        scope.cancel()
    }

    private companion object {
        fun fitToSize(colors: IntArray, current: ColorArray): IntArray =
            when {
                colors.size == current.size -> colors
                colors.size > current.size -> colors.copyOf(current.size)
                else -> current.toIntArray().also { colors.copyInto(it) }
            }

        fun ColorArray.rgbAtOrBlack(index: Int): Int = if (index in 0 until size) rgbAt(index) else 0

        fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = mapIndexed { i, v -> if (i == index) value else v }
    }
}
