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
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PacketTransport] over a pair of blocking streams, read on [Dispatchers.IO]. Used for plain TCP
 * sockets ([SocketTransport]) and for SSH tunnels, which hand out channel streams.
 *
 * [onClose] must unblock a reader stuck in the input stream (closing the socket or the channel
 * does); [isAlive] reports whether the underlying connection still exists.
 */
public open class StreamTransport(
    input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
    private val isAlive: () -> Boolean = { true },
) : PacketTransport {
    private val input = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean get() = !closed.get() && isAlive()

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
     * cancellation (e.g. `withTimeout`) closes the connection to unblock it. Cancelling [receive]
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
        if (closed.compareAndSet(false, true)) runCatching { onClose() }
    }

    private companion object {
        val ioExecutor = Dispatchers.IO.asExecutor()
    }
}

/**
 * [PacketTransport] over a plain blocking TCP socket, driven from [Dispatchers.IO].
 *
 * Blocking `java.net.Socket` I/O is used deliberately instead of NIO selectors: there is exactly
 * one connection per server, one reader and short writes, so a thread per connection is cheaper to
 * reason about. `TCP_NODELAY` is enabled because LED frames are small and latency-sensitive;
 * Nagle's algorithm would otherwise delay frames by up to 200 ms.
 */
public class SocketTransport private constructor(socket: Socket) : StreamTransport(
    input = socket.getInputStream(),
    output = socket.getOutputStream(),
    // Closing the socket unblocks a reader stuck in readFully().
    onClose = {
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    },
    isAlive = { !socket.isClosed },
) {
    public companion object Factory : TransportFactory {
        override suspend fun connect(host: String, port: Int, connectTimeoutMillis: Int): PacketTransport =
            withContext(Dispatchers.IO) {
                // Resolves (DNS) and connects inside the IO context, trying every address.
                SocketTransport(SocketConnector.connect(host, port, connectTimeoutMillis))
            }
    }
}
