package dev.eno2.openrgbremote.core.domain.model

/** Why a connection failed, in categories the UI can explain in plain language. */
enum class ConnectionProblem {
    UNKNOWN_HOST,
    CONNECTION_REFUSED,
    TIMEOUT,
    UNREACHABLE,
    NOT_OPENRGB,
    PROTOCOL_ERROR,
    CONNECTION_LOST,
    NO_NETWORK,
    UNKNOWN,
    JUMP_HOST_UNREACHABLE,
    JUMP_HOST_AUTH_FAILED,
    JUMP_HOST_KEY_UNKNOWN,
    JUMP_HOST_KEY_CHANGED,
    JUMP_TARGET_UNREACHABLE,

    /** Android 17+ blocks the local network until the user grants ACCESS_LOCAL_NETWORK. */
    LOCAL_NETWORK_DENIED,
}

/** Connection state of one saved server. */
sealed interface ConnectionStatus {
    /** Not connected and not trying (auto-connect off or app in background). */
    data object Offline : ConnectionStatus

    data class Connecting(val attempt: Int) : ConnectionStatus

    data class Connected(
        val protocolVersion: Int,
        val serverName: String?,
        val serverHostname: String?,
    ) : ConnectionStatus

    /** Waiting before the next automatic attempt. */
    data class Reconnecting(
        val attempt: Int,
        val retryInMillis: Long,
        val problem: ConnectionProblem,
        val detail: String,
    ) : ConnectionStatus

    /** Gave up; only a manual retry reconnects. */
    data class Failed(val problem: ConnectionProblem, val detail: String) : ConnectionStatus
}

val ConnectionStatus.isConnected: Boolean get() = this is ConnectionStatus.Connected

/** Problem of a failed or retrying connection, null otherwise. */
val ConnectionStatus.problem: ConnectionProblem?
    get() = when (this) {
        is ConnectionStatus.Reconnecting -> problem
        is ConnectionStatus.Failed -> problem
        else -> null
    }
