package dev.eno2.openrgbremote.sdk.client

import dev.eno2.openrgbremote.sdk.protocol.ProtocolException
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Categories of connection problems. The app maps each one to a plain-language,
 * localized explanation with a suggested fix.
 */
public enum class ConnectionErrorReason {
    /** Host name could not be resolved (typo, no DNS, mDNS name not supported by the network). */
    UNKNOWN_HOST,

    /** The PC answered but nothing listens on the port: SDK server not started or wrong port. */
    CONNECTION_REFUSED,

    /** No answer in time: wrong IP, PC asleep/off, firewall dropping packets. */
    TIMEOUT,

    /** No route to the host or network: different network/VLAN, Wi-Fi off, VPN down. */
    UNREACHABLE,

    /** Something answered on the port, but it does not speak the OpenRGB protocol. */
    NOT_OPENRGB,

    /** The server sent malformed data or rejected the protocol. */
    PROTOCOL_ERROR,

    /** An established connection broke (server closed, PC went to sleep, Wi-Fi changed). */
    CONNECTION_LOST,

    /** Anything else. */
    UNKNOWN,
}

/** A classified connection error, keeping the original cause for logs. */
public data class ConnectionError(
    val reason: ConnectionErrorReason,
    val message: String,
    val cause: Throwable? = null,
) {
    public companion object {
        /** Maps low-level exceptions (JVM and Android flavours) to a [ConnectionErrorReason]. */
        public fun from(t: Throwable): ConnectionError {
            if (t is OpenRgbException) return t.error
            val text = (t.message ?: "").lowercase()
            val reason = when {
                t is UnknownHostException -> ConnectionErrorReason.UNKNOWN_HOST
                t is ProtocolException && "magic" in text -> ConnectionErrorReason.NOT_OPENRGB
                t is ProtocolException -> ConnectionErrorReason.PROTOCOL_ERROR
                t is SocketTimeoutException || t is TimeoutCancellationException -> ConnectionErrorReason.TIMEOUT
                t is NoRouteToHostException || t is PortUnreachableException -> ConnectionErrorReason.UNREACHABLE
                t is ConnectException && ("refused" in text || "econnrefused" in text) -> ConnectionErrorReason.CONNECTION_REFUSED
                t is ConnectException && ("timed out" in text || "etimedout" in text) -> ConnectionErrorReason.TIMEOUT
                "ehostunreach" in text || "enetunreach" in text || "unreachable" in text || "no route" in text ->
                    ConnectionErrorReason.UNREACHABLE
                t is ConnectException -> ConnectionErrorReason.UNREACHABLE
                t is EOFException -> ConnectionErrorReason.CONNECTION_LOST
                t is SocketException -> ConnectionErrorReason.CONNECTION_LOST
                else -> ConnectionErrorReason.UNKNOWN
            }
            return ConnectionError(reason, t.message ?: t::class.java.simpleName, t)
        }
    }
}

/** Exception carrying an already classified [ConnectionError]. */
public class OpenRgbException(public val error: ConnectionError) : Exception(error.message, error.cause) {
    public constructor(reason: ConnectionErrorReason, message: String, cause: Throwable? = null) :
        this(ConnectionError(reason, message, cause))
}
