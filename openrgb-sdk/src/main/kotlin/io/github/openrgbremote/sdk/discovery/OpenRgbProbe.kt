package io.github.openrgbremote.sdk.discovery

import io.github.openrgbremote.sdk.protocol.PacketId
import io.github.openrgbremote.sdk.protocol.ProtocolConstants
import io.github.openrgbremote.sdk.protocol.Requests
import io.github.openrgbremote.sdk.protocol.Responses
import io.github.openrgbremote.sdk.transport.PacketTransport
import io.github.openrgbremote.sdk.transport.SocketTransport
import io.github.openrgbremote.sdk.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A server found by [OpenRgbProbe]. */
public data class ProbeResult(
    val host: String,
    val port: Int,
    /** Server protocol version, or null if the port was open but the server did not answer. */
    val protocolVersion: Int?,
    /** `SET_SERVER_NAME` (protocol 6+). */
    val serverName: String? = null,
    /** `SET_SERVER_HOSTNAME` (protocol 6+), a good default for the connection name. */
    val serverHostname: String? = null,
) {
    /** True if the server answered with valid OpenRGB packets. */
    val isConfirmed: Boolean get() = protocolVersion != null
}

/**
 * Checks whether an OpenRGB SDK server listens on host:port by performing the first step of the
 * handshake. A plain TCP connect is not enough: many devices have random open ports.
 */
public class OpenRgbProbe(
    private val transportFactory: TransportFactory = SocketTransport.Factory,
    private val clientName: String = "OpenRGB Remote (scan)",
) {
    /**
     * @return null if nothing accepts connections on the port or something else answered,
     *   a [ProbeResult] otherwise.
     */
    public suspend fun probe(
        host: String,
        port: Int = ProtocolConstants.DEFAULT_PORT,
        connectTimeoutMillis: Int = 400,
        replyTimeoutMillis: Long = 1_500,
    ): ProbeResult? {
        val transport: PacketTransport = try {
            transportFactory.connect(host, port, connectTimeoutMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return transport.use { t ->
            try {
                t.send(Requests.requestProtocolVersion())
                val reply = withTimeoutOrNull(replyTimeoutMillis) { t.receive() }
                    ?: return@use ProbeResult(host, port, protocolVersion = null) // OpenRGB 0.3/0.4 never answer
                if (reply.packetId != PacketId.REQUEST_PROTOCOL_VERSION) return@use null
                val version = Responses.protocolVersion(reply)
                var name: String? = null
                var hostname: String? = null
                if (version >= 6) {
                    // Protocol 6 servers follow up with SET_SERVER_NAME and answer SET_CLIENT_HOSTNAME
                    // with SET_SERVER_HOSTNAME; collect both briefly for a friendly default name.
                    t.send(Requests.setClientHostname(clientName))
                    withTimeoutOrNull(500) {
                        while (name == null || hostname == null) {
                            val p = t.receive()
                            when (p.packetId) {
                                PacketId.SET_SERVER_NAME -> name = Responses.string(p)
                                PacketId.SET_SERVER_HOSTNAME -> hostname = Responses.string(p)
                            }
                        }
                    }
                }
                ProbeResult(host, port, version, name, hostname)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // wrong magic or garbage: not an OpenRGB server
            }
        }
    }
}
