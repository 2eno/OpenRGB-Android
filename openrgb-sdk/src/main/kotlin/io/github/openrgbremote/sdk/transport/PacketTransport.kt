package io.github.openrgbremote.sdk.transport

import io.github.openrgbremote.sdk.protocol.Packet
import java.io.Closeable

/**
 * Bidirectional packet stream to one OpenRGB server.
 *
 * Implementations must allow [send] and [receive] to run concurrently from different coroutines
 * and must make [close] unblock a pending [receive] (which then throws).
 */
public interface PacketTransport : Closeable {
    /** Writes the whole packet atomically with respect to other [send] calls. */
    public suspend fun send(packet: Packet)

    /** Suspends until the next complete packet arrived. Throws [java.io.EOFException] when the peer closed. */
    public suspend fun receive(): Packet

    public val isOpen: Boolean
}

/** Opens [PacketTransport]s; replaceable in tests. */
public fun interface TransportFactory {
    public suspend fun connect(host: String, port: Int, connectTimeoutMillis: Int): PacketTransport
}
