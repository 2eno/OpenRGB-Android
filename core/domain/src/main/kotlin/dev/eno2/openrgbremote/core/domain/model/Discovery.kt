package dev.eno2.openrgbremote.core.domain.model

/** A server found by the network scan. */
data class DiscoveredServer(
    val host: String,
    val port: Int,
    /** null: the port is open but the server did not answer the handshake (OpenRGB 0.3/0.4 or another program). */
    val protocolVersion: Int?,
    val serverName: String?,
    val serverHostname: String?,
) {
    val isConfirmed: Boolean get() = protocolVersion != null

    /** Best default name for the connection: the PC's host name, otherwise the IP address. */
    val suggestedName: String get() = serverHostname?.takeIf { it.isNotBlank() } ?: host
}

/** Progress of a network scan. */
sealed interface ScanUpdate {
    /** Scan started on [network] (e.g. "192.168.1.0/24") with [total] addresses. */
    data class Started(val network: String, val total: Int) : ScanUpdate
    data class Progress(val scanned: Int, val total: Int) : ScanUpdate
    data class Found(val server: DiscoveredServer) : ScanUpdate

    /** No IPv4 network (Wi-Fi/Ethernet) to scan, e.g. mobile data only. */
    data object NoLocalNetwork : ScanUpdate
}

/** Result of a "test connection" request for host and port entered by the user. */
sealed interface ConnectionTestResult {
    data class Success(
        val protocolVersion: Int,
        val serverName: String?,
        val serverHostname: String?,
        val deviceCount: Int,
    ) : ConnectionTestResult

    data class Failure(val problem: ConnectionProblem, val detail: String) : ConnectionTestResult

    /**
     * The jump host presented a key that is not confirmed yet ([changed] false) or differs from
     * the confirmed one ([changed] true). The user compares the fingerprint and decides.
     */
    data class UntrustedHostKey(val key: SshHostKey, val changed: Boolean) : ConnectionTestResult
}

/** User preferences relevant before the first connection. */
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val securityNoticeAccepted: Boolean = false,
    /** Frame rate of app-rendered effects, one of [EffectFrameRates.options]. */
    val targetFps: Int = EffectFrameRates.DEFAULT,
)
