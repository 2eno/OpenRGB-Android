package dev.eno2.openrgbremote.sdk.transport

import dev.eno2.openrgbremote.sdk.protocol.Packet
import dev.eno2.openrgbremote.sdk.protocol.PacketHeader
import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PacketTransport] over a plain blocking TCP socket, driven from [Dispatchers.IO].
 *
 * Blocking `java.net.Socket` I/O is used deliberately instead of NIO selectors: there is exactly
 * one connection per server, one reader and short writes, so a thread per connection is cheaper to
 * reason about. `TCP_NODELAY` is enabled because LED frames are small and latency-sensitive;
 * Nagle's algorithm would otherwise delay frames by up to 200 ms.
 */
public class SocketTransport private constructor(private val socket: Socket) : PacketTransport {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean get() = !closed.get() && !socket.isClosed

    override suspend fun send(packet: Packet) {
        val bytes = packet.encode()
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                if (!isOpen) throw IOException("Connection closed")
                output.write(bytes)
                output.flush()
            }
        }
    }

    /**
     * Blocking stream reads ignore coroutine cancellation, so the read runs on an IO thread and a
     * cancellation (e.g. `withTimeout`) closes the socket to unblock it. Cancelling [receive]
     * therefore ends the connection, which is what every caller wants anyway.
     */
    override suspend fun receive(): Packet = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { close() }
        ioExecutor.execute {
            val result = runCatching { readPacket() }
            result.fold(
                onSuccess = { cont.resume(it) },
                onFailure = { if (cont.isActive) cont.resumeWithException(it) },
            )
        }
    }

    private fun readPacket(): Packet {
        val headerBytes = ByteArray(ProtocolConstants.HEADER_SIZE)
        try {
            input.readFully(headerBytes)
        } catch (e: EOFException) {
            throw EOFException("Server closed the connection")
        }
        val header = PacketHeader.decode(headerBytes)
        val payload = if (header.payloadSize == 0) Packet.EMPTY else ByteArray(header.payloadSize).also { input.readFully(it) }
        return Packet(header.deviceId, header.packetId, payload)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Closing the socket unblocks a reader stuck in readFully().
            runCatching { socket.shutdownInput() }
            runCatching { socket.close() }
        }
    }

    public companion object Factory : TransportFactory {
        private val ioExecutor = Dispatchers.IO.asExecutor()

        override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Int): PacketTransport =
            withContext(Dispatchers.IO) {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    // Resolving inside the IO context: InetSocketAddress(host, port) performs DNS lookup.
                    socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
                    SocketTransport(socket)
                } catch (e: Throwable) {
                    runCatching { socket.close() }
                    throw e
                }
            }
    }
}
