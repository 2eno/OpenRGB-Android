package dev.eno2.openrgbremote.core.domain.model

import dev.eno2.openrgbremote.sdk.protocol.ProtocolConstants

/** A PC running the OpenRGB SDK server, as saved by the user. */
data class Server(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = ProtocolConstants.DEFAULT_PORT,
    /** Connect automatically whenever the app is in use. */
    val autoConnect: Boolean = true,
    val createdAtMillis: Long = 0,
) {
    val address: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
}
