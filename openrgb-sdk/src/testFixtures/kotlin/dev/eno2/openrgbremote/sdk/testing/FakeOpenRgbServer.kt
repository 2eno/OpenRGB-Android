package dev.eno2.openrgbremote.sdk.testing

import dev.eno2.openrgbremote.sdk.model.ColorArray
import dev.eno2.openrgbremote.sdk.model.DeviceType
import dev.eno2.openrgbremote.sdk.model.Led
import dev.eno2.openrgbremote.sdk.model.DeviceMode
import dev.eno2.openrgbremote.sdk.model.ModeFlags
import dev.eno2.openrgbremote.sdk.model.OpenRgbDevice
import dev.eno2.openrgbremote.sdk.model.Zone
import dev.eno2.openrgbremote.sdk.model.ZoneType
import dev.eno2.openrgbremote.sdk.protocol.DeviceCodec
import dev.eno2.openrgbremote.sdk.protocol.LittleEndianReader
import dev.eno2.openrgbremote.sdk.protocol.LittleEndianWriter
import dev.eno2.openrgbremote.sdk.protocol.Packet
import dev.eno2.openrgbremote.sdk.protocol.PacketHeader
import dev.eno2.openrgbremote.sdk.protocol.PacketId
import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants
import dev.eno2.openrgbremote.sdk.protocol.UpdateReason
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * In-process OpenRGB SDK server for client tests, modelled on `NetworkServer.cpp` of OpenRGB 1.1
 * (and 0.9 for protocol < 6 behaviour):
 * - protocol 0 servers do not answer REQUEST_PROTOCOL_VERSION,
 * - the negotiated version is min(client, server),
 * - protocol 6 replies are followed by SET_SERVER_NAME, every packet is ACKed and UpdateLEDs is
 *   echoed to all protocol 6 clients as SIGNALUPDATE,
 * - controller data is serialized in the version sent with the request (< 6) or the negotiated one.
 */
class FakeOpenRgbServer(
    val serverProtocolVersion: Int,
    devices: List<OpenRgbDevice> = defaultDevices(),
    private val versionReplyDelayMillis: Long = 0,
    private val ackDelayMillis: Long = 0,
    /** 0 picks a free port; a fixed port lets tests simulate a server coming back. */
    fixedPort: Int = 0,
) : AutoCloseable {

    class Received(val connection: Int, val packet: Packet, val clientVersion: Int)

    private inner class Connection(val number: Int, val socket: Socket) {
        val output: OutputStream = socket.getOutputStream()
        @Volatile var clientVersion = 0

        @Synchronized
        fun send(packet: Packet) {
            output.write(packet.encode())
            output.flush()
        }
    }

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), fixedPort), 50)
    }
    private val connections = CopyOnWriteArrayList<Connection>()
    private val connectionCounter = AtomicInteger()

    val port: Int get() = serverSocket.localPort
    val received = CopyOnWriteArrayList<Received>()
    val clientNames = CopyOnWriteArrayList<String>()

    @Volatile var devices: List<OpenRgbDevice> = devices

    /** When true the server swallows every packet without answering (simulates a hung PC). */
    @Volatile var unresponsive = false

    init {
        thread(isDaemon = true, name = "fake-openrgb-accept") {
            while (!serverSocket.isClosed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                socket.tcpNoDelay = true
                val connection = Connection(connectionCounter.incrementAndGet(), socket)
                connections += connection
                thread(isDaemon = true, name = "fake-openrgb-conn-${connection.number}") { serve(connection) }
            }
        }
    }

    val connectionCount: Int get() = connectionCounter.get()

    fun packets(id: Int): List<Packet> = received.filter { it.packet.packetId == id }.map { it.packet }

    fun broadcastDeviceListUpdated() = connections.forEach { runCatching { it.send(Packet(0, PacketId.DEVICE_LIST_UPDATED)) } }

    fun dropAllConnections() {
        connections.forEach { runCatching { it.socket.close() } }
        connections.clear()
    }

    override fun close() {
        runCatching { serverSocket.close() }
        dropAllConnections()
    }

    private fun serve(c: Connection) {
        val input = DataInputStream(c.socket.getInputStream())
        try {
            while (true) {
                val headerBytes = ByteArray(ProtocolConstants.HEADER_SIZE)
                input.readFully(headerBytes)
                val header = PacketHeader.decode(headerBytes)
                val payload = ByteArray(header.payloadSize).also { input.readFully(it) }
                val packet = Packet(header.deviceId, header.packetId, payload)
                received += Received(c.number, packet, c.clientVersion)
                if (!unresponsive) handle(c, packet)
            }
        } catch (_: Exception) {
            connections.remove(c)
            runCatching { c.socket.close() }
        }
    }

    private fun indexOf(c: Connection, deviceId: Int): Int =
        if (c.clientVersion >= 6) devices.indexOfFirst { it.id == deviceId } else deviceId.takeIf { it in devices.indices } ?: -1

    private fun handle(c: Connection, packet: Packet) {
        when (packet.packetId) {
            PacketId.REQUEST_PROTOCOL_VERSION -> {
                if (serverProtocolVersion == 0) return // OpenRGB 0.3/0.4 do not know this packet
                if (versionReplyDelayMillis > 0) Thread.sleep(versionReplyDelayMillis)
                c.clientVersion = minOf(LittleEndianReader(packet.payload).i32(), serverProtocolVersion)
                c.send(Packet(0, PacketId.REQUEST_PROTOCOL_VERSION, LittleEndianWriter().i32(serverProtocolVersion).toByteArray()))
                if (c.clientVersion >= 6) c.send(Packet(0, PacketId.SET_SERVER_NAME, LittleEndianWriter().cString("Fake OpenRGB").toByteArray()))
            }
            PacketId.SET_CLIENT_NAME -> clientNames += LittleEndianReader(packet.payload).rawString(packet.payload.size)
            PacketId.SET_CLIENT_FLAGS ->
                if (c.clientVersion >= 6) c.send(Packet(0, PacketId.SET_SERVER_FLAGS, LittleEndianWriter().i32(1).toByteArray()))
            PacketId.SET_CLIENT_HOSTNAME ->
                if (c.clientVersion >= 6) c.send(Packet(0, PacketId.SET_SERVER_HOSTNAME, LittleEndianWriter().cString("fakehost").toByteArray()))
            PacketId.REQUEST_CONTROLLER_COUNT -> {
                val w = LittleEndianWriter().i32(devices.size)
                if (c.clientVersion >= 6) devices.forEach { w.i32(it.id) }
                c.send(Packet(0, PacketId.REQUEST_CONTROLLER_COUNT, w.toByteArray()))
            }
            PacketId.REQUEST_CONTROLLER_DATA -> {
                val version = if (c.clientVersion < 6) {
                    if (packet.payload.size == 4) LittleEndianReader(packet.payload).i32() else 0
                } else {
                    c.clientVersion
                }
                val index = indexOf(c, packet.deviceId)
                if (index >= 0) {
                    c.send(Packet(packet.deviceId, PacketId.REQUEST_CONTROLLER_DATA, DeviceCodec.encodeControllerData(devices[index], version)))
                }
            }
            PacketId.RGBCONTROLLER_UPDATEMODE -> {
                val index = indexOf(c, packet.deviceId)
                if (index >= 0) {
                    val modeIndex = LittleEndianReader(packet.payload, 4).i32()
                    val mode = DeviceCodec.decodeMode(LittleEndianReader(packet.payload, 8), c.clientVersion)
                    val device = devices[index]
                    if (modeIndex in device.modes.indices) {
                        val modes = device.modes.toMutableList().also { it[modeIndex] = mode }
                        devices = devices.toMutableList().also { it[index] = device.copy(activeModeIndex = modeIndex, modes = modes) }
                    }
                }
            }
            PacketId.RGBCONTROLLER_UPDATELEDS -> {
                val index = indexOf(c, packet.deviceId)
                if (index >= 0) {
                    val r = LittleEndianReader(packet.payload, 4)
                    val colors = DeviceCodec.decodeColors(r)
                    devices = devices.toMutableList().also { it[index] = it[index].copy(colors = colors) }
                    for (other in connections) {
                        if (other.clientVersion >= 6) {
                            val body = LittleEndianWriter().i32(0).i32(UpdateReason.UPDATE_LEDS)
                            DeviceCodec.encodeColors(body, colors)
                            body.patchI32(0, body.size)
                            runCatching { other.send(Packet(devices[index].id, PacketId.RGBCONTROLLER_SIGNALUPDATE, body.toByteArray())) }
                        }
                    }
                }
                if (ackDelayMillis > 0) Thread.sleep(ackDelayMillis)
            }
        }
        if (c.clientVersion >= 6) {
            c.send(Packet(packet.deviceId, PacketId.ACK, LittleEndianWriter().i32(packet.packetId).i32(0).toByteArray()))
        }
    }

    companion object {
        fun device(id: Int, name: String, ledCount: Int, type: DeviceType = DeviceType.LEDSTRIP): OpenRgbDevice = OpenRgbDevice(
            id = id,
            index = 0,
            type = type,
            rawType = type.raw,
            name = name,
            vendor = "Fake",
            modes = listOf(
                DeviceMode(name = "Direct", value = 0, flags = ModeFlags.HAS_PER_LED_COLOR, colorMode = 1),
                DeviceMode(name = "Rainbow", value = 2, flags = ModeFlags.HAS_SPEED, speedMin = 0, speedMax = 10, speed = 5),
            ),
            activeModeIndex = 1,
            zones = listOf(Zone("Strip", ZoneType.LINEAR, ZoneType.LINEAR.raw, 0, ledCount.toLong(), ledCount)),
            leds = List(ledCount) { Led("LED $it") },
            colors = ColorArray.of(IntArray(ledCount)),
        )

        fun defaultDevices(): List<OpenRgbDevice> = listOf(
            device(100, "Fake Strip", 8),
            device(200, "Fake Keyboard", 4, DeviceType.KEYBOARD),
        )
    }
}

/** Accepts connections and answers with something that is not OpenRGB. */
class GarbageServer : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = serverSocket.localPort

    init {
        thread(isDaemon = true) {
            while (!serverSocket.isClosed) {
                val s = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching {
                    s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }
    }

    override fun close() {
        serverSocket.close()
    }
}

/** A port on localhost that is guaranteed to be closed. */
fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
