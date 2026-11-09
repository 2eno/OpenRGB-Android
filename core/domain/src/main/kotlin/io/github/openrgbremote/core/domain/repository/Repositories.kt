package io.github.openrgbremote.core.domain.repository

import io.github.openrgbremote.core.domain.model.AppSettings
import io.github.openrgbremote.core.domain.model.ConnectionStatus
import io.github.openrgbremote.core.domain.model.ConnectionTestResult
import io.github.openrgbremote.core.domain.model.ScanUpdate
import io.github.openrgbremote.core.domain.model.Server
import io.github.openrgbremote.sdk.model.OpenRgbDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Saved servers. */
interface ServerRepository {
    fun observeServers(): Flow<List<Server>>
    suspend fun getServer(id: Long): Server?
    suspend fun findByAddress(host: String, port: Int): Server?

    /** Inserts ([Server.id] == 0) or updates; returns the id. */
    suspend fun save(server: Server): Long
    suspend fun delete(id: Long)
}

/** Live connections to all saved servers. */
interface ConnectionRepository {
    val statuses: StateFlow<Map<Long, ConnectionStatus>>
    val devices: StateFlow<Map<Long, List<OpenRgbDevice>>>

    /** Connects now, even if auto-connect is off. */
    fun connect(serverId: Long)
    fun disconnect(serverId: Long)

    /** Skips the backoff delay / restarts after giving up. */
    fun retry(serverId: Long)

    /** Re-reads the device list of every connected server. */
    suspend fun refreshAll()

    /** Asks the server to re-detect its hardware (protocol 5+). Returns false if unsupported or offline. */
    suspend fun rescanHardware(serverId: Long): Boolean

    /** One-off handshake with an unsaved address, used by the "test connection" button. */
    suspend fun testConnection(host: String, port: Int): ConnectionTestResult
}

/** Finds OpenRGB servers in the local network. */
interface DiscoveryRepository {
    fun scan(port: Int): Flow<ScanUpdate>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setSecurityNoticeAccepted(accepted: Boolean)
}
