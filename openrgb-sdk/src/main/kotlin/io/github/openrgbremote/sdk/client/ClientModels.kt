package io.github.openrgbremote.sdk.client

import io.github.openrgbremote.sdk.protocol.Ack
import io.github.openrgbremote.sdk.protocol.ProtocolConstants

/** Tunables of [OpenRgbClient]. Defaults suit a phone on home Wi-Fi. */
public data class OpenRgbClientConfig(
    /** Name shown in OpenRGB's "SDK Server" tab. */
    val clientName: String = "OpenRGB Remote (Android)",
    /** Sent as client hostname to protocol 6+ servers, null to skip. */
    val clientHostname: String? = null,
    /** Highest protocol version offered to the server. */
    val maxProtocolVersion: Int = ProtocolConstants.CLIENT_PROTOCOL_VERSION,
    val connectTimeoutMillis: Int = 4_000,
    /**
     * How long to wait for the protocol version reply. Protocol 0 servers (OpenRGB 0.3/0.4) never
     * answer; the reference client waits 1 s before assuming version 0. We wait a bit longer
     * because phones on Wi-Fi/VPN see higher latency than a desktop on localhost.
     */
    val protocolVersionTimeoutMillis: Long = 2_000,
    /** Timeout for request/reply exchanges such as controller data. */
    val requestTimeoutMillis: Long = 5_000,
    /** Interval of the liveness check (controller count request) while connected, 0 disables it. */
    val heartbeatIntervalMillis: Long = 15_000,
    /** Delay used to coalesce bursts of DEVICE_LIST_UPDATED (e.g. during detection). */
    val deviceListDebounceMillis: Long = 250,
) {
    init {
        require(maxProtocolVersion in 0..ProtocolConstants.CLIENT_PROTOCOL_VERSION) { "Unsupported protocol version $maxProtocolVersion" }
    }
}

/** Facts learned about the server during the handshake. */
public data class ServerInfo(
    val host: String,
    val port: Int,
    /** Highest version the server announced (0 when it did not answer). */
    val serverProtocolVersion: Int,
    /** Version actually used: min(client, server). */
    val protocolVersion: Int,
    /** `SET_SERVER_NAME` (protocol 6+), null otherwise. */
    val serverName: String? = null,
    /** `SET_SERVER_HOSTNAME` (protocol 6+), null otherwise. */
    val serverHostname: String? = null,
    /** `SET_SERVER_FLAGS` (protocol 6+), null otherwise. */
    val serverFlags: Int? = null,
)

/** Notifications from the server that are not plain state changes. */
public sealed interface ClientEvent {
    /** The server signalled DEVICE_LIST_UPDATED; a refresh is in progress. */
    public data object DeviceListChanged : ClientEvent

    /** The device list was re-synchronized. */
    public data class DevicesRefreshed(val count: Int) : ClientEvent

    /** Server-side detection started (protocol 6+). */
    public data object DetectionStarted : ClientEvent

    /** Server-side detection finished (protocol 6+). */
    public data object DetectionCompleted : ClientEvent

    /** The server rejected a command (protocol 6+ ACK with error status). */
    public data class CommandRejected(val ack: Ack) : ClientEvent
}
